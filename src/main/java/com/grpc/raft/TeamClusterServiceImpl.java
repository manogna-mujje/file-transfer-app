package com.grpc.raft;

import static io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import com.util.ConfigUtil;
import com.util.Connection;
import grpc.Raft;
import grpc.RaftServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.apache.log4j.Logger;

import grpc.Team;
import grpc.TeamClusterServiceGrpc;
import grpc.Team.Ack;
import grpc.Team.ChunkLocations;
import io.grpc.stub.StreamObserver;

/**
 * This class communicates the the RAFT Servers
 * @author Sricheta's computer
 *
 */
public class TeamClusterServiceImpl extends TeamClusterServiceGrpc.TeamClusterServiceImplBase{

	private final static Logger logger = Logger.getLogger(TeamClusterServiceImpl.class);
	private final String KEY_DELIMINATOR= "#";
	private final static int POLL_DEADLINE = 300; //how long until polling other nodes times out. timeout = rejected vote

	private RaftServer server;

	public TeamClusterServiceImpl(){
		super();
	}

	public TeamClusterServiceImpl(RaftServer serv){
		super();
		server = serv;
	}


	@Override
	public void heartbeat(Team.Ack request, StreamObserver<Team.Ack> responseObserver) {

		Team.Ack  response = Team.Ack.newBuilder().setIsAck(true).setMessageId(request.getMessageId()).build();
		responseObserver.onNext(response);
		responseObserver.onCompleted();

	}

	/**
	 * This method updates the RAFT HashMap with the DB address where it stored the chunk
	 * @param request
	 * @param responseObserver
	 */
	@Override
	public void updateChunkLocations(Team.ChunkLocations request, StreamObserver<Team.Ack> responseObserver) {

		//Forward command to leader and return that
		if(server.raftState != 2) {
			System.out.println("updateChunkLocations called by non-leader! Forwarding to "+server.currentLeaderIndex);
			Connection con = ConfigUtil.raftNodes.get((int) server.currentLeaderIndex);
			ManagedChannel channel = ManagedChannelBuilder
					.forTarget(con.getIP() + ":" + con.getPort()).usePlaintext(true).build();
			TeamClusterServiceGrpc.TeamClusterServiceBlockingStub stub = TeamClusterServiceGrpc.newBlockingStub(channel);

			responseObserver.onNext(stub.updateChunkLocations(request));
			responseObserver.onCompleted();
			return;
		}

		logger.debug("UpdateChunkLocations started.. ");
		String key = request.getFileName()+KEY_DELIMINATOR+ request.getChunkId()+KEY_DELIMINATOR+request.getMessageId();
		String value = server.data.get(key);
		logger.debug("Value presently stored in hashmap for key "+key+": "+value);

		//MaxChunks$IP1,IP2,IP3
		// If file is getting uploaded for the first time.
		if(value == null) {
			value = request.getMaxChunks()+"$";
			StringBuilder builder = new StringBuilder();
			builder.append(value);
			for(int i =0;i<request.getDbAddressesCount();i++) {
				builder.append(request.getDbAddresses(i));
				builder.append(",");
			}
			value = builder.toString();
			value = value.substring(0, value.length() - 1);
			//server.data.put(key, value);
			logger.debug("Value to store in server: "+value);
		}else {
			//If key is already there, only update the db addresses.
			String[] valArr = value.split("\\$");
			StringBuilder builder = new StringBuilder();
			for(int i =0;i<request.getDbAddressesCount();i++) {
				builder.append(request.getDbAddresses(i));
				if(i != request.getDbAddressesCount()-1)
					builder.append(",");
			}
			valArr[1] = builder.toString();
			String newValue = valArr[0] + "$"+ valArr[1];
			value = newValue;
			//server.data.put(key, newValue);

		}

		boolean acceptChange = false;
		if(pollValueChange(key, value)){
			confirmValueChange(key, value);
			acceptChange = true;
			server.data.put(key, value);
			System.out.println("key/value: "+key+" , "+value);
			server.numEntries++;
			System.out.println("Leader pushed value change! Entries="+server.numEntries);
		}

		Team.Ack response = Team.Ack.newBuilder()
				.setMessageId(request.getMessageId())
				.setIsAck(acceptChange)
				.build();

		// Use responseObserver to send a single response back
		responseObserver.onNext(response);

		logger.debug("UpdateChunkLocations ended.. ");
		responseObserver.onCompleted();

	}

