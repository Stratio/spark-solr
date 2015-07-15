package com.lucidworks.spark.example.hadoop;

import com.lucidworks.spark.SolrSupport;
import com.lucidworks.spark.SparkApp;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.OptionBuilder;
import org.apache.log4j.Logger;
import org.apache.solr.client.solrj.impl.CloudSolrClient;
import org.apache.solr.common.SolrInputDocument;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.PairFunction;

import scala.Tuple2;

public class HdfsToSolrRDDProcessor implements SparkApp.RDDProcessor {
  
  public static Logger log = Logger.getLogger(HdfsToSolrRDDProcessor.class);

  public String getName() { return "hdfs-to-solr"; }

  public Option[] getOptions() {
    return new Option[]{
      OptionBuilder
        .withArgName("PATH")
        .hasArg()
        .isRequired(false)
        .withDescription("HDFS path identifying the directories / files to index")
        .create("hdfsPath"),
      OptionBuilder
        .withArgName("INT")
        .hasArg()
        .isRequired(false)
        .withDescription("Queue size for ConcurrentUpdateSolrClient; default is 1000")
        .create("queueSize"),
      OptionBuilder
        .withArgName("INT")
        .hasArg()
        .isRequired(false)
        .withDescription("Number of runner threads per ConcurrentUpdateSolrClient instance; default is 2")
        .create("numRunners"),
      OptionBuilder
        .withArgName("INT")
        .hasArg()
        .isRequired(false)
        .withDescription("Number of millis to wait until CUSS sees a doc on the queue before it closes the current request and starts another; default is 20 ms")
        .create("pollQueueTime")
    };
  }

  // Benchmarking dataset generated by Solr Scale Toolkit
  private static final String[] pigSchema =
    ("id,integer1_i,integer2_i,long1_l,long2_l,float1_f,float2_f,double1_d,double2_d,timestamp1_tdt," +
      "timestamp2_tdt,string1_s,string2_s,string3_s,boolean1_b,boolean2_b,text1_en,text2_en,text3_en,random_bucket").split(",");

  public int run(SparkConf conf, CommandLine cli) throws Exception {
    JavaSparkContext jsc = new JavaSparkContext(conf);
    JavaRDD<String> textFiles = jsc.textFile(cli.getOptionValue("hdfsPath"));
    JavaPairRDD<String,SolrInputDocument> pairs = textFiles.mapToPair(new PairFunction<String, String, SolrInputDocument>() {
      public Tuple2<String, SolrInputDocument> call(String line) throws Exception {
        SolrInputDocument doc = new SolrInputDocument();
        String[] row = line.split("\t");
        if (row.length != pigSchema.length)
          return null;

        for (int c=0; c < row.length; c++)
          if (row[c] != null && row[c].length() > 0)
            doc.setField(pigSchema[c], row[c]);

        return new Tuple2<String, SolrInputDocument>((String)doc.getFieldValue("id"), doc);
      }
    });

    String zkHost = cli.getOptionValue("zkHost", "localhost:9983");
    String collection = cli.getOptionValue("collection", "collection1");
    int queueSize = Integer.parseInt(cli.getOptionValue("queueSize", "1000"));
    int numRunners = Integer.parseInt(cli.getOptionValue("numRunners", "2"));
    int pollQueueTime = Integer.parseInt(cli.getOptionValue("pollQueueTime", "20"));
    SolrSupport.streamDocsIntoSolr(zkHost, collection, "id", pairs, queueSize, numRunners, pollQueueTime);

    // send a final commit in case soft auto-commits are not enabled
    CloudSolrClient cloudSolrClient = SolrSupport.getSolrServer(zkHost,collection);
    cloudSolrClient.setDefaultCollection(collection);
    cloudSolrClient.commit(true, true);
    cloudSolrClient.close();

    return 0;
  }
}
