package com.dreamlab.edgefs.controlplane;

import com.dreamlab.edgefs.model.EdgeInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;

class EdgeStat {
    public int nodeId;
    public double reliability;
    public EdgeStat(int nodeId, double reliability){
        this.nodeId = nodeId;
        this.reliability = reliability;
    }
}

public class ErasureCodeAllocation {

    private static final Logger LOGGER = LoggerFactory.getLogger(ErasureCodeAllocation.class);

    //global info : Map<Short, FogStats> globalInfo

    private double achievedReliability = 0;
    private int n;
    private int m;
    private double requiredReliability;
    private ArrayList<EdgeStat> edgesStat = new ArrayList<EdgeStat>();
    private double calculationMatrix[][];

    public ErasureCodeAllocation(int n, int k, double requiredReliability, ArrayList<EdgeStat> edgesStat){
        this.n = n;
        this.m = n-k;
        this.requiredReliability = requiredReliability;
        this.edgesStat = edgesStat;
        //sort edgeStat list
        this.calculateAchievedReliability();
    }

    private void printCalculationMatrix(){
        for(int i=0;i<=this.n;i++){
            for(int j=0; j<=this.m; j++){
                System.out.print(calculationMatrix[i][j] + " ");
            }
            System.out.println();
        }
    }

    private void calculateAchievedReliability(){
        calculationMatrix = new double[this.n+1][this.m+1];
        calculationMatrix[0][0]= 1;
        for(int i=1; i <= n; i++){
            calculationMatrix[i][0] = calculationMatrix[i-1][0]*(edgesStat.get(i-1).reliability);
        }
        for(int j=1; j <= m; j++){
            for(int i=0; i<= n; i++){
                if(j>i){
                    calculationMatrix[i][j]=0;
                } else {
                    calculationMatrix[i][j] = calculationMatrix[i-1][j]*(edgesStat.get(i-1).reliability) + calculationMatrix[i-1][j-1]*(1-edgesStat.get(i-1).reliability);
                }
            }
        }
        this.printCalculationMatrix();
        LOGGER.info("The achieved reliability is "+ this.getAchievedReliability());
        System.out.println("The required reliability is "+ this.requiredReliability);
    }

    private double getAchievedReliability(){
        double sum =0;
        for(int j=0;j<=m;j++){
            sum+=calculationMatrix[this.n][j];
        }
        return sum;
    }

    public static void main(String[] args){
        //creating dummy nodes
        double reliabilities[] = {0.5,0.9,0.9,0.9,0.9,0.8,0.5,0.5,0.5};
        //double storages[] = {85, 124, 58, 78, 126};
        ArrayList<EdgeStat> edgesInfo = new ArrayList<EdgeStat>();
        for(int i=0; i<reliabilities.length;i++){
            edgesInfo.add(new EdgeStat(i,  reliabilities[i]));
        }
        ErasureCodeAllocation eca = new ErasureCodeAllocation(9, 6, 0.75, edgesInfo);
    }
}
