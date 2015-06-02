//
//  Author: Hari Sekhon
//  Date: 2015-05-25 19:39:56 +0100 (Mon, 25 May 2015)
//
//  vim:ts=2:sts=2:sw=2:et
//
//  https://github.com/harisekhon/spark-to-elasticsearch
//
//  License: see accompanying Hari Sekhon LICENSE file
//
//  If you're using my code you're welcome to connect with me on LinkedIn and optionally send me feedback to help improve or steer this or other code I publish
//
//  http://www.linkedin.com/in/harisekhon
//

// Generic Spark port of my Pig => Elasticsearch unstructured text / log files Hadoop job
// 
// based off 'pig-text-to-elasticsearch.pig' (see my Pig / Hive => Solr / Elasticsearch freebies in my public toolbox:
//
// https://github.com/harisekhon/toolbox

import HariSekhon.Utils._
import org.elasticsearch.spark._
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.OptionBuilder;
import org.apache.spark.SparkConf
import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import java.io.{ PrintWriter, File }
import org.apache.hadoop.io.LongWritable
import org.apache.hadoop.io.Text
import org.apache.hadoop.mapred.{ FileSplit, TextInputFormat }
import org.apache.spark.rdd.HadoopRDD
// for Kryo serialization
import java.lang.Long

object TextToElasticsearch {

