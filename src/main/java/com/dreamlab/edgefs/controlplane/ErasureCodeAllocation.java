package com.dreamlab.edgefs.controlplane;

import com.dreamlab.edgefs.model.FogStats;
import com.dreamlab.edgefs.model.NodeInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.DecimalFormat;
import java.util.*;

public class ErasureCodeAllocation {

    private static final Logger LOGGER = LoggerFactory.getLogger(ErasureCodeAllocation.class);

    private int N;
    private int M;
    private long shardSize;
    private double achievedReliability = 0;
    private double requiredReliability;
    private Map<Short, FogStats> globalInfo;
    private boolean isFeasible = false;
    private int scheme;

    //For each corresponding fog we return the number of edges to allocate as a map with HH or HL as the key and the number of edges assigned in that quadrant as the value
    private Map<NodeInfo, Map<String, Integer>> selectedFogs;

    public ErasureCodeAllocation(int n, int k, double requiredReliability, long length, Map<Short, FogStats> globalInfo, int scheme){
        this.N = n;
        this.M = n-k;
        this.requiredReliability = requiredReliability;
        this.shardSize = length/k;
        this.globalInfo = globalInfo;
        this.scheme = scheme;
        this.selectedFogs = new HashMap<NodeInfo, Map<String, Integer>>();
        LOGGER.info("Initialized with N ="+n+" K ="+k+" required reliability="+requiredReliability+ " scheme="+scheme+ " shardSize="+shardSize+ " globalInfo="+globalInfo);
    }

    private void printCalculationMatrix(double[][] calculationMatrix){
        DecimalFormat df = new DecimalFormat("#0.000");
        for(int i=0;i<=this.N;i++){
            for(int j=0; j<=this.M; j++){
                System.out.print(df.format(calculationMatrix[i][j]) + " ");
            }
            System.out.println();
        }
    }

    private double calculateReliability(double[] reliabilities){
        if(reliabilities.length < this.N){
            LOGGER.info("Length of reliabilities array is less than N");
            return -1;
        }
        double[][] calculationMatrix = new double[this.N+1][this.M+1];
        calculationMatrix[0][0]= 1;
        for(int i=1; i <= N; i++){
            calculationMatrix[i][0] = calculationMatrix[i-1][0]*(reliabilities[i-1]);
        }
        for(int j=1; j <= M; j++){
            for(int i=0; i<= N; i++){
                if(j>i){
                    calculationMatrix[i][j]=0;
                } else {
                    calculationMatrix[i][j] = calculationMatrix[i-1][j]*(reliabilities[i-1]) + calculationMatrix[i-1][j-1]*(1-reliabilities[i-1]);
                }
            }
        }
        double sum =0;
        for(int j=0;j<=this.M;j++){
            sum+=calculationMatrix[this.N][j];
        }
        //this.printCalculationMatrix(calculationMatrix);
        return sum;
    }

    public Map<NodeInfo, Map<String, Integer>> getSelectedFogs(){return this.selectedFogs;}

    public double getAchievedReliability(){return this.achievedReliability;}

    private void setAchievedReliability(double reliability){
        this.achievedReliability = reliability;
        isFeasible = this.achievedReliability >= this.requiredReliability;
    }

    // This function returns false if no subsets of reliabilities satisfy the reliability constraint
    public boolean selectFogsForPlacement(){
        switch(this.scheme){
            case 1:
                LOGGER.info("Using scheme "+scheme);
                return this.scheme1();
        }
        return false;
    }

