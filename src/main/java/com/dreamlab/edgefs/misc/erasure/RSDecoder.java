package com.dreamlab.edgefs.misc.erasure;

import com.dreamlab.edgefs.misc.Constants;
import com.dreamlab.edgefs.thrift.*;
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
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RSDecoder {

    private int N;
    private int K;
    private Long mbId;
    private byte[] dataBytes;
    private byte[][] shards;
    private boolean[] shardPresent;
    private int shardCount = 0;
    private int shardSize = 0;
    private int BYTES_IN_INT = 4;
    public String compFormat;
    public long uncompSize;
    private Metadata metadata = null;

    private Logger LOGGER = LoggerFactory.getLogger(RSDecoder.class);
    private ExecutorService executor = Executors.newFixedThreadPool(5);

    public RSDecoder(int N, int K, Long mbId, String compFormat, long uncompSize){
        this.N = N;
        this.K = K;
        this.mbId = mbId;
        this.shardSize = (short)uncompSize;
        this.shards = new byte[this.N][shardSize];
        this.shardPresent = new boolean [this.N];
        Arrays.fill(shardPresent, false);
        this.compFormat = compFormat;
        this.uncompSize = uncompSize;
        LOGGER.info("Initialized with N="+N+" K="+K);
    }

    public void setMetadata(Metadata metadata){
        if(this.metadata == null){
            this.metadata = metadata;
        }
    }

    public void setShard(Short shardIndex, byte[] shardData){
        System.arraycopy(shardData, 0, this.shards[shardIndex], 0, this.shardSize);
        this.shardPresent[shardIndex] = true;
        this.shardCount++;
    }

    public byte[] getDataBytes(){ return this.dataBytes; }

    public Metadata getMetadata(){ return this.metadata; }

    public boolean receiveAndDecode(List<FindReplica> shardsLocationList){
        LOGGER.info("ShardsLocationList "+ shardsLocationList);
        //Retrieve shards from edges
        Iterator<FindReplica> shardIterator = shardsLocationList.iterator();
        while(shardIterator.hasNext()){
            FindReplica shardLocation = shardIterator.next();
            executor.submit(new ReadFromEdgeTask(shardLocation.getEdgeInfo(), this.mbId, this));
        }
        executor.shutdown();
        while(!executor.isTerminated()){}

        //Check if K shards are retrieved
        if (this.shardCount < this.K) {
            LOGGER.info("Not enough shards present");
            return false;
        }

        boolean allDataShardsPresent = true;
        for(int i=0; i<this.K; i++){
            if(!shardPresent[i]){
                allDataShardsPresent = false;
                break;
            }
        }

        if(!allDataShardsPresent) {
            for (int i = 0; i < this.N; i++) {
                if (!this.shardPresent[i]) {
                    LOGGER.info("Shard "+i + " missing");
                    shards[i] = new byte [shardSize];
                }
            }
            LOGGER.info("Some data shards missing, recovering microbatch");
            LOGGER.info("Shardsize "+shardSize);
            ReedSolomon reedSolomon = ReedSolomon.create(this.K, this.N - this.K);
            reedSolomon.decodeMissing(shards, shardPresent, 0, shardSize);
        }

        // Combine the data shards into one buffer
        byte[] allBytes = new byte [shardSize * this.K];
        for (int i = 0; i < this.K; i++) {
            System.arraycopy(shards[i], 0, allBytes, shardSize * i, shardSize);
        }

        LOGGER.info("MicroBatchSize = " + ByteBuffer.wrap(allBytes).getInt());
        int microBatchSize = ByteBuffer.wrap(allBytes).getInt();
        this.dataBytes = new byte[microBatchSize];
        System.arraycopy(allBytes, BYTES_IN_INT, this.dataBytes, 0, microBatchSize);
        this.metadata.setUncompSize(microBatchSize);

        return true;
    }
}

class ReadFromEdgeTask implements Runnable {

    private Logger LOGGER = LoggerFactory.getLogger(ReadFromEdgeTask.class);

    private EdgeInfoData edgeInfo;
    private Long mbId;
    private RSDecoder rsd;
    public ReadFromEdgeTask(EdgeInfoData edgeInfo, Long mbId, RSDecoder rsd){
        this.edgeInfo = edgeInfo;
        this.mbId = mbId;
        this.rsd = rsd;
        LOGGER.info("Initialized with edgeInfo "+edgeInfo);
    }

    @Override
    public void run() {
        TTransport transport = new TFramedTransport(new TSocket(edgeInfo.getNodeIp(), edgeInfo.getPort()));
        try {
            transport.open();
        } catch (TTransportException e) {
            transport.close();
            LOGGER.error("Unable to contact edge for reading shard : " + e);
            e.printStackTrace();
            return;
        }
        TProtocol protocol = new TBinaryProtocol(transport);
        EdgeService.Client edgeClient = new EdgeService.Client(protocol);
        try {
                    LOGGER.info("Reading shard from edge "+ edgeInfo);
                    ReadReplica response =  edgeClient.read(mbId, (byte) 1, rsd.compFormat, rsd.uncompSize);
                    LOGGER.info("Response received from edge "+edgeInfo+ " is " + response.getStatus());
                    if(response.getStatus() == Constants.SUCCESS){
                        Metadata metadata = response.getMetadata();
                        rsd.setShard(metadata.getShardIndex(), response.getData());
                        rsd.setMetadata(metadata);
                        LOGGER.info("response.getdata.length "+response.getData().length);
                    }
        } catch (TException e) {
            LOGGER.error("Error while reading from edge "+ edgeInfo +" : " + e);
            e.printStackTrace();
        } finally {
            transport.close();
        }
    }
}