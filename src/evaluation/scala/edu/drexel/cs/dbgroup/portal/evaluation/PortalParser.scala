package edu.drexel.cs.dbgroup.portal.evaluation

import scala.util.parsing.combinator.PackratParsers
import scala.util.parsing.combinator.syntactical.StandardTokenParsers
import org.apache.spark.storage.StorageLevel
import org.apache.spark.storage.StorageLevel._

import java.time.LocalDate

import org.apache.spark.graphx.Edge
import org.apache.spark.graphx.{VertexId,TripletFields}

import edu.drexel.cs.dbgroup.portal._
import edu.drexel.cs.dbgroup.portal.util.{LinearTrendEstimate, GraphLoader}
import edu.drexel.cs.dbgroup.portal.representations._

object PortalParser extends StandardTokenParsers with PackratParsers {
  lexical.reserved += ("select", "from", "union", "intersection", "differ", "min", "max", "sum", "count", "any", "all", "exists", "directed", "undirected", "vertices", "edges", "group", "by", "vgroup", "with", "return", "compute", "pagerank", "degree", "components", "count", "id", "attr", "trend", "list", "ave", "year", "month", "day", "changes", "start", "end", "where", "and", "length", "value", "spaths", "months", "days", "years", "project", "first", "second", "random", "one", "OG", "HG", "VE", "RG", "E2D", "CRVC",
    //these are for debugging and testing
    "materialize")
  lexical.delimiters ++= List("-", "=", ".", "<", ">", "(", ")", "+", ",")
  var strategy = PartitionStrategyType.None
  var width = 8
  var graphType = "RG"
  def setStrategy(str: PartitionStrategyType.Value):Unit = strategy = str
  def setRunWidth(rw: Int):Unit = width = rw
  def setGraphType(t: String):Unit = graphType = t

  def parse(input: String) = {
    println("parsing query " + input)
    val tokens = (new PackratReader(new lexical.Scanner(input)))
    val res = phrase(query)(tokens)
    res match {
      case Success(result, _) => Interpreter.parseQuery(result)
      case e: NoSuccess => throw new IllegalArgumentException("illformed query, message: " + e.toString)
    }
  }

  lazy val query: PackratParser[Query] = ( expr ~ "return" ~ entity ~ opt("." ~> attrStr) ^^ { case g ~ _ ~ e ~ Some(astr) => Return(g, e, astr)
    case g ~ _ ~ e ~ _ => Return(g, e, Attr()) }
    | expr ~ "materialize" ^^ { case g ~ _ => Materialize(g)}
  )

  lazy val expr: PackratParser[Expression] = ( select ~ "union" ~ "with" ~ function ~ function ~ select ^^ { case g1 ~ _ ~ _ ~ func1 ~ func2 ~ g2 => Union(g1, g2, func1, func2)}
    | select ~ "intersection" ~ "with" ~ function ~ function ~ select ^^ { case g1 ~ _ ~ _ ~ func1 ~ func2 ~ g2 => Intersect(g1, g2, func1, func2)}
    | select ~ "differ" ~ select ^^ { case g1 ~ _ ~ g2 => Difference(g1, g2)}
    | select ^^ { case sel => PlainSelect(sel)}
  )

  lazy val entity = ( "vertices" ^^^ Vertices()
    | "edges" ^^^ Edges()
  )

  lazy val attrStr = ( "count" ^^^ Count()
    | "id" ^^^ Id()
    | "attr" ^^^ Attr()
  )

  lazy val structure = ( "RG" ^^^ RG()
    | "OG" ^^^ OG()
    | "OGC" ^^^ OGC()
    | "HG" ^^^ HG()
    | "VE" ^^^ VE()
  )

  lazy val partitions = ( "E2D" ^^^ PartitionStrategyType.EdgePartition2D
    | "CRVC" ^^^ PartitionStrategyType.CanonicalRandomVertexCut
  )

  lazy val select: PackratParser[Select] = (
    "select" ~> "from" ~> graph ~ compute ~ withc.? ^^ { case g ~ cmp ~ st => new SCompute(g, cmp, st.getOrElse(NoConvert())) }
      | "select" ~> "from" ~> graph ~ where ~ withc.? ^^ { case g ~ w ~ st => new SWhere(g, w, st.getOrElse(NoConvert())) }
      | "select" ~> "from" ~> graph ~ groupby ~ withc.? ^^ { case g ~ gpb ~ st => new SGroupBy(g, gpb, st.getOrElse(NoConvert())) }
      | "select" ~> "from" ~> graph ~ "project" ~ entity ~ projfunction ~ withc.? ^^ { case g ~ _ ~ e ~ pr ~ st => new SProject(g, e, pr, st.getOrElse(NoConvert())) }
      | "select" ~> "from" ~> graph ^^ { case g => new Select(g) }
  )

  lazy val withc = ( "with" ~> structure.? ~ partitions.? ^^ { case struct ~ strat => Convert(struct.getOrElse(Same()),strat.getOrElse(PartitionStrategyType.None)) } )

