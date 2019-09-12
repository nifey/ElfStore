package com.dreamlab.edgefs.misc.erasure;

import com.dreamlab.edgefs.model.NodeInfo;
import com.dreamlab.edgefs.thrift.FogService;
import com.dreamlab.edgefs.thrift.Metadata;
import com.dreamlab.edgefs.thrift.WritePreference;
import com.dreamlab.edgefs.thrift.WriteResponse;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.transport.TFramedTransport;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TTransport;
import org.apache.thrift.transport.TTransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.Map;
import java.util.List;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RSEncoder {

    private int N;
    private int K;
    private byte[] allBytes;
    private byte[][] shards;
    private int shardSize;
    final private int BYTES_IN_INT = 4;
    private Logger LOGGER = LoggerFactory.getLogger(RSEncoder.class);
    private ExecutorService executor = Executors.newFixedThreadPool(5);

    public RSEncoder(int N, int K, ByteBuffer dataBuffer){
        this.N = N;
        this.K = K;
        int fileSize = dataBuffer.remaining();
        LOGGER.info("Initialized with N="+N+" K="+K+" Filesize="+fileSize);
        int storedSize = fileSize + BYTES_IN_INT;
        this.shardSize = (storedSize + this.K - 1)/this.K;
        this.allBytes = new byte[shardSize * this.K];
        this.shards = new byte[this.N][this.shardSize];
        ByteBuffer.wrap(allBytes).putInt(fileSize);
        dataBuffer.get(allBytes, BYTES_IN_INT, fileSize);
    }

    public byte[] getShard(Short shardIndex){ return this.shards[shardIndex]; }

    public void encodeAndSend(Metadata metadata, Map<NodeInfo, Map<String, Integer>> selectedFogs){

        LOGGER.info("The selected fog information is "+ selectedFogs);
        LOGGER.info("Starting to encode data, startTime="+System.currentTimeMillis());

        for (int i = 0; i < this.K; i++) {
            System.arraycopy(allBytes, i * shardSize, shards[i], 0, shardSize);
        }

        ReedSolomon reedSolomon = ReedSolomon.create(this.K, this.N - this.K);
        reedSolomon.encodeParity(shards, 0, shardSize);

        LOGGER.info("Finished encoding data, endTime="+System.currentTimeMillis());

        LOGGER.info("Starting to send shards to other fogs, startTime=" + System.currentTimeMillis());
        //Send shards to fogs to be written
        Iterator<NodeInfo> fogIterator = selectedFogs.keySet().iterator();
        for(Short shardIndex=0; shardIndex < this.N;) {
            if(fogIterator.hasNext()) {
                NodeInfo nInfo = fogIterator.next();
                Map<String, Integer> allocMap = selectedFogs.get(nInfo);
                LOGGER.info("Evaluating fog "+nInfo);
                Map<WritePreference, List<Short>> shardPreferenceMap = new HashMap<WritePreference, List<Short>>();
                for(String s: allocMap.keySet()) {
                    LOGGER.info("Got String "+s);
                    WritePreference preference = WritePreference.HHL;
                    if(s.equals("HH")){
                        preference = WritePreference.HHH;
                    } else if(s.equals("HL")){
                        preference = WritePreference.HHL;
                    }
                    int count = allocMap.get(s);
                    for(int i=0;i<count; i++) {
                        if(shardPreferenceMap.containsKey(preference)){
                            shardPreferenceMap.get(preference).add(shardIndex);
                        } else {
                            List<Short> list = new ArrayList<Short>();
                            list.add(shardIndex);
                            shardPreferenceMap.put(preference, list);
                        }
                        shardIndex++;
                    }
                }
                executor.submit(new WriteToFogTask(nInfo, metadata, this ,shardPreferenceMap));
            }
        }
        executor.shutdown();
        while(!executor.isTerminated()){}

        LOGGER.info("Finished sending shards to other fogs, endTime=" + System.currentTimeMillis());
    }
}

class WriteToFogTask implements Runnable {

    private Logger LOGGER = LoggerFactory.getLogger(WriteToFogTask.class);

    private NodeInfo fogInfo;
    private Metadata metadata;
    private RSEncoder rse;
    private Map<WritePreference, List<Short>> shardPreferenceMap;
    public WriteToFogTask(NodeInfo fogInfo, Metadata metadata, RSEncoder rse, Map<WritePreference, List<Short>> shardPreferenceMap){
        this.fogInfo = fogInfo;
        this.metadata = metadata;
        this.rse = rse;
        this.shardPreferenceMap = shardPreferenceMap;
        LOGGER.info("Initialized with fogInfo "+fogInfo);
    }

    @Override
    public void run() {
        TTransport transport = new TFramedTransport(new TSocket(fogInfo.getNodeIP(), fogInfo.getPort()));
        try {
            transport.open();
        } catch (TTransportException e) {
            transport.close();
            LOGGER.error("Unable to contact fog for writing shard : " + e);
            e.printStackTrace();
            return;
        }
        TProtocol protocol = new TBinaryProtocol(transport);
        FogService.Client fogClient = new FogService.Client(protocol);
        try {
            for(WritePreference preference: shardPreferenceMap.keySet()){
                Iterator<Short> iter = shardPreferenceMap.get(preference).iterator();
                while(iter.hasNext()){
                    Short shardIndex = iter.next();
                    LOGGER.info("Sending shard "+shardIndex);
                    metadata.setShardIndex(shardIndex);
                    WriteResponse response =  fogClient.write(metadata, ByteBuffer.wrap(rse.getShard(shardIndex)), preference);
                    LOGGER.info("Response received from node "+fogInfo+ " is " + response.getStatus() + " rel "+response.getReliability());
                }
            }
        } catch (TException e) {
            LOGGER.error("Error while writing to fog "+ fogInfo +" : " + e);
            e.printStackTrace();
        } finally {
            transport.close();
        }
    }
}