    private boolean scheme1(){
        //Sorting fogs based on median reliability
        List<Short> fogReliabilityOrder = new ArrayList<Short>(globalInfo.keySet());
        fogReliabilityOrder.sort((o1, o2) -> globalInfo.get(o1).getMedianReliability() - globalInfo.get(o2).getMedianReliability());

        //Create reliabilities array and calculate reliability untill required selection is obtained
        double[] reliabilities = new double[this.N];
        int[] fogAssigned = new int[this.N];

        //Pick N least reliable edge reliabilities
        Integer count=0;
        Integer currentFogIndex = 0;
        FogStats currentFogStats;
        Integer countSelected;

        boolean[] fogUsed = new boolean[this.N];
        Arrays.fill(fogUsed, false);

        double currentFogReliability;
        while(count<this.N && currentFogIndex<fogReliabilityOrder.size()){
            currentFogStats = globalInfo.get(fogReliabilityOrder.get(currentFogIndex));
            if(currentFogStats.getMedianStorage()<this.shardSize){
                currentFogIndex++;
                continue;
            }
            //We select a maximum of N-K edges from HH of the low reliability fog
            countSelected = Math.min(currentFogStats.getD(), this.M);
            //We take median reliability value because the edges are chosen from HH
            currentFogReliability = currentFogStats.getMedianReliability();
            HashMap<String, Integer> allocMap = new HashMap<String, Integer>();
            allocMap.put("HH", countSelected);
            this.selectedFogs.put(currentFogStats.getNodeInfo(), allocMap);
            for(; countSelected>0 && count<this.N ; countSelected--){
                reliabilities[count] = currentFogReliability/100;
                fogAssigned[count] = currentFogIndex;
                count++;
            }
            if(countSelected!=0) {
                allocMap.put("HH", allocMap.get("HH") - countSelected);
                this.selectedFogs.put(currentFogStats.getNodeInfo(), allocMap);
            }

            //check if the fog is used up
            countSelected = 0;
            for(String s : allocMap.keySet()){
                countSelected += allocMap.get(s);
            }
            if(countSelected==this.M) {
                fogUsed[currentFogIndex] = true;
            }
            currentFogIndex++;
        }
        if(count<this.N){
            //If not enough edges are present try allocating edges from HL
            currentFogIndex = 0;
            while(count<this.N && currentFogIndex<fogReliabilityOrder.size()){
                currentFogStats = globalInfo.get(fogReliabilityOrder.get(currentFogIndex));
                if(currentFogStats.getMedianStorage()<this.shardSize){
                    currentFogIndex++;
                    continue;
                }
                //Finding how many edges are already assigned
                countSelected = 0;
                Map<String, Integer> allocMap = selectedFogs.get(currentFogStats.getNodeInfo());
                if(allocMap == null){
                    allocMap = new HashMap<String, Integer>();
                } else {
                    for (String s : allocMap.keySet()) {
                        countSelected += allocMap.get(s);
                    }
                }
                countSelected = Math.min(currentFogStats.getB(), this.M - countSelected);
                //We take min reliability value because the edges are chosen from HL
                currentFogReliability = currentFogStats.getMinReliability();
                allocMap.put("HL", allocMap.getOrDefault("HL", 0) + countSelected);
                this.selectedFogs.put(currentFogStats.getNodeInfo(), allocMap);
                for(; countSelected>0 && count<this.N ; countSelected--){
                    reliabilities[count] = currentFogReliability/100;
                    fogAssigned[count] = currentFogIndex;
                    count++;
                }
                if(countSelected!=0) {
                    allocMap.put("HL", allocMap.get("HL") - countSelected);
                    this.selectedFogs.put(currentFogStats.getNodeInfo(), allocMap);
                }
                //check if the fog is used up
                countSelected = 0;
                for(String s : allocMap.keySet()){
                    countSelected += allocMap.get(s);
                }
                if(countSelected==this.M) {
                    fogUsed[currentFogIndex] = true;
                }
                currentFogIndex++;
            }

            if(count<this.N) {
                LOGGER.info("Not enough edges available to place N erasure coded blocks");
                return false;
            }
        }

        //Check reliability and replace edge reliabilities if reliability is not met
        double currentReliability;
        //Now we use currentFogIndex variable to index in reverse starting from the most reliable fogs
        currentFogIndex = fogReliabilityOrder.size() - 1;
        Integer removeIndex = this.N-1;
        while(!isFeasible) {
            currentReliability = this.calculateReliability(reliabilities);
            LOGGER.info(selectedFogs.toString());
            LOGGER.info("Calculated reliability: "+ currentReliability);
            if(currentReliability >= this.requiredReliability){
                this.setAchievedReliability(currentReliability);
            }
            if(!isFeasible){
                while(currentFogIndex>=0){
                    if(!fogUsed[currentFogIndex]) {
                        if((globalInfo.get(fogReliabilityOrder.get(currentFogIndex)).getMedianStorage() >= this.shardSize)){
                            break;
                        }
                    }
                    currentFogIndex--;
                }
                //if all fogs are used return false
                if(currentFogIndex == -1){
                    return false;
                }
                // else replace an edge reliability with a high reliabilty edge
                currentFogStats = globalInfo.get(fogReliabilityOrder.get(currentFogIndex));
                countSelected = 0;
                Map<String, Integer> allocMap = selectedFogs.get(currentFogStats.getNodeInfo());
                if(allocMap == null){
                    allocMap = new HashMap<String, Integer>();
                } else {
                    for (String s : allocMap.keySet()) {
                        countSelected += allocMap.get(s);
                    }
                }
                if(countSelected<this.M && ((!allocMap.containsKey("HL") && currentFogStats.getB()>0) || (allocMap.containsKey("HL") && (currentFogStats.getB() - allocMap.get("HL"))>0))){
                    if(removeIndex<0){
                        return false;
                    }
                    Integer previouslyAssignedFog = fogAssigned[removeIndex];
                    Map<String, Integer> previousAllocMap = selectedFogs.get(globalInfo.get(fogReliabilityOrder.get(previouslyAssignedFog)).getNodeInfo());
                    previousAllocMap.put("HH", previousAllocMap.get("HH") - 1);
                    selectedFogs.put(globalInfo.get(fogReliabilityOrder.get(previouslyAssignedFog)).getNodeInfo(), previousAllocMap);
                    fogUsed[previouslyAssignedFog] = false;

                    //We take min reliability value because the edges are chosen from HL
                    reliabilities[removeIndex] = (double)currentFogStats.getMinReliability()/100;
                    fogAssigned[removeIndex] = currentFogIndex;
                    if(countSelected + 1 >= this.M){
                        fogUsed[currentFogIndex] = true;
                    }

                    //We select an edge from HL of the high reliability fog
                    if(allocMap.containsKey("HL")) {
                        allocMap.put("HL", allocMap.get("HL") + 1);
                    } else {
                        allocMap.put("HL", 1);
                    }
                    selectedFogs.put(currentFogStats.getNodeInfo(), allocMap);
                    removeIndex--;
                } else {
                    currentFogIndex--;
                }
            }
        }
        return true;
    }

}