  lazy val compute = ( "compute" ~> "pagerank" ~> dir ~ doubleLit ~ doubleLit ~ numericLit ^^ { case dir ~ tol ~ reset ~ numIter => Pagerank(dir, tol, reset, numIter)}
    | "compute" ~> "degree" ^^^ Degrees()
    | "compute" ~> "components" ^^^ ConnectedComponents()
    | "compute" ~> "spaths" ~> dir ~ landmarks ^^ { case dir ~ lds => ShortestPaths(dir, lds) }
  )

  lazy val landmarks: PackratParser[Seq[String]] = repsep(numericLit, ",")

  lazy val where = ("where" ~> datecond ~ opt("and" ~> datecond) ^^ {
    case datec ~ Some(datec2) => new TWhere(datec, datec2)
    case datec ~ _ => new TWhere(datec)
  }
    | "where" ~> attr ~ operation ~ literal ^^ { case a ~ o ~ l => SubWhere(a, o, l) }
  )

  //TODO: add group by size
  //FIXME: separate temporal and structural
  lazy val groupby = ("group" ~> "by" ~> numericLit ~ period ~ "vertices" ~ semantics ~ function ~ "edges" ~ semantics ~ function ~ vgroupby.? ^^ { case num ~ per ~ _ ~ vsem ~ vfunc ~ _ ~ esem ~ efunc ~ vgb => new GroupBy(num, per, vsem, vfunc, esem, efunc, vgb.getOrElse(Id()))})

  lazy val vgroupby = ("vgroup" ~> "by" ~> attrStr)

  lazy val projfunction = ("first" ^^^ ProjectFirst()
    | "second" ^^^ ProjectSecond()
    | "length" ^^^ ProjectLength()
    | "random" ~> numericLit ^^ { case num => ProjectRandom(num) }
    | "one" ^^^ ProjectOne()
  )

  lazy val graph = ("(" ~> select <~ ")" ^^ { case s => Nested(s) }
    | stringLit ~ numericLiteral.? ^^ { case s ~ c => DataSet(s, c.getOrElse("1").toInt) }
  )

  lazy val literal: Parser[String] =
    ( numericLiteral
    | stringLit)

  lazy val numericLiteral: Parser[String] =
    ( integral
    | sign.? ~ unsignedFloat ^^ { case s ~ f => s + f }
    )

  lazy val unsignedFloat: Parser[String] =
    ( "." ~> numericLit ^^ { u => "0." + u }
      | numericLit ~ "." ~ numericLit ^^ { case n1 ~ n2 => n1 + "." + n2 }
      | numericLit
    )

  lazy val integral: Parser[String] =
    sign.? ~ numericLit ^^ { case s ~ n => s.getOrElse("") + n }

  lazy val sign: Parser[String] = ("+" | "-")

  lazy val attr = ("length" | "value")

  lazy val operation: Parser[String] = (">" | "<" | "=")

  lazy val semantics = ( "all" ^^^ Universal()
    | "exists" ^^^ Existential()
  )

  lazy val doubleLit = ( numericLit ~ "." ~ numericLit ^^ { case num1 ~ _ ~ num2 => (num1 + "." + num2).toDouble} )

  lazy val datecond = ("start" ~> "=" ~> date ^^ { StartDate(_)}
    | "end" ~> "=" ~> date ^^ { EndDate(_)})

  lazy val year = ( numericLit ^^ { _.toInt })
  lazy val other = ( numericLit ^^ { _.toInt })

  lazy val date = ( year ~ ("-" ~> other) ~ ("-" ~> other) ^^ { case y ~ mo ~ d => new Date(y,mo,d) }
  )

  lazy val period = ("year" ^^^ Years()
    | "years" ^^^ Years()
    | "month" ^^^ Months()
    | "months" ^^^ Months()
    | "days" ^^^ Days()
    | "day" ^^^ Days()
    | "changes" ^^^ Changes()
  )

  lazy val dir = ( "directed" ^^^ Directed()
            | "undirected" ^^^ Undirected()
  )

  //TODO: add first/last
  lazy val function = ( "min" ^^^ MinFunc()
                 | "max" ^^^ MaxFunc()
                 | "sum" ^^^ SumFunc()
                 | "any" ^^^ AnyFunc()
                 | "count" ^^^ CountFunc()
                 | "trend" ^^^ TrendFunc()
                 | "list" ^^^ ListFunc()
                 | "ave" ^^^ AverageFunc()
  )


}

object Interpreter {
  var argNum:Int = 1

  def parseQuery(q: Query) {
    q match {
      case Materialize(graph) =>
        val intRes = parseExpr(graph)
        val materializeStart = System.currentTimeMillis()
        intRes.coalesce.materialize
        val materializeEnd = System.currentTimeMillis()
        val total = materializeEnd - materializeStart
        println(f"Materialize Runtime: $total%dms ($argNum%d)")
        argNum += 1
      case Return(graph, entity, attr) =>
        val intRes = parseExpr(graph)
        val countStart = System.currentTimeMillis()
        var op:String = ""
        entity match {
          case v: Vertices =>
            attr match {
              case c: Count =>
                val count = intRes.vertices.count
                println("Total vertex count: " + count)
                op = "Return"
              case i: Id =>
                println("Vertices:\n" + intRes.vertices.keys.collect.mkString(","))
                op = "Ids"
              case a: Attr =>
                println("Vertices with attributes:\n" + intRes.vertices.collect.mkString("\n"))
                op = "Return"
            }
          case e: Edges =>
            attr match {
              case c: Count =>
                val count = intRes.edges.count
                println("Total edges count: " + count)
                op = "Return"
              case i: Id =>
                println("Edges:\n" + intRes.edges.map(te => te.eId).collect.mkString(","))
                op = "Return"
              case a: Attr =>
                println("Edges with attributes:\n" + intRes.edges.collect.mkString("\n"))
                op = "Return"
            }
        }
        val countEnd = System.currentTimeMillis()
        val total = countEnd - countStart
        println(f"$op Runtime: $total%dms ($argNum%d)")
        argNum += 1
    }
  }

