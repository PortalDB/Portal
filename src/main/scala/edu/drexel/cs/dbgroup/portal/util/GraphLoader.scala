package edu.drexel.cs.dbgroup.portal.util

import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.types.{StructType,Metadata,StructField}
import org.apache.spark.sql.catalyst.expressions.{Attribute,AttributeReference}
import org.apache.spark.sql.types._
import org.apache.spark.sql.{Dataset,Row,DataFrame}
import org.apache.spark.rdd.RDD
import org.apache.spark.storage.StorageLevel
import org.apache.spark.graphx.VertexId
import org.apache.spark.HashPartitioner
import org.apache.spark.sql.functions._
import org.apache.spark.sql.catalyst.util.DateTimeUtils

import org.apache.hadoop.conf._
import org.apache.hadoop.fs._

import edu.drexel.cs.dbgroup.portal._
import edu.drexel.cs.dbgroup.portal.representations._
import java.time.LocalDate
import scala.util.matching.Regex
import scala.reflect._

object GraphLoader {

  //TODO: make a type for Node and type for Link(Edge) so that representations can
  //deal directly with Dataset[Node] and Dataset[Link] which will be
  //type-safer and easier to understand.
  //Issue: for the attribute, Node/Link has to be a template, but we don't know
  //its type until we load the schema from the dataset, by which point is too late

  def buildRG(url: String, vattrcol: Int, eattrcol: Int, bounds: Interval): RepresentativeGraph[Any, Any] = {
    //get the configuration option for snapshot groups
    val sg = System.getProperty("portal.partitions.sgroup", "")
    //make a filter. RG needs "spatial" layout, i.e. one sorted by time
    val filter = "_t_" + sg

    val (nodes, edges, deflt) = loadDataParquet(url, vattrcol, eattrcol, bounds, filter)
    val col = sg match {
      case "" => true
      case _ => false
    }

    RepresentativeGraph.fromDataFrames[Any,Any](nodes, edges, deflt, StorageLevel.MEMORY_ONLY_SER, col)
  }

  def buildOG(url: String, vattrcol: Int, eattrcol: Int, bounds: Interval): OneGraph[Any, Any] = {
    //get the configuration option for snapshot groups
    val sg = System.getProperty("portal.partitions.sgroup", "")
    //make a filter. OG needs "temporal" layout, i.e. one sorted by id
    val filter = "_t_" + sg

    val (nodes, edges, deflt) = loadDataParquet(url, vattrcol, eattrcol, bounds, filter)
    val col = sg match {
      case "" => true
      case _ => false
    }

    OneGraph.fromDataFrames[Any,Any](nodes, edges, deflt, StorageLevel.MEMORY_ONLY_SER, col)

  }

  def buildOGC(url: String, vattrcol: Int, eattrcol: Int, bounds: Interval): OneGraphColumn[Any, Any] = {
    //get the configuration option for snapshot groups
    val sg = System.getProperty("portal.partitions.sgroup", "")
    //make a filter. OG needs "temporal" layout, i.e. one sorted by id
    val filter = "_t_" + sg

    val (nodes, edges, deflt) = loadDataParquet(url, vattrcol, eattrcol, bounds, filter)
    val col = sg match {
      case "" => true
      case _ => false
    }

    OneGraphColumn.fromDataFrames[Any,Any](nodes, edges, deflt, StorageLevel.MEMORY_ONLY_SER, col)

  }

