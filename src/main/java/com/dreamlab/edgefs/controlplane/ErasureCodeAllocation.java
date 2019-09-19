package com.dreamlab.edgefs.controlplane;

import com.dreamlab.edgefs.misc.Constants;
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
            case 0:
                LOGGER.info("Using scheme 0");
                return this.scheme0();
            case 1:
                LOGGER.info("Using scheme 1");
                return this.scheme1();
            case 2:
                LOGGER.info("Using scheme 2 with excessReliabilityLimit = "+ Constants.ERASURE_CODE_EXCESS_RELIABILITY_LIMIT);
                return this.scheme2(Constants.ERASURE_CODE_EXCESS_RELIABILITY_LIMIT);
        }
        return false;
    }

    //Random scheme
    private boolean scheme0(){
        Random random = new Random();
        List<Short> fogsList = new ArrayList<Short>(globalInfo.keySet());
        int fogListBound = fogsList.size();
        int outerIterateLimit = 20;
        while(!isFeasible && outerIterateLimit-->0) {
            //Create reliabilities array and calculate reliability until required selection is obtained
            this.selectedFogs = new HashMap<NodeInfo, Map<String, Integer>>();
            double[] reliabilities = new double[this.N];
            int[] fogAssigned = new int[this.N];

            //Pick N edge reliabilities at random
            Integer count = 0;
            Integer currentFogIndex;
            FogStats currentFogStats;
            Integer countSelected;

            boolean[] fogUsed = new boolean[this.N];
            Arrays.fill(fogUsed, false);

            double currentFogReliability;

            //If iterateLimit number of iterations complete we return false
            int iterateLimit = 20;
            while (count < this.N && (iterateLimit--)>0) {
                //Randomly select N reliabilities
                currentFogIndex = random.nextInt(fogListBound);
                currentFogStats = globalInfo.get(fogsList.get(currentFogIndex));
                if (currentFogStats.getMedianStorage() < this.shardSize) {
                    continue;
                }
                //We select an edge
                int reliability = random.nextInt(2);
                if (reliability == 0) {
                    //Choose an edge from HH if available
                    currentFogReliability = currentFogStats.getMedianReliability();
                    if (currentFogStats.getD() < 1 || fogUsed[currentFogIndex]) {
                        continue;
                    }
                    Map<String, Integer> allocMap = this.selectedFogs.get(currentFogStats.getNodeInfo());
                    if (allocMap == null) {
                        allocMap = new HashMap<String, Integer>();
                        this.selectedFogs.put(currentFogStats.getNodeInfo(), allocMap);
                    }
                    //check if the fog is used up
                    countSelected = 0;
                    for (String s : allocMap.keySet()) {
                        countSelected += allocMap.get(s);
                    }
                    if (countSelected < this.M) {
                        if(allocMap.containsKey("HH")) {
                            allocMap.put("HH", allocMap.get("HH") + 1);
                        } else {
                            allocMap.put("HH", 1);
                        }
                        reliabilities[count] = currentFogReliability / 100;
                        fogAssigned[count] = currentFogIndex;
                        count++;
                    }
                    if (countSelected + 1 == this.M) {
                        fogUsed[currentFogIndex] = true;
                    }
                } else {
                    //Choose an edge from HL if available
                    currentFogReliability = currentFogStats.getMinReliability();
                    if (currentFogStats.getB() < 1 || fogUsed[currentFogIndex]) {
                        continue;
                    }
                    Map<String, Integer> allocMap = this.selectedFogs.get(currentFogStats.getNodeInfo());
                    if (allocMap == null) {
                        allocMap = new HashMap<String, Integer>();
                        this.selectedFogs.put(currentFogStats.getNodeInfo(), allocMap);
                    }
                    //check if the fog is used up
                    countSelected = 0;
                    for (String s : allocMap.keySet()) {
                        countSelected += allocMap.get(s);
                    }
                    if (countSelected < this.M) {
                        if(allocMap.containsKey("HL")) {
                            allocMap.put("HL", allocMap.get("HL") + 1);
                        } else {
                            allocMap.put("HL", 1);
                        }
                        reliabilities[count] = currentFogReliability / 100;
                        fogAssigned[count] = currentFogIndex;
                        count++;
                    }
                    if (countSelected + 1 == this.M) {
                        fogUsed[currentFogIndex] = true;
                    }
                }
            }

            //Not enough fogs present
            if (count < this.N) {
                return false;
            }

            //Check reliability
            double currentReliability = this.calculateReliability(reliabilities);
            LOGGER.info(selectedFogs.toString());
            LOGGER.info("Calculated reliability: " + currentReliability);
            if (currentReliability >= this.requiredReliability) {
                this.setAchievedReliability(currentReliability);
                if(isFeasible){
                    return true;
                }
            }
        }
        return false;
    }

    //Least reliability first scheme
    private boolean scheme1(){
        //Sorting fogs based on median reliability
        List<Short> fogReliabilityOrder = new ArrayList<Short>(globalInfo.keySet());
        fogReliabilityOrder.sort((o1, o2) -> globalInfo.get(o1).getMedianReliability() - globalInfo.get(o2).getMedianReliability());

        //Create reliabilities array and calculate reliability until required selection is obtained
        double[] reliabilities = new double[this.N];
        int[] fogAssigned = new int[this.N];

        //Pick N least reliable edge reliabilities
        Integer count=0;
        Integer currentFogIndex = 0;
        FogStats currentFogStats;
        Integer countSelected;

        boolean[] fogUsed = new boolean[globalInfo.keySet().size()];
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

    //Greedy search scheme
    private boolean scheme2(double excessReliabilityLimit){
        //Sorting fogs based on median reliability
        List<Short> fogReliabilityOrder = new ArrayList<Short>(globalInfo.keySet());
        fogReliabilityOrder.sort((o1, o2) -> globalInfo.get(o1).getMedianReliability() - globalInfo.get(o2).getMedianReliability());

        Integer lowCount = (this.N%2==0)? this.N/2 : this.N/2 + 1;
        Integer highCount = this.N/2 ;

        //Create reliabilities array for high and low reliability selections
        double[] lowReliabilities = new double[this.N];
        int[] lowRFogAssigned = new int[this.N];

        Integer count=0;
        Integer currentLowFogIndex = 0;
        FogStats currentFogStats;
        Integer countSelected;

        boolean[] fogUsed = new boolean[globalInfo.keySet().size()];
        Arrays.fill(fogUsed, false);

        //Pick N/2 least reliable edge reliabilities
        double currentFogReliability;
        while(count<lowCount && currentLowFogIndex<fogReliabilityOrder.size()){
            currentFogStats = globalInfo.get(fogReliabilityOrder.get(currentLowFogIndex));
            if(currentFogStats.getMedianStorage()<this.shardSize){
                currentLowFogIndex++;
                continue;
            }
            //We select a maximum of N-K edges from HL of the low reliability fog
            countSelected = Math.min(Math.min(currentFogStats.getB(), this.M), lowCount - count);
            //We take min reliability value because the edges are chosen from HL
            currentFogReliability = currentFogStats.getMinReliability();
            HashMap<String, Integer> allocMap = new HashMap<String, Integer>();
            allocMap.put("HL", countSelected);
            this.selectedFogs.put(currentFogStats.getNodeInfo(), allocMap);
            for(; countSelected>0 ; countSelected--){
                lowReliabilities[count] = currentFogReliability/100;
                lowRFogAssigned[count] = currentLowFogIndex;
                count++;
            }

            //check if the fog is used up
            countSelected = 0;
            for(String s : allocMap.keySet()){
                countSelected += allocMap.get(s);
            }
            if(countSelected==this.M) {
                fogUsed[currentLowFogIndex] = true;
            }
            currentLowFogIndex++;
        }
        if(count<lowCount) {
            //If not enough low reliability edges found then select other high reliability ones
            highCount = highCount + (lowCount - count);
            lowCount = count;
        }

        //Pick N/2 high reliable edge reliabilities
        double[] highReliabilities = new double[this.N];
        int[] highRFogAssigned = new int[this.N];
        Integer currentHighFogIndex = fogReliabilityOrder.size() - 1;
        count = 0;
        while(count<highCount && currentHighFogIndex>=0 && !fogUsed[currentHighFogIndex]){
            currentFogStats = globalInfo.get(fogReliabilityOrder.get(currentHighFogIndex));
            if(currentFogStats.getMedianStorage()<this.shardSize){
                currentHighFogIndex--;
                continue;
            }
            //We select a maximum of N-K edges from HH of the high reliability fog
            countSelected = Math.min(Math.min(currentFogStats.getD(), this.M), highCount - count);
            //We take median reliability value because the edges are chosen from HH
            currentFogReliability = currentFogStats.getMedianReliability();
            Map<String, Integer> allocMap = selectedFogs.get(currentFogStats.getNodeInfo());
            if(allocMap == null){
                allocMap = new HashMap<String, Integer>();
                selectedFogs.put(currentFogStats.getNodeInfo(), allocMap);
            }
            allocMap.put("HH", countSelected);
            for(; countSelected>0 ; countSelected--){
                highReliabilities[count] = currentFogReliability/100;
                highRFogAssigned[count] = currentHighFogIndex;
                count++;
            }

            //check if the fog is used up
            countSelected = 0;
            for(String s : allocMap.keySet()){
                countSelected += allocMap.get(s);
            }
            if(countSelected==this.M) {
                fogUsed[currentHighFogIndex] = true;
            }
            currentHighFogIndex--;
        }
        if(count<highCount) {
            LOGGER.info("Not enough edges available to place N erasure coded blocks");
            return false;
        }

        //Check reliability and replace edge reliabilities if reliability is not met
        currentLowFogIndex = 0;
        currentHighFogIndex = fogReliabilityOrder.size() - 1;
        int iterateLimit = 20;
        Map<NodeInfo, Map<String, Integer>> satisfyingAllocation = null;
        double previousReliability = 1;
        double currentReliability;
        while(iterateLimit-->0 && !(this.getAchievedReliability() >= this.requiredReliability && this.getAchievedReliability() <= (this.requiredReliability + excessReliabilityLimit))) {
            double[] reliabilities = new double[this.N];
            System.arraycopy(lowReliabilities, 0, reliabilities, 0, lowCount);
            System.arraycopy(highReliabilities, 0, reliabilities, lowCount, highCount);

            String reli= new String();
            for(int i=0;i<reliabilities.length;i++){
                reli += reliabilities[i]+ ", ";
            }

            currentReliability = this.calculateReliability(reliabilities);
            this.setAchievedReliability(currentReliability);
            if(currentReliability == previousReliability){
                //To prevent oscillation of selection
                currentHighFogIndex--;
            }
            if(currentReliability < previousReliability && currentReliability >= this.requiredReliability){
                previousReliability = currentReliability;
                satisfyingAllocation = new HashMap<NodeInfo, Map<String, Integer>>();
                for(NodeInfo n: selectedFogs.keySet()) {
                    Map<String, Integer> allocMap = new HashMap<String, Integer>();
                    for(String s: selectedFogs.get(n).keySet()){
                        allocMap.put(s, selectedFogs.get(n).get(s).intValue());
                    }
                    satisfyingAllocation.put(n, allocMap);
                }
            }
            if(this.achievedReliability < this.requiredReliability){
                //Replace a low reliability edge with a high reliability edge
                while(currentHighFogIndex>=0){
                    if(!fogUsed[currentHighFogIndex]) {
                        if((globalInfo.get(fogReliabilityOrder.get(currentHighFogIndex)).getMedianStorage() >= this.shardSize)){
                            break;
                        }
                    }
                    currentHighFogIndex--;
                }
                //if all fogs are used return false
                if(currentHighFogIndex == -1){
                    break;
                }
                // else replace an edge reliability with a high reliabilty edge
                currentFogStats = globalInfo.get(fogReliabilityOrder.get(currentHighFogIndex));
                countSelected = 0;
                Map<String, Integer> allocMap = selectedFogs.get(currentFogStats.getNodeInfo());
                if(allocMap == null){
                    allocMap = new HashMap<String, Integer>();
                    selectedFogs.put(currentFogStats.getNodeInfo(), allocMap);
                } else {
                    for (String s : allocMap.keySet()) {
                        countSelected += allocMap.get(s);
                    }
                }
                if(countSelected<this.M && ((!allocMap.containsKey("HH") && currentFogStats.getD()>0) || (allocMap.containsKey("HH") && (currentFogStats.getD() - allocMap.get("HH"))>0))){
                    if(lowCount<=0){
                        break;
                    }
                    Integer previouslyAssignedFog = lowRFogAssigned[lowCount-1];
                    Map<String, Integer> previousAllocMap = selectedFogs.get(globalInfo.get(fogReliabilityOrder.get(previouslyAssignedFog)).getNodeInfo());
                    previousAllocMap.put("HL", previousAllocMap.get("HL") - 1);
                    fogUsed[previouslyAssignedFog] = false;

                    //We take median reliability value because the edges are chosen from HH
                    highReliabilities[highCount] = (double)currentFogStats.getMedianReliability()/100;
                    highRFogAssigned[highCount] = currentHighFogIndex;
                    highCount++;
                    if(countSelected + 1 >= this.M){
                        fogUsed[currentHighFogIndex] = true;
                    }

                    //We select an edge from HH of the high reliability fog
                    if(allocMap.containsKey("HH")) {
                        allocMap.put("HH", allocMap.get("HH") + 1);
                    } else {
                        allocMap.put("HH", 1);
                    }
                    lowCount--;
                } else {
                    currentHighFogIndex--;
                }
            } else if(this.achievedReliability > this.requiredReliability + excessReliabilityLimit){
                //Replace a high reliability edge with a low reliability edge
                while(currentLowFogIndex<fogReliabilityOrder.size()){
                    if(!fogUsed[currentLowFogIndex]) {
                        if((globalInfo.get(fogReliabilityOrder.get(currentLowFogIndex)).getMedianStorage() >= this.shardSize)){
                            break;
                        }
                    }
                    currentLowFogIndex++;
                }
                //if all fogs are used return false
                if(currentLowFogIndex == fogReliabilityOrder.size()){
                    break;
                }
                // else replace an edge reliability with a low reliabilty edge
                currentFogStats = globalInfo.get(fogReliabilityOrder.get(currentLowFogIndex));
                countSelected = 0;
                Map<String, Integer> allocMap = selectedFogs.get(currentFogStats.getNodeInfo());
                if(allocMap == null){
                    allocMap = new HashMap<String, Integer>();
                    selectedFogs.put(currentFogStats.getNodeInfo(), allocMap);
                } else {
                    for (String s : allocMap.keySet()) {
                        countSelected += allocMap.get(s);
                    }
                }
                if(countSelected<this.M && ((!allocMap.containsKey("HL") && currentFogStats.getB()>0) || (allocMap.containsKey("HL") && (currentFogStats.getB() - allocMap.get("HL"))>0))){
                    if(highCount<=0){
                        break;
                    }
                    Integer previouslyAssignedFog = highRFogAssigned[highCount-1];
                    Map<String, Integer> previousAllocMap = selectedFogs.get(globalInfo.get(fogReliabilityOrder.get(previouslyAssignedFog)).getNodeInfo());
                    previousAllocMap.put("HH", previousAllocMap.get("HH") - 1);
                    fogUsed[previouslyAssignedFog] = false;

                    //We take min reliability value because the edges are chosen from HL
                    lowReliabilities[lowCount] = (double)currentFogStats.getMinReliability()/100;
                    lowRFogAssigned[lowCount] = currentLowFogIndex;
                    lowCount++;
                    if(countSelected + 1 >= this.M){
                        fogUsed[currentLowFogIndex] = true;
                    }

                    //We select an edge from HL of the low reliability fog
                    if(allocMap.containsKey("HL")) {
                        allocMap.put("HL", allocMap.get("HL") + 1);
                    } else {
                        allocMap.put("HL", 1);
                    }
                    highCount--;
                } else {
                    currentLowFogIndex++;
                }
            }
        }

        if(this.getAchievedReliability() >= this.requiredReliability && this.getAchievedReliability() <= (this.requiredReliability + excessReliabilityLimit)){
            LOGGER.info("Achieved reliability: "+ this.achievedReliability);
            LOGGER.info("The selected fogs are:");
            for(NodeInfo n : selectedFogs.keySet()){
                LOGGER.info("Fog "+n.getNodeID()+" => "+selectedFogs.get(n).toString());
            }
            return true;
        } else if (satisfyingAllocation != null){
            this.setAchievedReliability(previousReliability);
            LOGGER.info("Achieved reliability: "+ this.achievedReliability);
            this.selectedFogs = satisfyingAllocation;
            LOGGER.info("The selected fogs are:");
            for(NodeInfo n : selectedFogs.keySet()){
                LOGGER.info("Fog "+n.getNodeID()+" => "+selectedFogs.get(n).toString());
            }
            return true;
        }
        LOGGER.info("Could not find a selection of fogs that satisfy the required reliabilty");
        return false;
    }

    public static void main(String[] args){
        //This main function is for debugging the selection schemes
        //creating dummy globalInfo
        Map<Short, FogStats> fogStatsMap = new HashMap<Short, FogStats>();
        FogStats fs1 = new FogStats(1112, 1122, 1145, 77, 85, 92, 4, 4, 4, 4);
        FogStats fs2 = new FogStats(1112, 1122, 1145, 55, 65, 72, 4, 4, 4, 2);
        FogStats fs3 = new FogStats(1112, 1122, 1145, 87, 95, 98, 4, 4, 4, 4);
        FogStats fs4 = new FogStats(1112, 1122, 1145, 67, 83, 90, 4, 4, 4, 1);
        FogStats fs5 = new FogStats(1112, 1122, 1145, 37, 53, 72, 4, 4, 4, 4);
        NodeInfo n1 = new NodeInfo("127.0.0.1", (short)1, 1234);
        NodeInfo n2 = new NodeInfo("127.0.0.1", (short)2, 1234);
        NodeInfo n3 = new NodeInfo("127.0.0.1", (short)3, 1234);
        NodeInfo n4 = new NodeInfo("127.0.0.1", (short)4, 1234);
        NodeInfo n5 = new NodeInfo("127.0.0.1", (short)5, 1234);
        fs1.setNodeInfo(n1);
        fs2.setNodeInfo(n2);
        fs3.setNodeInfo(n3);
        fs4.setNodeInfo(n4);
        fs5.setNodeInfo(n5);
        Short id1 = 1;
        Short id2 = 2;
        Short id3 = 3;
        Short id4 = 4;
        Short id5 = 5;
        fogStatsMap.put(id1, fs1);
        fogStatsMap.put(id2, fs2);
        fogStatsMap.put(id3, fs3);
        fogStatsMap.put(id4, fs4);
        fogStatsMap.put(id5, fs5);
        ErasureCodeAllocation eca = new ErasureCodeAllocation(9, 6, 0.77, 123 ,fogStatsMap, 2);
        LOGGER.info("result = "+eca.selectFogsForPlacement());
        LOGGER.info(eca.getSelectedFogs().toString());
        LOGGER.info(eca.getAchievedReliability()+"");
    }
}