  def parseExpr(expr: Expression): TGraphNoSchema[Any, Any] = {
    expr match {
      case PlainSelect(gr) => {
        parseSelect(gr)
      }
      case Union(g1, g2,vfunc, efunc) => {
        val gr1 = parseSelect(g1)
        val gr2 = parseSelect(g2)
        val countStart = System.currentTimeMillis()
        val fun1 = (s1:Any, s2:Any) => {
          s1 match {
            case st: String => vfunc match {
              case su: SumFunc => st + s2.toString
              case mi: MinFunc => if (st > s2.toString) s2.toString else st
              case ma: MaxFunc => if (st < s2.toString) s2.toString else s1
              case an: AnyFunc => st
              case _ => throw new IllegalArgumentException("only sum/min/max/any are supported for the union operation")
            }
            case in: Int => vfunc match {
              case su: SumFunc => in + s2.asInstanceOf[Int]
              case mi: MinFunc => math.min(in, s2.asInstanceOf[Int])
              case ma: MaxFunc => math.max(in, s2.asInstanceOf[Int])
              case an: AnyFunc => in
              case _ => throw new IllegalArgumentException("only sum/min/max/any are supported for the union operation")
            }
            case du: Double => vfunc match {
              case su: SumFunc => du + s2.asInstanceOf[Double]
              case mi: MinFunc => math.min(du, s2.asInstanceOf[Double])
              case ma: MaxFunc => math.max(du, s2.asInstanceOf[Double])
              case an: AnyFunc => du
              case _ => throw new IllegalArgumentException("only sum/min/max/any are supported for the union operation")
            }
            case bl: StructureOnlyAttr => vfunc match {
              case an: AnyFunc => bl
              case _ => throw new IllegalArgumentException("only any supported for structureonlyattr type")
            }
            case null => null
            case _ => throw new IllegalArgumentException("unsupported data type " + s1.getClass + " in union")
          }
        }
        val fun2 = (s1:Any, s2:Any) => {
          s1 match {
            case st: String => efunc match {
              case su: SumFunc => st + s2.toString
              case mi: MinFunc => if (st > s2.toString) s2.toString else st
              case ma: MaxFunc => if (st < s2.toString) s2.toString else s1
              case an: AnyFunc => st
              case _ => throw new IllegalArgumentException("only sum/min/max/any are supported for the union operation")
            }
            case in: Int => efunc match {
              case su: SumFunc => in + s2.asInstanceOf[Int]
              case mi: MinFunc => math.min(in, s2.asInstanceOf[Int])
              case ma: MaxFunc => math.max(in, s2.asInstanceOf[Int])
              case an: AnyFunc => in
              case _ => throw new IllegalArgumentException("only sum/min/max/any are supported for the union operation")
            }
            case du: Double => efunc match {
              case su: SumFunc => du + s2.asInstanceOf[Double]
              case mi: MinFunc => math.min(du, s2.asInstanceOf[Double])
              case ma: MaxFunc => math.max(du, s2.asInstanceOf[Double])
              case an: AnyFunc => du
              case _ => throw new IllegalArgumentException("only sum/min/max/any are supported for the union operation")
            }
            case bl: StructureOnlyAttr => vfunc match {
              case an: AnyFunc => bl
              case _ => throw new IllegalArgumentException("only any supported for structureonlyattr type")
            }
            case null => null
            case _ => throw new IllegalArgumentException("unsupported data type " + s1.getClass + " in union")
          }
        }

        val res = gr1.union(gr2,fun1,fun2).asInstanceOf[TGraphNoSchema[Any,Any]]//.persist(StorageLevel.MEMORY_ONLY_SER)
        val countEnd = System.currentTimeMillis()
        val total = countEnd - countStart
        println(f"Union Runtime: $total%dms ($argNum%d)")
        argNum += 1
        res
      }
      case Intersect(g1, g2 , vfunc, efunc) => {
        val gr1 = parseSelect(g1)
        val gr2 = parseSelect(g2)
        val countStart = System.currentTimeMillis()
        val fun1 = (s1:Any, s2:Any) => {
          s1 match {
            case st: String => vfunc match {
              case su: SumFunc => st + s2.toString
              case mi: MinFunc => if (st > s2.toString) s2.toString else st
              case ma: MaxFunc => if (st < s2.toString) s2.toString else s1
              case an: AnyFunc => st
              case _ => throw new IllegalArgumentException("only sum/min/max/any are supported for the intersection operation")
            }
            case in: Int => vfunc match {
              case su: SumFunc => in + s2.asInstanceOf[Int]
              case mi: MinFunc => math.min(in, s2.asInstanceOf[Int])
              case ma: MaxFunc => math.max(in, s2.asInstanceOf[Int])
              case an: AnyFunc => in
              case _ => throw new IllegalArgumentException("only sum/min/max/any are supported for the intersection operation")
            }
            case du: Double => vfunc match {
              case su: SumFunc => du + s2.asInstanceOf[Double]
              case mi: MinFunc => math.min(du, s2.asInstanceOf[Double])
              case ma: MaxFunc => math.max(du, s2.asInstanceOf[Double])
              case an: AnyFunc => du
              case _ => throw new IllegalArgumentException("only sum/min/max/any are supported for the intersection operation")
            }
            case bl: StructureOnlyAttr => vfunc match {
              case an: AnyFunc => bl
              case _ => throw new IllegalArgumentException("only any supported for structureonlyattr type")
            }
            case null => null
            case _ => throw new IllegalArgumentException("unsupported data type " + s1.getClass + " in intersection")
          }
        }
        val fun2 = (s1:Any, s2:Any) => {
          s1 match {
            case st: String => efunc match {
              case su: SumFunc => st + s2.toString
              case mi: MinFunc => if (st > s2.toString) s2.toString else st
              case ma: MaxFunc => if (st < s2.toString) s2.toString else s1
              case an: AnyFunc => st
              case _ => throw new IllegalArgumentException("only sum/min/max/any are supported for the intersection operation")
            }
            case in: Int => efunc match {
              case su: SumFunc => in + s2.asInstanceOf[Int]
              case mi: MinFunc => math.min(in, s2.asInstanceOf[Int])
              case ma: MaxFunc => math.max(in, s2.asInstanceOf[Int])
              case an: AnyFunc => in
              case _ => throw new IllegalArgumentException("only sum/min/max/any are supported for the intersection operation")
            }
            case du: Double => efunc match {
              case su: SumFunc => du + s2.asInstanceOf[Double]
              case mi: MinFunc => math.min(du, s2.asInstanceOf[Double])
              case ma: MaxFunc => math.max(du, s2.asInstanceOf[Double])
              case an: AnyFunc => du
              case _ => throw new IllegalArgumentException("only sum/min/max/any are supported for the intersection operation")
            }
            case bl: StructureOnlyAttr => vfunc match {
              case an: AnyFunc => bl
              case _ => throw new IllegalArgumentException("only any supported for structureonlyattr type")
            }
            case null => null
            case _ => throw new IllegalArgumentException("unsupported data type " + s1.getClass + " in intersection")
          }
        }
        val res = gr1.intersection(gr2,fun1,fun2).asInstanceOf[TGraphNoSchema[Any,Any]]//.persist(StorageLevel.MEMORY_ONLY_SER)
        val countEnd = System.currentTimeMillis()
        val total = countEnd - countStart
        println(f"Intersection Runtime: $total%dms ($argNum%d)")
        argNum += 1
        res
      }
      case Difference(g1,g2) => {
        val gr1 = parseSelect(g1)
        val gr2 = parseSelect(g2)
        val countStart = System.currentTimeMillis()
        val res = gr1.difference(gr2)
        val countEnd = System.currentTimeMillis()
        val total = countEnd - countStart
        println(f"Difference Runtime: $total%dms ($argNum%d)")
        argNum += 1
        res
      }
    }
  }

