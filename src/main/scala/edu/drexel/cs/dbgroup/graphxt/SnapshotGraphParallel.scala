//For one graph per year (spanshots):
package edu.drexel.cs.dbgroup.graphxt

import scala.collection.immutable.SortedMap
import scala.collection.immutable.TreeMap
import scala.collection.parallel.ParSeq
import scala.reflect.ClassTag
import scala.util.control._

import org.apache.hadoop.conf._
import org.apache.hadoop.fs._

import org.apache.spark.SparkContext
import org.apache.spark.Partition

import org.apache.spark.graphx._
import org.apache.spark.graphx.Graph
import org.apache.spark.graphx.GraphLoaderAddon
import org.apache.spark.rdd._
import org.apache.spark.rdd.EmptyRDD
import org.apache.spark.rdd.PairRDDFunctions
import org.apache.spark.storage.RDDBlockId
import org.apache.spark.storage.StorageLevel
import org.apache.spark.storage.StorageLevel._


class SnapshotGraphParallel[VD: ClassTag, ED: ClassTag](sp: Interval, invs: SortedMap[Interval, Int], gps: ParSeq[Graph[VD, ED]]) extends TemporalGraph[VD, ED] with Serializable {
  var span = sp
  var graphs: ParSeq[Graph[VD, ED]] = gps //ParSeq[Graph[VD,ED]]()
  var intervals: SortedMap[Interval, Int] = invs //TreeMap[Interval,Int]()

  //constructor overloading
  def this(sp: Interval) = {
    this(sp, TreeMap[Interval, Int](), ParSeq[Graph[VD, ED]]())
  }

  def size(): Int = { graphs.size }

  def persist(newLevel: StorageLevel = MEMORY_ONLY): SnapshotGraphParallel[VD, ED] = {
    //persist each graph
    graphs.map(_.persist(newLevel))
    this
  }

  def unpersist(blocking: Boolean = true): SnapshotGraphParallel[VD, ED] = {
    graphs.map(_.unpersist(blocking))
    this
  }

  def numEdges(): Long = {
    graphs.filterNot(_.edges.isEmpty).map(_.numEdges).reduce(_ + _)
  }

  def numPartitions(): Int = {
    graphs.filterNot(_.edges.isEmpty).map(_.edges.partitions.size).reduce(_ + _)
  }

  def vertices: VertexRDD[VD] = {
    graphs.filterNot(_.vertices.isEmpty).map(_.vertices).reduce((a, b) => VertexRDD(a.union(b)))
  }

  def edges: EdgeRDD[ED] = {
    graphs.filterNot(_.edges.isEmpty).map(_.edges).reduce((a, b) => EdgeRDD.fromEdges[ED, VD](a.union(b)))
  }

  //Note: this kind of breaks the normal spark/graphx paradigm of returning
  //the new object with the change rather than making the change in place
  //intervals are assumed to be nonoverlapping
  //Note: snapshots should be added from earliest to latest for performance reasons
  def addSnapshot(place: Interval, snap: Graph[VD, ED]): SnapshotGraphParallel[VD, ED] = {
    var intvs = intervals
    var gps = graphs
    val iter: Iterator[Interval] = intvs.keysIterator
    var pos: Int = -1
    var found: Boolean = false

    // create a Breaks object (to break out of a loop)
    val loop = new Breaks

    loop.breakable {
      //this is not as efficient as binary search but the list is expected to be short
      while (iter.hasNext) {
        val nx: Interval = iter.next

        if (nx > place) {
          pos = intvs(nx) - 1
          found = true
          loop.break
        }
      }
    }

    //pos can be negative if there are no elements
    //or if this interval is the smallest of all
    //or if this interval is the largest of all
    if (pos < 0) {
      if (found) {
        //put in position 0, move the rest to the right
        pos = 0
        intvs.map { case (k, v) => (k, v + 1) }
        intvs += (place -> pos)
        gps = snap +: gps
      } else {
        //put in last
        intvs += (place -> gps.size)
        gps = gps :+ snap
      }
    } else {
      //put in the specified position, move the rest to the right
      intvs.foreach { case (k, v) => if (v > pos) (k, v + 1) }
      val (st, en) = gps.splitAt(pos - 1)
      gps = (st ++ (snap +: en))
    }

    new SnapshotGraphParallel(span, intvs, gps)
  }

  def getSnapshotByTime(time: Int): Graph[VD, ED] = {
    if (time >= span.min && time <= span.max) {
      //retrieve the position of the correct interval
      var position = -1
      val iter: Iterator[Interval] = intervals.keysIterator
      val loop = new Breaks

      loop.breakable {
        while (iter.hasNext) {
          val nx: Interval = iter.next
          if (nx.contains(time)) {
            position = intervals(nx)
            loop.break
          }
        }
      }

      getSnapshotByPosition(position)
    } else
      null
  }

  def getSnapshotByPosition(pos: Int): Graph[VD, ED] = {
    if (pos >= 0 && pos < graphs.size)
      graphs(pos)
    else
      null
  }

