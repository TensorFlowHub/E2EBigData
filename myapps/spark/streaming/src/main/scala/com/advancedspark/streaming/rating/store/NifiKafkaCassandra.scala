package com.advancedspark.streaming.rating.store

import org.apache.spark.streaming.kafka.KafkaUtils
import org.apache.spark.streaming.Seconds
import org.apache.spark.streaming.StreamingContext
import org.apache.spark.SparkContext
import org.apache.spark.sql.SQLContext
import org.apache.spark.SparkConf
import kafka.serializer.StringDecoder
import org.apache.spark.sql.SaveMode
import org.apache.spark.sql.Row
import org.apache.spark.rdd.RDD
import org.apache.spark.streaming.Time
import com.advancedspark.streaming.rating.core.RatingGeo

object NiFiKafkaCassandra {
  def main(args: Array[String]) {
    val conf = new SparkConf()
      .set("spark.cassandra.connection.host", "127.0.0.1")

    val sc = SparkContext.getOrCreate(conf)

    def createStreamingContext(): StreamingContext = {
      @transient val newSsc = new StreamingContext(sc, Seconds(2))
      println(s"Creating new StreamingContext $newSsc")

      newSsc
    }
    val ssc = StreamingContext.getActiveOrCreate(createStreamingContext)

    val sqlContext = SQLContext.getOrCreate(sc)
    import sqlContext.implicits._

    // Cassandra Config
    val cassandraConfig = Map("keyspace" -> "advancedspark", "table" -> "item_ratings")

    // Kafka Config    
    val brokers = "127.0.0.1:9092"
    val kafkaParams = Map[String, String]("metadata.broker.list" -> brokers)
    val topics = Set("item_ratings")
 
    // Create Kafka Direct Stream Receiver
    val ratingsStream = KafkaUtils.createDirectStream[String, String, StringDecoder, StringDecoder](ssc, kafkaParams, topics)

    ratingsStream.print()

    ratingsStream.foreachRDD {
      (message: RDD[(String, String)], batchTime: Time) => {
//        message.cache()

        // Split each _2 element of the RDD (String,String) tuple into a RDD[Seq[String]]
        val tokens = message.map(_._2.split(","))

	// convert Tokens into RDD[Ratings]
        val ratings = tokens.map(token => 
          RatingGeo(token(0).trim.toInt,token(1).trim.toInt, token(2).trim.toInt, batchTime.milliseconds, token(3).trim.toString)
        )
  
        // save the DataFrame to Cassandra
        // Note:  Cassandra has been initialized through spark-env.sh
        //        Specifically, export SPARK_JAVA_OPTS=-Dspark.cassandra.connection.host=127.0.0.1
        val ratingsDF = ratings.toDF("userid", "itemid", "rating", "timestamp", "geocity")

        ratingsDF.write.format("org.apache.spark.sql.cassandra")
          .mode(SaveMode.Append)
          .options(cassandraConfig)
          .save()
	}

//	message.unpersist()
   }

    ssc.start()
    ssc.awaitTermination()
  }
}