  def parseSelect(sel: Select): TGraphNoSchema[Any,Any] = {
    sel match {
      case SCompute(g, cmp, st) => {
        val gr = parseGraph(g)
        compute(gr, cmp, st)
      }
      case SWhere(g, w, st) => {
        val gr = st match {
          case s: NoConvert => parseGraph(g, w)
          case c: Convert => convertGraph(parseGraph(g), c)
        }
        w match {
          case t: TWhere => {
            val opStart = System.currentTimeMillis()
            val res = gr.slice(Interval(t.start, t.end)).asInstanceOf[TGraphNoSchema[Any,Any]]//.persist(StorageLevel.MEMORY_ONLY_SER)
            val opEnd = System.currentTimeMillis()
            val total = opEnd - opStart
            println(f"Slice Runtime: $total%dms ($argNum%d)")
            argNum += 1
            res
          }
          case s: SubWhere => {
            val vp = (vid: Long, attrs: Any,interval: Interval) => {
              s.compute(attrs)
            }
            val opStart = System.currentTimeMillis()
            val res = gr.vsubgraph(pred = vp).asInstanceOf[TGraphNoSchema[Any,Any]]//.persist(StorageLevel.MEMORY_ONLY_SER)
            val opEnd = System.currentTimeMillis()
            val total = opEnd - opStart
            println(f"Subgraph Runtime: $total%dms ($argNum%d)")
            argNum += 1
            res
          }
        }
      }
      case SGroupBy(g, gbp, st) => {
        val gr = st match {
          case s: NoConvert => parseGraph(g)
          case c: Convert => convertGraph(parseGraph(g), c)
        }
        val opStart = System.currentTimeMillis()
        val spec = gbp.per match {
          case Changes() => ChangeSpec(gbp.num.toInt)
          case _ => TimeSpec(Resolution.from(gbp.period))
        }
        val fun1 = (s1:Any, s2:Any) => {
          s1 match {
            case st: String => gbp.vfun match {
              case su: SumFunc => st + s2.toString
              case mi: MinFunc => if (st > s2.toString) s2.toString else st
              case ma: MaxFunc => if (st < s2.toString) s2.toString else s1
              case an: AnyFunc => st
              case _ => throw new IllegalArgumentException("unsupported function " + gbp.vfun + " on string attribute of vertices")
            }
            case in: Int => gbp.vfun match {
              case su: SumFunc => in + s2.asInstanceOf[Int]
              case mi: MinFunc => math.min(in, s2.asInstanceOf[Int])
              case ma: MaxFunc => math.max(in, s2.asInstanceOf[Int])
              case an: AnyFunc => in
              case _ => throw new IllegalArgumentException("unsupported function " + gbp.vfun + " on int attribute of vertices")
            }
            case du: Double => gbp.vfun match {
              case su: SumFunc => du + s2.asInstanceOf[Double]
              case mi: MinFunc => math.min(du, s2.asInstanceOf[Double])
              case ma: MaxFunc => math.max(du, s2.asInstanceOf[Double])
              case an: AnyFunc => du
              case _ => throw new IllegalArgumentException("unsupported function " + gbp.vfun + " on double attribute of vertices")
            }
            case lt: List[Any] => gbp.vfun match {
              case su: SumFunc => lt ++ s2.asInstanceOf[List[Any]]
              case lf: ListFunc => lt ++ s2.asInstanceOf[List[Any]]
              case an: AnyFunc => lt
              case _ => throw new IllegalArgumentException("only sum/list/any functions are supported with lists")
            }
            case mp: Map[Any,Any] => gbp.vfun match {
              case su: SumFunc => mp ++ s2.asInstanceOf[Map[Any,Any]]
              case td: TrendFunc => mp ++ s2.asInstanceOf[Map[Any,Any]]
              case _ => throw new IllegalArgumentException("only sum and trend functions are supported with maps")
            }
            case tu: Tuple2[Double, Int] => gbp.vfun match {
              case ave: AverageFunc => (tu._1 + s2.asInstanceOf[Tuple2[Double,Int]]._1, tu._2 + s2.asInstanceOf[Tuple2[Double,Int]]._2)
              case ct: CountFunc => (tu._1, tu._2 + s2.asInstanceOf[Tuple2[Any,Int]]._2)
              case an: AnyFunc => tu
              case _ => throw new IllegalArgumentException("only average/any functions are supported with Tuples")
            }
            case tu2: Tuple2[Any, Int] => gbp.vfun match {
              case ct: CountFunc => (tu2._1, tu2._2 + s2.asInstanceOf[Tuple2[Any,Int]]._2)
              case _ => throw new IllegalArgumentException("only count supported")
            }
            case null => null
            case at: StructureOnlyAttr => at
            case _ => throw new IllegalArgumentException("unsupported vertex attribute type in aggregation")
          }
        }
        val fun2 = (s1:Any, s2:Any) => {
          s1 match {
            case st: String => gbp.efun match {
              case su: SumFunc => st + s2.toString
              case mi: MinFunc => if (st > s2.toString) s2.toString else st
              case ma: MaxFunc => if (st < s2.toString) s2.toString else s1
              case an: AnyFunc => st
              case _ => throw new IllegalArgumentException("unsupported function " + gbp.efun + " on string attribute of vertices")
            }
            case in: Int => gbp.efun match {
              case su: SumFunc => in + s2.asInstanceOf[Int]
              case mi: MinFunc => math.min(in, s2.asInstanceOf[Int])
              case ma: MaxFunc => math.max(in, s2.asInstanceOf[Int])
              case an: AnyFunc => in
               case _ => throw new IllegalArgumentException("unsupported function " + gbp.efun + " on int attribute of edges")
            }
            case du: Double => gbp.efun match {
              case su: SumFunc => du + s2.asInstanceOf[Double]
              case mi: MinFunc => math.min(du, s2.asInstanceOf[Double])
              case ma: MaxFunc => math.max(du, s2.asInstanceOf[Double])
              case an: AnyFunc => du
              case _ => throw new IllegalArgumentException("unsupported function " + gbp.efun + " on double attribute of vertices")
            }
            case null => null
            case at: StructureOnlyAttr => at
            case _ => throw new IllegalArgumentException("unsupported edge attribute type in aggregation")
          }
        }

        val mpd: TGraphNoSchema[Any,Any] = gbp.vfun match {
          case td: TrendFunc => gr.vmap((vid, intv, attr) => Map(intv -> attr), Map[Interval,Double]())
          case lt: ListFunc => gr.vmap((vid, intv, attr) => List(attr), List[Any]())
          case ave: AverageFunc => gr.vmap((vid, intv, attr) => (attr, 1), (0, 1))
          case ct: CountFunc => gr.vmap((vid, intv, attr) => (attr, 1), (gr.defaultValue, 1))
          case _ => gr
        }

        val agg = gbp.vgb match {
          case i: Id => mpd.createTemporalNodes(spec, gbp.vsem.value, gbp.esem.value, fun1, fun2).asInstanceOf[TGraphNoSchema[Any,Any]]
          //Todo: fix me. It's combined
          case a: Attr => {
            val vgb = (vid: VertexId, attr: Any) => {
 	        attr match {
		   case a: Tuple2[Any,Any] => a._1.hashCode().toLong
		   case _ => attr.hashCode().toLong
                }
            }
            mpd.createAttributeNodes(fun1)(vgb).asInstanceOf[TGraphNoSchema[Any,Any]]
          }
          case _ => throw new IllegalArgumentException("unsupported vgroupby")
        }

        val res: TGraphNoSchema[Any,Any] = gbp.vfun match {
          case td: TrendFunc => agg.vmap((vid, intv, attr) => LinearTrendEstimate.calculateSlopeFromIntervals(attr.asInstanceOf[Map[Interval,Double]]), 0.0)
          case ave: AverageFunc => agg.vmap((vid, intv, attr) => {val tp = attr.asInstanceOf[Tuple2[Double,Int]]; tp._1 / tp._2}, 0.0)
          case _ => agg
        }
        val opEnd = System.currentTimeMillis()
        val total = opEnd - opStart
        println(f"Aggregation Runtime: $total%dms ($argNum%d)")
        argNum += 1
        res
      }
      case SProject(g, e, f, st) => {
        val gr = st match {
          case s: NoConvert => parseGraph(g)
          case c: Convert => convertGraph(parseGraph(g), c)
        }
        val opStart = System.currentTimeMillis()
        val rGen = scala.util.Random
        val vm = (vid:Long, intv: Interval, attr:Any) => {
          attr match {
            case t: (Any,Any) => f match {
              case f: ProjectFirst => t._1
              case s: ProjectSecond => t._2
              case l: ProjectLength => 2
              case ProjectRandom(num) => rGen.nextInt(num.toInt)
            }
            case s: Set[Any] => f match {
              case f: ProjectFirst => s.head
              case se: ProjectSecond => s.tail
              case l: ProjectLength => s.size
              case ProjectRandom(num) => rGen.nextInt(num.toInt)
            }
            case st: String => f match {
              case f: ProjectLength => st.length
              case ProjectRandom(num) => rGen.nextInt(num.toInt)
              case _ => throw new IllegalArgumentException("unsupported function + " + f + " for string attributes")
            }
            case b: StructureOnlyAttr => f match {
	      case ProjectRandom(num) => rGen.nextInt(num.toInt)
              case _ => throw new IllegalArgumentException("unsupported function + " + f + " for string attributes")
            }
            case _ => throw new IllegalArgumentException("project not supported for this attribute type")
          }
        }
        val em = (e: TEdge[Any]) => {
          e.attr match {
            case t: (Any,Any) => f match {
              case f: ProjectFirst => t._1
              case s: ProjectSecond => t._2
              case l: ProjectLength => 2
              case ProjectRandom(num) => rGen.nextInt(num.toInt)
            }
            case s: Set[Any] => f match {
              case f: ProjectFirst => s.head
              case se: ProjectSecond => s.tail
              case l: ProjectLength => s.size
              case ProjectRandom(num) => rGen.nextInt(num.toInt)
            }
            case st: String => f match {
              case f: ProjectLength => st.length
              case ProjectRandom(num) => rGen.nextInt(num.toInt)
              case _ => throw new IllegalArgumentException("unsupported function + " + f + " for string attributes")
            }
            case b: StructureOnlyAttr => f match {
              case ProjectRandom(num) => rGen.nextInt(num.toInt)
              case o: ProjectOne => 1
              case _ => throw new IllegalArgumentException("unsupported function + " + f + " for string attributes")
            }
            case _ => throw new IllegalArgumentException("project not supported for this attribute type")
          }
        }

        val res = e match {
          case v: Vertices => {
            val dfv = gr.defaultValue match {
              case t: (Any,Any) => f match {
                case f: ProjectFirst => t._1
                case s: ProjectSecond => t._2
                case l: ProjectLength => 0
                case r: ProjectRandom => 0
              }
              case s: Set[Any] => f match {
                case l: ProjectLength => 0
                case r: ProjectRandom => 0
                case _ => s.head
              }
              case st: String => f match {
                case f: ProjectLength => 0
                case r: ProjectRandom => 0
                case _ => throw new IllegalArgumentException("unsupported function " + f + " on string attribute")
              }
              case b: StructureOnlyAttr => f match {
		case f: ProjectLength => 0
		case r: ProjectRandom => 0
                case _ => throw new IllegalArgumentException("unsupported function " + f + " on string attribute")
              }
              case _ => throw new IllegalArgumentException("project not supported for this attribute type")
            }
            gr.vmap(vm, dfv)
          }
          case ed: Edges => gr.emap(em)
        }
        val opEnd = System.currentTimeMillis()
        val total = opEnd - opStart
        println(f"Project Runtime: $total%dms ($argNum%d)")
        argNum += 1
        res
      }
      case s: Select => parseGraph(s.graph)
    }
  }