  //since SnapshotGraphParallels are involatile (except with add)
  //the result is a new SnapshotGraphParallel
  def select(bound: Interval): SnapshotGraphParallel[VD, ED] = {
    if (!span.intersects(bound)) {
      null
    }

    val minBound = if (bound.min > span.min) bound.min else span.min
    val maxBound = if (bound.max < span.max) bound.max else span.max
    val rng = Interval(minBound, maxBound)

    var selectStart = math.abs(span.min - minBound)
    var selectStop = maxBound - span.min + 1
    var intervalFilter: SortedMap[Interval, Int] = intervals.slice(selectStart, selectStop)

    val newGraphs: ParSeq[Graph[VD, ED]] = intervalFilter.map(x => graphs(x._2)).toSeq.par
    val newIntervals: SortedMap[Interval, Int] = intervalFilter.mapValues { x => x - selectStart }

    new SnapshotGraphParallel(rng, newIntervals, newGraphs)
  }

  def aggregate(resolution: Int, sem: AggregateSemantics.Value, vAggFunc: (VD, VD) => VD, eAggFunc: (ED, ED) => ED): SnapshotGraphParallel[VD, ED] = {
    var intvs: SortedMap[Interval, Int] = TreeMap[Interval, Int]()
    var gps: ParSeq[Graph[VD, ED]] = ParSeq[Graph[VD, ED]]()

    val iter: Iterator[(Interval, Int)] = intervals.iterator
    var numResults: Int = 0
    var minBound, maxBound: Int = 0;

    while (iter.hasNext) {
      val (k, v) = iter.next
      minBound = k.min
      maxBound = k.max

      //take resolution# of consecutive graphs, combine them according to the semantics
      var firstVRDD: RDD[(VertexId, VD)] = graphs(v).vertices
      var firstERDD: RDD[Edge[ED]] = graphs(v).edges
      var yy = 0
      var width = 0

      val loop = new Breaks
      loop.breakable {
        for (yy <- 1 to resolution - 1) {

          if (iter.hasNext) {
            val (k, v) = iter.next
            firstVRDD = firstVRDD.union(graphs(v).vertices)
            firstERDD = firstERDD.union(graphs(v).edges)
            maxBound = k.max
          } else {
            width = yy - 1
            loop.break
          }
        }
      }
      if (width == 0) width = resolution - 1

      intvs += (Interval(minBound, maxBound) -> numResults)
      numResults += 1

      if (sem == AggregateSemantics.Existential) {
        gps = gps :+ Graph(VertexRDD(firstVRDD.reduceByKey(vAggFunc)), EdgeRDD.fromEdges[ED, VD](firstERDD.map(e => ((e.srcId, e.dstId), e.attr)).reduceByKey(eAggFunc).map { x =>
          val (k, v) = x
          Edge(k._1, k._2, v)
        }))
      } else if (sem == AggregateSemantics.Universal) {
        gps = gps :+ Graph(firstVRDD.map(x => (x._1, (x._2, 1))).reduceByKey((x, y) => (vAggFunc(x._1, y._1), x._2 + y._2)).filter(x => x._2._2 > width).map { x =>
          val (k, v) = x
          (k, v._1)
        },
          EdgeRDD.fromEdges[ED, VD](firstERDD.map(e => ((e.srcId, e.dstId), (e.attr, 1))).reduceByKey((x, y) => (eAggFunc(x._1, y._1), x._2 + y._2)).filter(x => x._2._2 > width).map { x =>
            val (k, v) = x
            Edge(k._1, k._2, v._1)
          }))
      }
    }

    new SnapshotGraphParallel(span, intvs, gps)
  }

  //run PageRank on each contained snapshot
  def pageRank(uni: Boolean, tol: Double, resetProb: Double = 0.15, numIter: Int = Int.MaxValue): SnapshotGraphParallel[Double, Double] = {
      def safePagerank(grp: Graph[VD, ED]): Graph[Double, Double] = {
        if (grp.edges.isEmpty) {
          Graph[Double, Double](ProgramContext.sc.emptyRDD, ProgramContext.sc.emptyRDD)
        } else {
          if (uni)
            UndirectedPageRank.run(Graph(grp.vertices, grp.edges.coalesce(1, true)), tol, resetProb, numIter)
          else
            //FIXME: this doesn't use the numIterations stop condition
            grp.pageRank(tol, resetProb)
        }
      }

    val newseq: ParSeq[Graph[Double, Double]] = graphs.map(safePagerank)
    new SnapshotGraphParallel(span, intervals, newseq)
  }

  def partitionBy(pst: PartitionStrategyType.Value, runs: Int): SnapshotGraphParallel[VD, ED] = {
    partitionBy(pst, runs, graphs.head.edges.partitions.size)
  }