  def buildHG(url: String, vattrcol: Int, eattrcol: Int, bounds: Interval): HybridGraph[Any, Any] = {
    //get the configuration option for snapshot groups
    val sg = System.getProperty("portal.partitions.sgroup", "")
    //want one graph in HG per SG group

    //make a filter. HG needs "temporal" layout, i.e. one sorted by id
    val filter = "_t_" + sg
    //separate dataframe for each path
    val nodePaths = getPaths(url, bounds, "nodes" + filter)
    val nh = ProgramContext.getSession.read.parquet(nodePaths.head)
    //val nodeschema = nh.schema
    val nreader = ProgramContext.getSession.read.schema(nh.schema)
    var nodeDFs = nh +: nodePaths.tail.map(nf => nreader.parquet(nf))
    val edgePaths = getPaths(url, bounds, "edges" + filter)
    val eh = ProgramContext.getSession.read.parquet(edgePaths.head)
    //val edgeschema = eh.schema
    val ereader = ProgramContext.getSession.read.schema(eh.schema)
    var edgeDFs = eh +: edgePaths.tail.map(nf => ereader.parquet(nf))

    //select within bounds
    if (bounds.start != LocalDate.MIN || bounds.end != LocalDate.MAX) {
       val secs1 = math.floor(DateTimeUtils.daysToMillis(bounds.start.toEpochDay().toInt).toDouble / 1000L).toLong
       val secs2 = math.floor(DateTimeUtils.daysToMillis(bounds.end.toEpochDay().toInt).toDouble / 1000L).toLong
       nodeDFs = nodeDFs.zip(nodePaths).map{ case (nf,dp) => 
         if (bounds.contains(Interval.parse(dp.takeRight(21)))) nf else 
            nf.filter("NOT (estart >= " + secs2 + " OR eend <= " + secs1 + ")").withColumn("estart", greatest(nf("estart"), lit(secs1))).withColumn("eend", least(nf("eend"), lit(secs2)))
       }
       edgeDFs = edgeDFs.zip(edgePaths).map{ case (nf,dp) => 
          if (bounds.contains(Interval.parse(dp.takeRight(21)))) nf else
            nf.filter("NOT (estart >= " + secs2 + " OR eend <= " + secs1 + ")").withColumn("estart", greatest(nf("estart"), lit(secs1))).withColumn("eend", least(nf("eend"), lit(secs2)))
       }
    } 

    //the schema should be the same in each df
    val vattr = 2 + vattrcol
    if (nodeDFs.head.schema.fields.size <= vattr)
      throw new IllegalArgumentException("requested column index " + vattrcol + " which does not exist in the data")
    val eattr = 4 + eattrcol
    var ec = eattrcol
    if (edgeDFs.head.schema.fields.size <= eattr)
      ec = -1
      //throw new IllegalArgumentException("requested column index " + eattrcol + " which does not exist in the data")

    //if there are more fields in the schema, add the select statement
    if (vattrcol == -1) {
      if (nodeDFs.head.schema.fields.size > 3)
        nodeDFs = nodeDFs.map(nf => nf.select("vid", "estart", "eend"))
      nodeDFs = nodeDFs.map(nf => nf.withColumn("attr", lit(true)))
    }
    else if (nodeDFs.head.schema.fields.size > 4)
      nodeDFs = nodeDFs.map(nf => nf.select("vid", "estart", "eend", nf.schema.fields(vattr).name))
    if (ec == -1) {
      if (edgeDFs.head.schema.fields.size > 5)
        edgeDFs = edgeDFs.map(nf => nf.select("eid", "vid1", "vid2", "estart", "eend"))
      edgeDFs = edgeDFs.map(nf => nf.withColumn("attr", lit(true)))
    }
    else if (edgeDFs.head.schema.fields.size > 6)
      edgeDFs = edgeDFs.map(nf => nf.select("eid", "vid1", "vid2", "estart", "eend", nf.schema.fields(eattr).name))
    
    val col: Boolean = nodeDFs.size < 2
    val deflt: Any = if (vattrcol == -1) false else nodeDFs.head.schema.fields(vattr).dataType match {
      case StringType => ""
      case IntegerType => -1
      case LongType => -1L
      case DoubleType => -1.0
      case _ => null
    }
    HybridGraph.fromDataFrames[Any,Any](nodeDFs, edgeDFs, deflt, StorageLevel.MEMORY_ONLY_SER, col)

  }

  def buildVE(url: String, vattrcol: Int, eattrcol: Int, bounds: Interval): VEGraph[Any, Any] = {
    //get the configuration option for snapshot groups
    val sg = System.getProperty("portal.partitions.sgroup", "")
    val woptim: Boolean = System.getProperty("portal.vegraph.optim", "false").toBoolean
    //make a filter. VE needs "temporal" layout, i.e. one sorted by id
    val filter = "_t_" + sg

    val (nodes, edges, deflt) = loadDataParquet(url, vattrcol, eattrcol, bounds, filter)
    val col = sg match {
      case "" => true
      case _ => false
    }

    if (woptim)
      VEGraphOptim.fromDataFrames[Any,Any](nodes, edges, deflt, StorageLevel.MEMORY_ONLY_SER, col)
    else
      VEGraph.fromDataFrames[Any,Any](nodes, edges, deflt, StorageLevel.MEMORY_ONLY_SER, col)

  }