  def parseGraph(g: Graph, c: Where = NoWhere()): TGraphNoSchema[Any,Any] = {
    g match {
      case DataSet(nm, col) => {
         c match {
           case t: TWhere => load(nm, col, Interval(t.start, t.end))
           case _ =>  load(nm, col, Interval(LocalDate.MIN, LocalDate.MAX))
         }
      }
      case Nested(sel) => parseSelect(sel)
    }
  }

  def convertGraph(gr: TGraphNoSchema[Any,Any], c: Convert) = {
    val (verts, edgs, coal) = gr match {
      case rg: RepresentativeGraph[Any,Any] => (rg.verticesRaw, rg.edgesRaw, false)
      case ogc: OneGraphColumn[Any,Any] => (ogc.allVertices, ogc.allEdges, ogc.coalesced)
      case og: OneGraph[Any,Any] => (og.verticesRaw, og.edgesRaw, og.coalesced)
      case hg: HybridGraph[Any,Any] => (hg.allVertices, hg.allEdges, hg.coalesced)
      case ve: VEGraph[Any,Any] => (ve.allVertices, ve.allEdges, ve.coalesced)
      case _ => throw new IllegalArgumentException("oops, something went wrong and is a bug, unknown data type")
    }

    val res = c.ds match {
      case r: RG => RepresentativeGraph.fromRDDs(verts, edgs, gr.defaultValue, gr.storageLevel, coal)
      case o: OG => OneGraph.fromRDDs(verts, edgs, gr.defaultValue, gr.storageLevel, coal)
      case o: OGC => OneGraphColumn.fromRDDs(verts, edgs, gr.defaultValue, gr.storageLevel, coal)
      case h: HG => HybridGraph.fromRDDs(verts, edgs, gr.defaultValue, gr.storageLevel, coal)
      case v: VE => VEGraph.fromRDDs(verts, edgs, gr.defaultValue, gr.storageLevel, coal)
      case s: Same => gr
    }

    c.p match {
      case PartitionStrategyType.EdgePartition2D | PartitionStrategyType.CanonicalRandomVertexCut => res.partitionBy(TGraphPartitioning(c.p, PortalParser.width, 0)).asInstanceOf[TGraphNoSchema[Any,Any]]
      case _ => res
    }
  }