	private boolean pollValueChange(String key, String value){
		//Check with other nodes if we can make a change
		int accepts = 1;
		for(int i = 0; i < ConfigUtil.raftNodes.size(); i++){
			if(i == server.index)
				continue;

			Connection con = ConfigUtil.raftNodes.get(i);
			ManagedChannel channel = ManagedChannelBuilder
					.forTarget(con.getIP()+":"+con.getPort())
					.usePlaintext(true).build();

			RaftServiceGrpc.RaftServiceBlockingStub stub =
					RaftServiceGrpc.newBlockingStub(channel);

			Raft.Entry entry = Raft.Entry.newBuilder()
					.setKey(key).setValue(value).build();
			Raft.EntryAppend voteReq = Raft.EntryAppend.newBuilder()
					.setEntry(entry)
					.setTerm(server.term)
					.setLeader(server.index)
					.setAppendedEntries(server.numEntries)
					.build();

			boolean acceptChange = true;
			Raft.Response vote = null;
			try {
				vote = stub.withDeadlineAfter(POLL_DEADLINE, TimeUnit.MILLISECONDS).pollEntry(voteReq);
			}catch (Exception e){
				//logger.debug("Deadline for vote response has passed, vote rejected.");
			}
			if(vote == null || !vote.getAccept())
				acceptChange = false;

			if(acceptChange) {
				accepts++;
				logger.debug("Accepted vote from "+i);
			}else{
				logger.debug("Rejected vote from "+i);
			}

			channel.shutdownNow();

			//Check if no point continuing to vote
			if(accepts > ConfigUtil.raftNodes.size()/2)
				return true;
		}
		if(accepts > ConfigUtil.raftNodes.size()/2)
			return true;
		else
			return false;
	}

	private void confirmValueChange(String key, String value){
		for(int i = 0; i < ConfigUtil.raftNodes.size(); i++){
			if(i == server.index)
				continue;

			Connection con = ConfigUtil.raftNodes.get(i);
			ManagedChannel channel = ManagedChannelBuilder
					.forTarget(con.getIP()+":"+con.getPort())
					.usePlaintext(true).build();

			RaftServiceGrpc.RaftServiceBlockingStub stub =
					RaftServiceGrpc.newBlockingStub(channel);

			Raft.Entry entry = Raft.Entry.newBuilder()
					.setKey(key).setValue(value).build();
			Raft.EntryAppend voteReq = Raft.EntryAppend.newBuilder()
					.setEntry(entry)
					.setTerm(server.term)
					.setLeader(server.index)
					.setAppendedEntries(server.numEntries)
					.build();

			try {
				Raft.Response vote = stub.acceptEntry(voteReq);
			}catch (Exception e){

			}

			channel.shutdownNow();
		}
	}

	/**
	 * Proxy to fetch file locations of a particular file and chunk from RAFT HashMap
	 * 
	 * rpc GetChunkLocations (FileData) returns (ChunkLocations)
	 * 
	 */

	@Override
	public void getChunkLocations(grpc.Team.FileData request,
			io.grpc.stub.StreamObserver<grpc.Team.ChunkLocations> responseObserver) {

		//Forward command to leader and return that
		
		if(server.raftState != 2) {
			System.out.println("getChunkLocations called by non-leader! Forwarding to "+server.currentLeaderIndex);
			Connection con = ConfigUtil.raftNodes.get((int) server.currentLeaderIndex);
			ManagedChannel channel = ManagedChannelBuilder
					.forTarget(con.getIP() + ":" + con.getPort()).usePlaintext(true).build();
			TeamClusterServiceGrpc.TeamClusterServiceBlockingStub stub = TeamClusterServiceGrpc.newBlockingStub(channel);

			responseObserver.onNext(stub.getChunkLocations(request));
			responseObserver.onCompleted();
			return;
		}
		
		String key = request.getFileName()+KEY_DELIMINATOR+ request.getChunkId()+KEY_DELIMINATOR+request.getMessageId();
		String value = server.data.get(key);

		logger.debug("Getting chunk data: key="+key+"\nvalue="+value);
		String[] valArr = value.split("\\$");
		ArrayList<String> arrayList = new ArrayList<String>(Arrays.asList(valArr[1]));

		ChunkLocations ch = ChunkLocations.newBuilder().setChunkId(request.getChunkId())
				.setFileName(request.getFileName())
				.addAllDbAddresses(arrayList)
				.setMessageId(request.getMessageId())
				.setMaxChunks(Long.parseLong(valArr[0]))
				.build();
		responseObserver.onNext(ch);
		responseObserver.onCompleted();
	}


}
