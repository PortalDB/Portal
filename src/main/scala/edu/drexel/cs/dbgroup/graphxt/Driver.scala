package edu.drexel.cs.dbgroup.graphxt

import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark.graphx.Graph
import scala.util.control._
import scala.collection.mutable.ArrayBuffer
import org.apache.log4j.Logger 
import org.apache.log4j.Level 

object Driver {
  def main(args: Array[String]) = {

  //note: this does not remove ALL logging  
    Logger.getLogger("org").setLevel(Level.OFF) 
    Logger.getLogger("akka").setLevel(Level.OFF) 

    var graphType: String = ""
    var strategy: String = ""
    var iterations: Int = 1
    var data = ""
    var partitionType:PartitionStrategyType.Value = PartitionStrategyType.None

    for(i <- 0 until args.length){
      if(args(i) == "--type"){
	graphType = args(i+1)
      }else if(args(i) == "--strategy"){
	strategy = args(i+1)
      }else if(args(i) == "--iterations"){
	iterations = args(i+1).toInt
      }else if(args(i) == "--data"){
	data = args(i+1)
      }else if (args(i) == "--partition"){
        partitionType = PartitionStrategyType.withName(args(i+1))
      }
    }
	
    val sc = new SparkContext("local", "SnapshotGraph Project",
//    val sc = new SparkContext("spark://ec2-54-234-129-137.compute-1.amazonaws.com:7077", "SnapshotGraph Project",
      System.getenv("SPARK_HOME"),
      List("target/scala-2.10/snapshot-graph-project_2.10-1.0.jar"))

    var result:SnapshotGraph[String,Int] = SnapshotGraph.loadData(data, sc).partitionBy(partitionType)
    var result2:SnapshotGraph[Double,Double] = null
    var changedType = false

    val startAsMili = System.currentTimeMillis()

    var  nextArg = 0
    for(i <- 0 until args.length){
      if(args(i) == "--agg"){
        var sem = AggregateSemantics.Existential
        nextArg = i+2
        if (args(i+2) == "universal")
          sem = AggregateSemantics.Universal
        if (changedType)
	  result2 = result2.aggregate(args(i+1).toInt, sem)
        else
	  result = result.aggregate(args(i+1).toInt, sem)
      }else if(args(i) == "--select"){
        nextArg = i+3
        if (changedType)
	  result2 = result2.select(Interval(args(i+1).toInt, args(i+2).toInt))
        else
          result = result.select(Interval(args(i+1).toInt, args(i+2).toInt))
      }else if(args(i) == "--pagerank"){
        nextArg = i+1
        if (changedType)
	  result2 = result2.pageRank(0.0001)
        else {
	  result2 = result.pageRank(0.0001)
          changedType = true
        }
      }
      //if this is not the last part of the query, repartition
      if (nextArg < args.length) {
        if (changedType)
          result2 = result2.partitionBy(partitionType)
        else
          result = result.partitionBy(partitionType)
      }
    }

    val endAsMili = System.currentTimeMillis()
    val runTime = endAsMili.toInt - startAsMili.toInt
    println("Final Runtime: " + runTime)

  }
}