  def load(name: String, column: Int, bound: Interval): TGraphNoSchema[Any,Any] = {
    val selStart = System.currentTimeMillis()
    val vattrcol = if (name.endsWith("structure") || column < 1) -1 else column
    val eattrcol = if (name.endsWith("structure")) -1 else 1

    val res = PortalParser.graphType match {
      case "RG" => GraphLoader.buildRG(PortalShell.uri + "/" + name.split("structure").head, vattrcol, eattrcol, bound)
      case "OG" => GraphLoader.buildOG(PortalShell.uri + "/" + name.split("structure").head, vattrcol, eattrcol, bound)
      case "OGC" => GraphLoader.buildOGC(PortalShell.uri + "/" + name.split("structure").head, vattrcol, eattrcol, bound)
      case "HG" => GraphLoader.buildHG(PortalShell.uri + "/" + name.split("structure").head, vattrcol, eattrcol, bound)
      case "VE" => GraphLoader.buildVE(PortalShell.uri + "/" + name.split("structure").head, vattrcol, eattrcol, bound)
    }

    if (PortalShell.warmStart)
      res.materialize
    val selEnd = System.currentTimeMillis()
    val total = selEnd - selStart
    println(f"Load Runtime: $total%dms ($argNum%d)")
    argNum += 1
    res.partitionBy(TGraphPartitioning(PortalParser.strategy, PortalParser.width, 0)).asInstanceOf[TGraphNoSchema[Any,Any]]
  }