  def partitionBy(pst: PartitionStrategyType.Value, runs: Int, parts: Int): SnapshotGraphParallel[VD, ED] = {
    var numParts = if (parts > 0) parts else graphs.head.edges.partitions.size

    if (pst != PartitionStrategyType.None) {
      //not changing the intervals, only the graphs at their indices
      //each partition strategy for SG needs information about the graph

      //use that strategy to partition each of the snapshots
      var temp = intervals.map { case (a, b) => graphs(b).partitionBy(PartitionStrategies.makeStrategy(pst, b, graphs.size, runs), numParts) }
      var gps: ParSeq[Graph[VD, ED]] = temp.toSeq.par

      new SnapshotGraphParallel(span, intervals, gps)
    } else
      this
  }
  
  def connectedComponent(numIter: Int = Int.MaxValue): SnapshotGraphParallel[Long, ED] = {
    var intvs: SortedMap[Interval, Int] = TreeMap[Interval, Int]()
    var gps: ParSeq[Graph[Long, ED]] = ParSeq[Graph[Long, ED]]()
    val iter: Iterator[(Interval, Int)] = intervals.iterator
    var numResults: Int = 0

    while (iter.hasNext) {
      val (k, v) = iter.next
      intvs += (k -> numResults)
      numResults += 1

      if (graphs(v).vertices.isEmpty) {
        gps = gps :+ Graph[Long, ED](ProgramContext.sc.emptyRDD, ProgramContext.sc.emptyRDD)
      } else {
        var graph = graphs(v).connectedComponents()
        gps = gps :+ graph
      }
    }
    
    new SnapshotGraphParallel(span, intvs, gps)
  }

  def shortestPaths(landmarks: Seq[VertexId]): SnapshotGraphParallel[ShortestPathsXT.SPMap, ED] = {
    var intvs: SortedMap[Interval, Int] = TreeMap[Interval, Int]()
    var gps: ParSeq[Graph[ShortestPathsXT.SPMap, ED]] = ParSeq[Graph[ShortestPathsXT.SPMap, ED]]()
    val iter: Iterator[(Interval, Int)] = intervals.iterator
    var numResults: Int = 0


    while (iter.hasNext) {
      val (k, v) = iter.next
      intvs += (k -> numResults)
      numResults += 1

      if (graphs(v).vertices.isEmpty) {
        gps = gps :+ Graph[ShortestPathsXT.SPMap, ED](ProgramContext.sc.emptyRDD, ProgramContext.sc.emptyRDD)
      } else {
        var graph = ShortestPathsXT.run(graphs(v), landmarks)
        gps = gps :+ graph
      }
    }
    
    new SnapshotGraphParallel(span, intvs, gps)
  }

}

object SnapshotGraphParallel extends Serializable {
  //this assumes that the data is in the dataPath directory, each time period in its own files
  //and the naming convention is nodes<time>.txt and edges<time>.txt
  //TODO: extend to be more flexible about input data such as arbitrary uniform intervals are supported
  final def loadData(dataPath: String, startIndex: TimeIndex = 0, endIndex: TimeIndex = Int.MaxValue): SnapshotGraphParallel[String, Int] = {
    var minYear: Int = Int.MaxValue
    var maxYear: Int = 0

    var source: scala.io.Source = null
    var fs: FileSystem = null

    val pt: Path = new Path(dataPath + "/Span.txt")
    val conf: Configuration = new Configuration()
    if (System.getenv("HADOOP_CONF_DIR") != "") {
      conf.addResource(new Path(System.getenv("HADOOP_CONF_DIR") + "/core-site.xml"))
    }
    fs = FileSystem.get(conf)
    source = scala.io.Source.fromInputStream(fs.open(pt))

    val lines = source.getLines
    val minin = lines.next.toInt
    val maxin = lines.next.toInt
    minYear = if (minin > startIndex) minin else startIndex
    maxYear = if (maxin < endIndex) maxin else endIndex
    source.close()
    
    val span = Interval(minYear, maxYear)
    var years = 0
    var intvs: SortedMap[Interval, Int] = TreeMap[Interval, Int]()
    var gps: ParSeq[Graph[String, Int]] = ParSeq[Graph[String, Int]]()

    
    for (years <- minYear to maxYear) {
      val users: RDD[(VertexId, String)] = ProgramContext.sc.textFile(dataPath + "/nodes/nodes" + years + ".txt").map(line => line.split(",")).map { parts =>
        if (parts.size > 1 && parts.head != "")
          (parts.head.toLong, parts(1).toString)
        else
          (0L, "Default")
      }
      var edges: RDD[Edge[Int]] = ProgramContext.sc.emptyRDD
    
      val ept:Path = new Path(dataPath + "/edges/edges" + years + ".txt")
      if (fs.exists(ept) && fs.getFileStatus(ept).getLen > 0) {
        //uses extended version of Graph Loader to load edges with attributes
        val tmp = years
        edges = GraphLoaderAddon.edgeListFile(ProgramContext.sc, dataPath + "/edges/edges" + years + ".txt", true).edges
      } else {
        edges = ProgramContext.sc.emptyRDD
      }

      val graph: Graph[String, Int] = Graph(users, EdgeRDD.fromEdges(edges))
      
      intvs += (Interval(years, years) -> (years - minYear))
      gps = gps :+ graph
    }
    new SnapshotGraphParallel(span, intvs, gps)
  }
}