  private def loadDataParquet(url: String, vattrcol: Int, eattrcol: Int, bounds: Interval, filter: String): (DataFrame, DataFrame, Any) = {
    val nodesFiles = getPaths(url, bounds, "nodes" + filter)
    val edgesFiles = getPaths(url, bounds, "edges" + filter)

    var users = ProgramContext.getSession.read.parquet(nodesFiles:_*)
    var links = ProgramContext.getSession.read.parquet(edgesFiles:_*)

    //select within bounds
    if (bounds.start != LocalDate.MIN || bounds.end != LocalDate.MAX) {
      val secs1 = math.floor(DateTimeUtils.daysToMillis(bounds.start.toEpochDay().toInt).toDouble / 1000L).toLong
      val secs2 = math.floor(DateTimeUtils.daysToMillis(bounds.end.toEpochDay().toInt).toDouble / 1000L).toLong
      users = users.filter("NOT (estart >= " + secs2 + " OR eend <= " + secs1 + ")").withColumn("estart", greatest(users("estart"), lit(secs1))).withColumn("eend", least(users("eend"), lit(secs2)))
      links = links.filter("NOT (estart >= " + secs2 + " OR eend <= " + secs1 + ")").withColumn("estart", greatest(links("estart"), lit(secs1))).withColumn("eend", least(links("eend"), lit(secs2)))
    }

    val vattr = 2 + vattrcol
    if (users.schema.fields.size <= vattr)
      throw new IllegalArgumentException("requested column index " + vattrcol + " which does not exist in the data")
    var ec = eattrcol
    val eattr = 4 + eattrcol
    if (links.schema.fields.size <= eattr)
      ec = -1
      //throw new IllegalArgumentException("requested column index " + eattrcol + " which does not exist in the data")

    val deflt: Any = if (vattrcol == -1) false else users.schema.fields(vattr).dataType match {
      case StringType => ""
      case IntegerType => -1
      case LongType => -1L
      case DoubleType => -1.0
      case _ => null
    }

    //if there are more fields in the schema, add the select statement
    if (vattrcol == -1) {
      if (users.schema.fields.size > 3)
        users = users.select("vid", "estart", "eend")
      users = users.withColumn("attr", lit(true))
    }
    else if (users.schema.fields.size > 4)
      users = users.select("vid", "estart", "eend", users.schema.fields(vattr).name)
    if (ec == -1) {
      if (links.schema.fields.size > 5)
        links = links.select("eid", "vid1", "vid2", "estart", "eend")
      links = links.withColumn("attr", lit(true))
    }
    else if (links.schema.fields.size > 6)
      links = links.select("eid", "vid1", "vid2", "estart", "eend", links.schema.fields(eattr).name)

    (users, links, deflt)
  }

  /* 
   * Return all the directories within the source that contain 
   * snapshot groups intersecting with the interval in question
   * Assumes that the directory has the snapshot groups directly in it
   * and that each snapshot group is named with the interval it contains.
   */
  def getPaths(path: String, intv: Interval, filter: String): Array[String] = {
    //get a listing of directories from path
    val pt: Path = new Path(path)
    val conf: Configuration = new Configuration()
    if (System.getenv("HADOOP_CONF_DIR") != "") {
      conf.addResource(new Path(System.getenv("HADOOP_CONF_DIR") + "/core-site.xml"))
    }
    val filterP = if (filter.endsWith("_")) filter else filter + "_"
    val pathFilter = new PathFilter {
      def accept(p: Path): Boolean = {
        val pat = (filterP+"""\d""").r
        pat.findFirstIn(p.getName()).isDefined
      }
    }
    val status = FileSystem.get(conf).listStatus(pt, pathFilter)
    status.map(x => x.getPath()).filter(x => Interval.parse(x.getName().takeRight(21)).intersects(intv)).map(x => x.toString())
  }

  def getParquet(paths: Array[String], point: LocalDate): DataFrame = {
    val file = paths.filter(x => Interval.parse(x.takeRight(21)).contains(point))
    if (file.size > 0) {
      ProgramContext.getSession.read.parquet(file.head)
    } else { 
      ProgramContext.getSession.emptyDataFrame
    }
  }

  def getParquet(paths: Array[String], intv: Interval): DataFrame = {
    val file = paths.filter(x => Interval.parse(x.takeRight(21)).intersects(intv))
    if (file.size > 0) {
      ProgramContext.getSession.read.parquet(file:_*)
    } else ProgramContext.getSession.emptyDataFrame
  }

}