  def main(args: Array[String]) {

    // set index.refresh_interval = -1 on the index and then set back at end of job
    // actually do this in the the shell/perl script outside of code to give flexibility as ppl may not want this

    // must use older commons-cli API to not conflict with Spark's embedded commons-cli
    // urgh this would work in Java but not in Scala since due to static class 
    //options.addOption(OptionBuilder.withLongOpt("index").withArgName("index").withDescription("Elasticsearch index and type in the format index/type").create("i"))
    //options.addOption(OptionBuilder.withLongOpt("path").withArgName("dir").withDescription("HDFS / File / Directory path to recurse and index to Elasticsearch").create("p"))
    //options.addOption(OptionBuilder.withLongOpt("es-nodes").withArgName("nodes").withDescription("Elasticsearch node list, comma separated (eg. node1:9200,node2:9200,...). Required, should list all nodes for balancing load since the Elastic spark driver using a direct connection and doesn't yet use a native cluster join as it does in the regular Java API").create("E"))
    //options.addOption(OptionBuilder.withLongOpt("count").withDescription("Generate a total count of the lines to index, for both reporting during the index job as well as writing it to '/tmp/<index_name>.count' to later comparison. This causes an extra job and shuffle and should be avoided for high volume or when you need to get things done quicker").create("c"))
    // XXX: this is awful consider replacing Apache Commons CLI with something else:
    //
    options.addOption("c", "count", false, "Generate a total count of the lines to index before sending to Elasticsearch, for both reporting how much there is to do before starting indexing as well as writing it to '/tmp/<index_name>.count' at the end for later comparison. This causes an extra Spark job and network shuffle and will slow you down")
    // this way doesn't print the description: 
    //OptionBuilder.withLongOpt("count")
    //OptionBuilder.withDescription("Generate a total count of the lines to index before sending to Elasticsearch, for both reporting how much there is to do before starting indexing as well as writing it to '/tmp/<index_name>.count' at the end for later comparison. This causes an extra Spark job and network shuffle and will slow you down")
    //options.addOption(OptionBuilder.create("c"))
    //
    OptionBuilder.withLongOpt("path")
    OptionBuilder.withArgName("path")
    OptionBuilder.withDescription("HDFS / File / Directory path to recurse and index to Elasticsearch")
    OptionBuilder.hasArg()
    OptionBuilder.isRequired()
    options.addOption(OptionBuilder.create("p"))
    //
    OptionBuilder.withLongOpt("index")
    OptionBuilder.withArgName("index")
    OptionBuilder.withDescription("Elasticsearch index and type in the format 'index/type'")
    OptionBuilder.hasArg()
    OptionBuilder.isRequired()
    options.addOption(OptionBuilder.create("i"))
    //
    OptionBuilder.withLongOpt("es-nodes")
    OptionBuilder.withArgName("nodes")
    OptionBuilder.withDescription("Elasticsearch node list, comma separated (eg. node1:9200,node2:9200,...). Required, should list all nodes for balancing load since the Elastic spark driver uses a direct connection and doesn't yet have native cluster join support as it does in the regular Elasticsearch Java API")
    OptionBuilder.hasArg()
    OptionBuilder.isRequired()
    options.addOption(OptionBuilder.create("E"))
    
    val cmd = get_options(args)
        
    val path: String = cmd.getOptionValue("p")
    //val index = validate_elasticsearch_index(args(1))
    val index: String = cmd.getOptionValue("i")
    //val es_nodes = validate_nodeport_list(args(2))
    val es_nodes: String = cmd.getOptionValue("E")
    // // in testing this makes no difference to the performance
    val do_count: Boolean = cmd.hasOption("c")

    // TODO: proper input validation of path dir/file/globs and <index/type> using my java lib later 
    if(path == null){
      usage("--path not set")
    }
    if(index == null){
      usage("--index not set")
    }
    if(es_nodes == null){
      usage("--es-nodes not set")
    }
    validate_nodeport_list(es_nodes)
    
    // by default you will get tuple position field names coming out in Elasticsearch (eg. _1: line, _2: content), so use case classes
    //case class Line(line: String)
    //case class DateLine(date: String, line: String)
    // case classes for use with lower level Hadoop InputFormat
    // Kryo serialization blows up on Hadoop types, do String conversions to work around
    // ES Hadoop blows up if using Hadoop types in the case class
    //case class FileLine(file: Text, line: Text)
    //case class FileDateLine(file: Text, date: Text, line: Text)
    case class FileLine(path: String, offset: Long, line: String)
    case class FileDateLine(file: String, offset: Long, date: String, line: String)

    // AppName needs to be short since it gets truncated in Spark 4040 job Web UI
    val conf = new SparkConf().setAppName("Text=>ES:" + index)
    // TODO: add option to disable Kryo serialization
    //conf.set("spark.serializer", classOf[org.apache.spark.serializer.KryoSerializer].getName)
    conf.set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
    // enforce registering Kryo classes, don't allow sloppiness, doing things at high scale performance tuning matters
    conf.set("spark.kryo.registrationRequired", "true")
    conf.registerKryoClasses(
      Array(
        //classOf[String],
        //classOf[Array[String]],
        //classOf[scala.Long],
        //classOf[Array[scala.Long]],
        // Kryo doesn't seem to like serializing Scala Long so use Java Long
        //classOf[Long],
        //classOf[Array[Long]],
        // Kryo also doesn't seem to like serializing Hadoop Writable types, so converting back to basic types instead
        //classOf[Text],
        //classOf[LongWritable],
        //classOf[Array[Text]],
        //classOf[Array[LongWritable]],
        //classOf[Line],
        //classOf[DateLine],
        classOf[FileLine],
        //classOf[FileDateLine],
        classOf[Array[FileLine]]
        //classOf[Array[FileDateLine]]
        //classOf[Array[Object]]
      )
    )
    
    conf.set("es.resource", index)
    conf.set("es.nodes", es_nodes)
    // avoid this as it will introduce unnecessary duplicates into the Elasticsearch index, trade off as we may get slowed down a little by straggler tasks
    conf.set("spark.speculation", "false")
    //conf.set("es.query", "?q=me*")
    val sc = new SparkContext(conf)
    //val lines = sc.textFile(path).cache()
    // thought I was on to something with lines.name but this returns the file glob, not the individual file names so
    // must go lower level to Hadoop InputFormat to allow us to index the originating filename as textFile() doesn't allow for this
    sc.hadoopConfiguration.set("mapreduce.input.fileinputformat.input.dir.recursive","true")
    val lines = sc.hadoopFile(path, classOf[TextInputFormat], classOf[LongWritable], classOf[Text], sc.defaultMinPartitions)
    val hadoopRdd = lines.asInstanceOf[HadoopRDD[LongWritable, Text]]
    val fileLines = hadoopRdd.mapPartitionsWithInputSplit { (inputSplit, iterator) =>
      // get the filename we're currently processing to record in Elasticsearch and strip off redundant file:/ and hdfs://nn:8020/ prefixes, not worth the extra bytes at scale
      val filepath = inputSplit.asInstanceOf[FileSplit].getPath().toString().replaceFirst("^file:", "").replaceFirst("^hdfs:\\/\\/[\\w.-]+(?:\\d+)?", "")
      iterator.map { l => {
        // Keeping the offset now so you could order by offset and see the original ordering of lines in each file if wanted
        //println("\n*** INPUTSPLIT %s,%s,%s\n".format(filepath,l._1,l._2))
        //(filepath, l._1, l._2)
        // XXX: Hadoop Text formats blow up Kryo and later on ElasticSearch so convert them to basic types - review this as surely everyone else must have worked around this before without having to do what might be costly at scale conversions
        //(filepath.toString(), l._1.toString().toLong, l._2.toString())
        // Kryo doesn't seem to like serializing Scala Long so use Java Long
        (filepath.toString(), Long.valueOf(l._1.toString()).longValue(), l._2.toString())
        }
      }
    }.cache()
    
    val start: Long =  System.currentTimeMillis
    // ====================================================
    var count : String = "uncounted"
    if(do_count){
      println("\n*** Calculating how many records we are going to be dealing with - there is overhead to this as it's basically a pre-job but it allows us to check the counts in Elasticsearch later on for higher confidence in the correctness and completeness of the indexing\n")
      //val count = lines.count().toString()
      count = fileLines.count().toString()
      // this is collected not an RDD at this point, save using local file PrintWriter at the end after Spark job
      // count.saveAsTextFile("/tmp/" + path)   
    }
    // =====================================================

    println("\n*** Indexing path '%s' (%s records) to Elasticsearch index '%s' (nodes: '%s')\n".format(path, count, index, es_nodes))
    /*
    val es_map = lines.map(line => {
      // TODO: implement date parsing and make use of DateLine if possible for Elasticsearch range time queries
      // if we find don't find the date (date == null) create only Line class not DateLine which would end up being
      if (true) {
        //println("sending only line")
        Line(line)
      } else {
        //println("sending date + line")
        DateLine(null, line)
      }
    })
    */
    // case class to annotate fields for indexing in Elasticsearch
    //val es_map = lines.map(fileLine => {
    val es_map = fileLines.map(l => {
      // TODO: add Date formats parsing and add date conversion and date field if suitable date is found
      // XXX: only use this for debugging at small scale on your laptop!
      //println("\n*** path: '%s', offset: '%s', line: '%s'\n".format(l._1, l._2, l._3))
      // converting earlier in the pipeline now to avoid Kryo serialization errors
      FileLine(l._1, l._2, l._3)
      // last minute conversions to prevent org.elasticsearch.hadoop.serialization.EsHadoopSerializationException, did earlier conversions in fileLines mapPartitionsWithInputSplit and changed case class instead
      //FileLine(l._1.toString(), Long.valueOf(l._2.toString()).longValue(), l._3.toString())
    })
    es_map.saveToEs(index)

    lines.unpersist()
    // TODO: do elasticsearch query vs count reporting here
    // the high level API would pull all data through RDD whereas I just want the hit count header on first page of 10 results
    // maybe handle this in calling script instead
    sc.stop()
    // TODO: tie in with count above to be an optional switch
    val count_file = index.replaceAll("[^A-Za-z0-9_-]", "_")
    val secs = (System.currentTimeMillis - start) / 1000
    println("\n*** Finished indexing path '%s' (%s records) to Elasticsearch index '%s' (nodes: %s) in %d secs\n".format(path, count, index, es_nodes, secs))
    // leave count file with contents "uncounted" as a placeholder to reduce confusion
    if(count != "uncounted"){
      println("writing index count for index '%s' to /tmp/%s.count for convenience for cross referencing later if needed\n".format(index, count_file))
    } else {
      println("writing uncounted placeholder file for index '%s' to /tmp/%s.count\n".format(index, count_file))
    }
    // Still write the file for uniformity
    // raises FileNotFoundException at end of job when using globs for Spark's textFile(), use index name converted for filename safety instead
    val pw = new PrintWriter(new File("/tmp/" + count_file + ".count"))
    pw.write(count.toString)
    pw.close()
    //}
    
  }

}