  def compute(gr: TGraphNoSchema[Any,Any], com: Compute, wi: WithC): TGraphNoSchema[Any,Any] = {
    val grin = wi match {
      case s: NoConvert => gr
      case c: Convert => convertGraph(gr, c)
    }
    com match {
      case Pagerank(dir, tol, res, numIter) => {
        val prStart = System.currentTimeMillis()
        val result = grin.pageRank(dir.value, tol, res, numIter.toInt).asInstanceOf[TGraphNoSchema[Any,Any]]//.persist(StorageLevel.MEMORY_ONLY_SER)
        val prEnd = System.currentTimeMillis()
        val total = prEnd - prStart
        println(f"PageRank Runtime: $total%dms ($argNum%d)")
        argNum += 1
        result
      }
      case Degrees() => {
        val degStart = System.currentTimeMillis()
        val result = grin.aggregateMessages[Int](triplet => { Iterator((triplet.dstId, 1), (triplet.srcId, 1))}, (a, b) => {a + b}, 0, TripletFields.None).asInstanceOf[TGraphNoSchema[Any,Any]]
        val degEnd = System.currentTimeMillis()
        val total = degEnd - degStart
        println(f"Degree Runtime: $total%dms ($argNum%d)")
        argNum += 1
        result
      }
      case ConnectedComponents() => {
        val conStart = System.currentTimeMillis()

        val result = grin.connectedComponents().asInstanceOf[TGraphNoSchema[Any,Any]]//.persist(StorageLevel.MEMORY_ONLY_SER)
        val conEnd = System.currentTimeMillis()
        val total = conEnd - conStart
        println(f"ConnectedComponents Runtime: $total%dms ($argNum%d)")
        argNum += 1
        result
      }
      case ShortestPaths(dir, ids) => {
        val spStart = System.currentTimeMillis()

        val result = grin.shortestPaths(dir.value, ids.map(_.toLong)).asInstanceOf[TGraphNoSchema[Any,Any]]//.persist(StorageLevel.MEMORY_ONLY_SER)
        val spEnd = System.currentTimeMillis()
        val total = spEnd - spStart
        println(f"ShortestPaths Runtime: $total%dms ($argNum%d)")
        argNum += 1
        result
      }
    }
  }
}

