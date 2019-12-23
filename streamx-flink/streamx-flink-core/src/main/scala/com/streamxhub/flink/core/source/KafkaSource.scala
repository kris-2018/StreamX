package com.streamxhub.flink.core.source

import org.apache.flink.api.common.serialization.SimpleStringSchema
import org.apache.flink.streaming.api.scala.{DataStream, _}
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaConsumer011
import com.streamxhub.flink.core.StreamingContext
import com.streamxhub.flink.core.conf.{ConfigConst, Config}

import scala.collection.JavaConversions._
import scala.collection.Map
import scala.language.postfixOps

class KafkaSource(@transient val ctx: StreamingContext, specialKafkaParams: Map[String, String] = Map.empty[String, String]) {

  /**
   * 获取DStream 流
   *
   * @return
   */
  def getDataStream(implicit topic: String = ""): DataStream[String] = {
    val prop = Config.getKafkaSource(ctx.parameter, topic)
    prop.putAll(specialKafkaParams)
    val _topic = prop.getProperty(ConfigConst.TOPIC)
    val consumer = new FlinkKafkaConsumer011[String](_topic, new SimpleStringSchema(), prop)
    ctx.env.addSource(consumer)
  }
}