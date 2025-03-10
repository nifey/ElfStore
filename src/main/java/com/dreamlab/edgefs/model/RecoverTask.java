package com.dreamlab.edgefs.model;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;

import org.apache.thrift.TException;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.transport.TFramedTransport;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TTransport;
import org.apache.thrift.transport.TTransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dreamlab.edgefs.misc.Constants;
import com.dreamlab.edgefs.servicehandler.FogServiceHandler;
import com.dreamlab.edgefs.thrift.FindReplica;
import com.dreamlab.edgefs.thrift.FogService;
import com.dreamlab.edgefs.thrift.NodeInfoData;
import com.dreamlab.edgefs.thrift.ReadReplica;
import com.dreamlab.edgefs.thrift.WritableFogData;
import com.dreamlab.edgefs.thrift.WritePreference;

public class RecoverTask implements Comparable<RecoverTask>, Runnable {

	private static final Logger LOGGER = LoggerFactory.getLogger(RecoverTask.class);

	private short edgeId;
	private Integer reliability;
//	private String microbatchId;
	private Long microbatchId;
	private FogServiceHandler handler;

	public RecoverTask() {

	}

	public RecoverTask(short edgeId, Integer reliability, /*String microbatchId,*/
			Long microbatchId, FogServiceHandler handler) {
		super();
		this.edgeId = edgeId;
		this.reliability = reliability;
		this.microbatchId = microbatchId;
		this.handler = handler;
	}

	public short getEdgeId() {
		return edgeId;
	}

	public void setEdgeId(short edgeId) {
		this.edgeId = edgeId;
	}

	public Integer getReliability() {
		return reliability;
	}

	public Long getMicrobatchId() {
		return microbatchId;
	}

	@Override
	public int compareTo(RecoverTask task) {
		return task.getReliability().compareTo(this.reliability);
	}

	@Override
	public void run() {
		Short edgeId = getEdgeId();
		if(edgeId == null)
			return;
		EdgeInfo edgeInfo = handler.getFog().getLocalEdgesMap().get(edgeId);
		if(edgeInfo == null)
			return;
		int edgeReliability = getReliability();
		Long microBatchId = getMicrobatchId();
		LOGGER.info("Recovery for microbatchId : " + microBatchId + " belonging to EdgeId: " + edgeId
		+ " starts at " + System.currentTimeMillis());
		List<FindReplica> currentReplicas = new ArrayList<>();
		try {
			currentReplicas = handler.find(microBatchId, true, true, null);
		} catch (TException e) {
			LOGGER.error("Error while finding replicas for data recovery : " + e);
			e.printStackTrace();
			return;
		}
		ReadReplica read = null;
		for (FindReplica r : currentReplicas) {
			NodeInfoData node = r.getNode();
			TTransport transport = new TFramedTransport(new TSocket(node.getNodeIP(), node.getPort()));
			try {
				transport.open();
			} catch (TTransportException e) {
				LOGGER.error("Unable to contact for recovery : " + e);
				e.printStackTrace();
				continue;
			}
			TProtocol protocol = new TBinaryProtocol(transport);
			FogService.Client fogClient = new FogService.Client(protocol);
			try {
				Map<String,Long> formatSize = new HashMap<>();
				//This map will only contain a single entry
				formatSize = fogClient.requestCompFormatSize(microbatchId);
				//Read the first and only entry in the map
				Map.Entry<String,Long> entry = formatSize.entrySet().iterator().next();
				String compFormat = entry.getKey();
				Long uncompSize = entry.getValue();
				read = fogClient.read(microBatchId, true, compFormat, uncompSize);
				LOGGER.info("Write complete for recovery");
				break;
			} catch (TException e) {
				LOGGER.error("Error while reading data during recovery : " + e);
				e.printStackTrace();
				continue;
			}
		}
		List<WritableFogData> newReplicas = new ArrayList<>();
		if (read != null) {
			long datalength = read.getData().length;
			//identifyreplicas take MB size of datalength
			datalength = datalength/(1024 * 1024);
//			newReplicas = handler.identifyReplicas(datalength, null, (double) (edgeReliability * 1.0) / 100, 1, 2);
			newReplicas = handler.identifyReplicas(microBatchId, datalength, true, (double) (edgeReliability * 1.0) / 100, 1, 2);
		}
		if (read != null && read.getStatus() == Constants.SUCCESS) {
			LOGGER.info("Found new replicas for writing");
			//WRITE_FAILURE:: Write failures are not handled in current implementation
			for (WritableFogData fogData : newReplicas) {
				NodeInfoData node = fogData.getNode();
				TTransport transport = new TFramedTransport(new TSocket(node.getNodeIP(), node.getPort()));
				try {
					transport.open();
				} catch (TTransportException e) {
					//write failure not accounted
					transport.close();
					LOGGER.error("Unable to contact for writing while recovery : " + e);
					e.printStackTrace();
					continue;
				}
				TProtocol protocol = new TBinaryProtocol(transport);
				FogService.Client fogClient = new FogService.Client(protocol);
				try {
					byte[] data = read.getData();
					ByteBuffer buffer = ByteBuffer.allocate(data.length);
					buffer.put(data, 0, data.length);
					buffer.flip();
					fogClient.write(read.getMetadata(), buffer, WritePreference.HHH);

				} catch (TException e) {
					LOGGER.error("Error while writing data during recovery : " + e);
					e.printStackTrace();
					continue;
				} finally {
					transport.close();
				}
			}
			LOGGER.info("Successfully recovered microbatch : " + microBatchId
					+ " lost from edgeId: " + edgeId);
			LOGGER.info("Recovery for microbatchId : " + microBatchId + " belonging to EdgeId: " + edgeId
					+ " ends at " + System.currentTimeMillis());

			//remove the microbatch from the list of microbatches the edge has
			//fetch edge from MbIdLocation map and remove this microbatchId
			//Since the microbatchId is removed from the set, make sure to not
			//use iterator while adding this microbatchId as we are removing here
			//and adding in Checker (ConcurrentModificationException)
//			Set<String> set = handler.getFog().getEdgeMicrobatchMap().get(edgeId);
			Set<Long> set = handler.getFog().getEdgeMicrobatchMap().get(edgeId);
			set.remove(microBatchId);
			if (set.size() == 0) {
				LOGGER.info("All microbatches recovered for EdgeId: " + edgeId + " at " + System.currentTimeMillis());
			}
		}

	}

}