sealed abstract class Query
case class Return(graph: Expression, ent: Entity, attr: AttrStr) extends Query
case class Materialize(graph: Expression) extends Query

sealed abstract class Expression
case class PlainSelect(sel: Select) extends Expression
case class Union(graph1: Select, graph2: Select,vfunc: Function, efunc: Function) extends Expression
case class Intersect(graph1: Select, graph2: Select, vfunc: Function, efunc: Function) extends Expression
case class Difference(graph1: Select, graph2: Select) extends Expression

sealed abstract class Entity
case class Vertices() extends Entity
case class Edges() extends Entity

sealed abstract class AttrStr
case class Count() extends AttrStr
case class Id() extends AttrStr
case class Attr() extends AttrStr

sealed abstract class DataStructure
case class Same() extends DataStructure
case class RG() extends DataStructure
case class OG() extends DataStructure
case class OGC() extends DataStructure
case class HG() extends DataStructure
case class VE() extends DataStructure

sealed abstract class WithC
case class NoConvert() extends WithC
case class Convert(ds: DataStructure, p: PartitionStrategyType.Value) extends WithC

class Select(data: Graph) extends Serializable {
  val graph: Graph = data
}
case class SCompute(data: Graph, com: Compute, w: WithC) extends Select(data)
case class SWhere(data: Graph, w: Where, ds: WithC) extends Select(data)
case class SGroupBy(data: Graph, g: GroupBy, w: WithC) extends Select(data)
case class SProject(data: Graph, e: Entity, f: ProjectFunction, w: WithC) extends Select(data)

sealed abstract class Graph
case class DataSet(name: String, column: Int) extends Graph
case class Nested(sel: Select) extends Graph

sealed abstract class Compute
case class Pagerank(dir: Direction, tol: Double, reset: Double, numIter: String) extends Compute
case class Degrees() extends Compute
case class ConnectedComponents() extends Compute
case class ShortestPaths(dir: Direction, ids: Seq[String]) extends Compute

sealed abstract class Where
case class NoWhere() extends Where
case class TWhere(datec: Datecond) extends Where {
  var start: LocalDate = datec match {
    case StartDate(dt) => dt.value
    case _ => LocalDate.MIN
  }
  var end: LocalDate = datec match {
    case EndDate(dt) => dt.value
    case _ => LocalDate.MAX
  }

  def this(date1: Datecond, date2: Datecond) = {
    this(date1)
    date2 match {
      case StartDate(dt) => start = dt.value
      case EndDate(dt) => end = dt.value
    }
  }
}
case class SubWhere(attr: String, op: String, va: String) extends Where {
  //this is ugly but it'll do for now
  def compute(in: Any): Boolean = {
    if (in == null) return false
    attr match {
      case "length" => op match {
        case "=" => in.toString.length == va.toInt
        case ">" => in.toString.length > va.toInt
        case "<" => in.toString.length < va.toInt
      }
      //TODO: add handling for > and <
      case "value" => op match {
        case "=" => in.toString == va
      }
    }
  }
}

sealed abstract class Datecond
case class StartDate(datet: Date) extends Datecond
case class EndDate(datet: Date) extends Datecond

class Date(y: Int, m: Int, d: Int) {
  val year:Int = y
  val month:Int = m
  val day:Int = d
  def value():LocalDate = LocalDate.of(year, month, day)
}

case class GroupBy(num: String, per: Period, vsem: Semantics, vfun: Function, esem: Semantics, efun: Function, vgb: AttrStr) {
  val period = "P" + num + per.value
}

sealed abstract class Period {
  val value:String
}
case class Months() extends Period {
  val value = "M"
}
case class Years() extends Period {
  val value = "Y"
}
case class Days() extends Period {
  val value = "D"
}
case class Changes() extends Period {
  val value = "C"
}

sealed abstract class Direction {
  def value: Boolean
}
case class Directed() extends Direction {
  def value() = true
}
case class Undirected() extends Direction {
  def value() = false
}

sealed abstract class Function extends Serializable
case class MaxFunc() extends Function
case class MinFunc() extends Function
case class SumFunc() extends Function
case class CountFunc() extends Function
case class AnyFunc() extends Function
case class TrendFunc() extends Function
case class AverageFunc() extends Function
case class ListFunc() extends Function

sealed abstract class ProjectFunction extends Serializable
case class ProjectFirst() extends ProjectFunction
case class ProjectSecond() extends ProjectFunction
case class ProjectLength() extends ProjectFunction
case class ProjectRandom(num: String) extends ProjectFunction
case class ProjectOne() extends ProjectFunction

sealed abstract class Semantics {
  def value: Quantification
}

case class Universal() extends Semantics {
  def value() = Always()
}
case class Existential() extends Semantics {
  def value() = Exists()
}


