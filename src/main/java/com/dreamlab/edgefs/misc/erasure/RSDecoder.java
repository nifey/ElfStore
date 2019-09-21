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

    public RSDecoder(int N, int K, Long mbId, String compFormat, long uncompSize){
        this.N = N;
        this.K = K;
        this.mbId = mbId;
        this.shardSize = (int) uncompSize;
        this.shards = new byte[this.N][shardSize];
        this.shardPresent = new boolean [this.N];
        Arrays.fill(shardPresent, false);
        this.compFormat = compFormat;
        this.uncompSize = uncompSize;
        LOGGER.info("Initialized with N="+N+" K="+K);
    }

    public void setMetadata(Metadata metadata){
        if(this.metadata == null){
            this.metadata = metadata.deepCopy();
        }
    }

    public void setShard(Short shardIndex, byte[] shardData){
        System.arraycopy(shardData, 0, this.shards[shardIndex], 0, this.shardSize);
        this.shardPresent[shardIndex] = true;
        this.shardCount++;
    }

    public int getShardSize(){ return this.shardSize;}

    public byte[] getDataBytes(){ return this.dataBytes; }

    public byte[] getShard(Short shardIndex){ return this.shards[shardIndex];}

    //This returns metadata for the whole microbatch i.e. it has uncompsize of the whole microbatch
    public Metadata getMetadata(){ return this.metadata; }

    //This returns metadata for  a shard i.e. it has shardIndex set and has uncompsize equal to shard size
    public Metadata getMetadata(Short shardIndex){
        Metadata newMetadata = this.metadata.deepCopy();
        newMetadata.setShardIndex(shardIndex);
        newMetadata.setUncompSize(shardSize);
        return newMetadata;
    }

    public boolean receiveAndDecode(List<FindReplica> shardsLocationList){
        LOGGER.info("ShardsLocationList "+ shardsLocationList);

        ExecutorService dataExecutor = Executors.newFixedThreadPool(8);
        //Retrieve data shards from fogs
        Iterator<FindReplica> shardIterator = shardsLocationList.iterator();
        while(shardIterator.hasNext()){
            FindReplica shardLocation = shardIterator.next();
            dataExecutor.submit(new ReadFromEdgeTask(shardLocation.getNode(), this.mbId, this, true));
        }
        dataExecutor.shutdown();
        while(!dataExecutor.isTerminated()){}

        if(this.shardCount<this.K) {
            ExecutorService parityExecutor = Executors.newFixedThreadPool(3);
            //Retrieve parity shards from fogs
            Iterator<FindReplica> parityIterator = shardsLocationList.iterator();
            while(parityIterator.hasNext()){
                FindReplica shardLocation = parityIterator.next();
                parityExecutor.submit(new ReadFromEdgeTask(shardLocation.getNode(), this.mbId, this, false));
            }
            parityExecutor.shutdown();
            while(!parityExecutor.isTerminated()){}

            for (int i = 0; i < this.N; i++) {
                if (!this.shardPresent[i]) {
                    LOGGER.info("Shard "+i + " missing");
                    shards[i] = new byte [shardSize];
                }
            }
            //Check if K shards are retrieved
            if (this.shardCount < this.K) {
                LOGGER.info("Not enough shards present");
                return false;
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

    private NodeInfoData fogInfo;
    private Long mbId;
    private RSDecoder rsd;
    private boolean readData;
    public ReadFromEdgeTask(NodeInfoData fogInfo, Long mbId, RSDecoder rsd, boolean readData){
        this.fogInfo = fogInfo;
        this.mbId = mbId;
        this.rsd = rsd;
        this.readData = readData;
        LOGGER.info("Initialized with fogInfo "+fogInfo +" readData="+readData);
    }

    @Override
    public void run() {
        TTransport transport = new TFramedTransport(new TSocket(fogInfo.getNodeIP(), fogInfo.getPort()));
        try {
            transport.open();
        } catch (TTransportException e) {
            transport.close();
            LOGGER.error("Unable to contact fog for reading shards : " + e);
            e.printStackTrace();
            return;
        }
        TProtocol protocol = new TBinaryProtocol(transport);
        FogService.Client fogClient = new FogService.Client(protocol);
        try {
            List<ReadReplica> shardList;
            if(this.readData) {
                LOGGER.info("Reading data shards from fog "+ fogInfo);
                shardList = fogClient.getDataShards(mbId, rsd.compFormat, rsd.uncompSize);
            } else {
                LOGGER.info("Reading parity shards from fog "+ fogInfo);
                shardList = fogClient.getParityShards(mbId, rsd.compFormat, rsd.uncompSize);
            }
            Iterator<ReadReplica> iter = shardList.iterator();
            while(iter.hasNext()) {
                ReadReplica response = iter.next();
                if (response.getStatus() == Constants.SUCCESS) {
                    Metadata metadata = response.getMetadata();
                    rsd.setShard(metadata.getShardIndex(), response.getData());
                    rsd.setMetadata(metadata);
                }
            }
        } catch (TException e) {
            LOGGER.error("Error while reading from fog "+ fogInfo +" : " + e);
            e.printStackTrace();
        } finally {
            transport.close();
        }
    }
}