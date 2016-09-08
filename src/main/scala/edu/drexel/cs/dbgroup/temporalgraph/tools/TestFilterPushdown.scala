package edu.drexel.cs.dbgroup.temporalgraph.tools

import java.sql.Date

import _root_.edu.drexel.cs.dbgroup.temporalgraph.ProgramContext
import org.apache.log4j.{Level, Logger}
import org.apache.spark.sql.{DataFrame, SQLContext}
import org.apache.spark.{SparkContext, SparkConf}

/**
  * Created by shishir on 8/8/2016.
  */
object TestFilterPushdown {
  //note: this does not remove ALL logging
  Logger.getLogger("org").setLevel(Level.OFF)
  Logger.getLogger("akka").setLevel(Level.OFF)
  var conf = new SparkConf().setAppName("TemporalGraph Project").setSparkHome(System.getenv("SPARK_HOME"))

  conf.set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
  conf.set("spark.network.timeout", "240")
  //conf.set("spark.shuffle.spill.numElementsForceSpillThreshold", "500000")
  val sc = new SparkContext(conf)
  ProgramContext.setContext(sc)
  val sqlContext = ProgramContext.getSession
  sqlContext.conf.set("spark.sql.files.maxPartitionBytes", "16777216")
  import sqlContext.implicits._

  def main(args: Array[String]): Unit ={
    run("C:/Users/shishir/temporaldata/dblp/edges.parquet", "1952-01-01", "2013-01-01")
    runVid("hdfs://master:9000/data/twitter/edges.parquet", 10000, 1000000)
  }

  def runVid(source:String, dataWithFewCounts:Long, dateWithManyCounts:Long): Unit ={
    val data = sqlContext.read.parquet(source)
    var start, end, timeTaken: Long = 0
    data.registerTempTable("tempTable")
    //executing test predicate to make sure the tempTable is registered
    var output = sqlContext.sql("Select * from tempTable")
    println(output.count())

    start = System.currentTimeMillis()
    var sqlQuery = "Select vid1 from tempTable where vid1<" +  dataWithFewCounts
    output = sqlContext.sql(sqlQuery)
    println(output.count())
    end = System.currentTimeMillis()
    timeTaken =  end - start
    println("Time taken for one Predicate with few counts " + timeTaken/1000 + "s")

    //to test if number of counts affect performance keeping number of predicate constant, it looks like it doesnt
    start = System.currentTimeMillis()
    sqlQuery = "Select vid1 from tempTable where vid1<" +  dateWithManyCounts
    output = sqlContext.sql(sqlQuery)
    println(output.count())
    end = System.currentTimeMillis()
    timeTaken =  end - start
    println("Time taken for one Predicate with large counts " + timeTaken/1000 + "s")
  }

  def run(source:String, dataWithFewCounts:String, dateWithManyCounts:String): Unit ={
    val data = sqlContext.read.parquet(source)
    var start, end, timeTaken: Long = 0
    data.registerTempTable("tempTable")
    val output = sqlContext.sql("Select * from tempTable")
    println(output.count())
    //executing test predicate to make sure the tempTable is registered
//    executeSQL()

    start = System.currentTimeMillis()
    executePredicate(dataWithFewCounts)
    end = System.currentTimeMillis()
    timeTaken =  end - start
    println("Time taken for one Predicate with few counts " + timeTaken/1000 + "s")

    //to test if number of counts affect performance keeping number of predicate constant, it looks like it doesnt
    start = System.currentTimeMillis()
    executePredicate(dateWithManyCounts)
    end = System.currentTimeMillis()
    timeTaken =  end - start
    println("Time taken for one Predicate with large counts " + timeTaken/1000 + "s")
  }

  def executeSQL(): Unit ={
    val output = sqlContext.sql("Select * from tempTable")
    println(output.count())
  }

  def executePredicate(date:String):Unit={
    val sqlQuery = "Select estart from tempTable where estart='" + date + "'"
    val output = sqlContext.sql(sqlQuery)
    println(output.count())
  }
}
