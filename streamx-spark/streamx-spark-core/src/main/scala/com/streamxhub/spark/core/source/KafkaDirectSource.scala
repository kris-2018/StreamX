/**
  * Copyright (c) 2019 The StreamX Project
  * <p>
  * Licensed to the Apache Software Foundation (ASF) under one
  * or more contributor license agreements. See the NOTICE file
  * distributed with this work for additional information
  * regarding copyright ownership. The ASF licenses this file
  * to you under the Apache License, Version 2.0 (the
  * "License"); you may not use this file except in compliance
  * with the License. You may obtain a copy of the License at
  * <p>
  * http://www.apache.org/licenses/LICENSE-2.0
  * <p>
  * Unless required by applicable law or agreed to in writing,
  * software distributed under the License is distributed on an
  * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
  * KIND, either express or implied. See the License for the
  * specific language governing permissions and limitations
  * under the License.
  */

package com.streamxhub.spark.core.source

import java.util.concurrent.ConcurrentHashMap

import com.streamxhub.spark.core.support.kafka.KafkaClient
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.spark.streaming.{StreamingContext, Time}
import org.apache.spark.streaming.dstream.DStream
import org.apache.spark.streaming.kafka010.{HasOffsetRanges, OffsetRange}

import scala.language.postfixOps
import scala.reflect.ClassTag
import scala.util.Try

/**
  *
  * 封装 Kafka Direct Api
  *
  * @param ssc
  * @param specialKafkaParams 指定 Kafka 配置,可以覆盖配置文件
  */
class KafkaDirectSource[K: ClassTag, V: ClassTag](@transient val ssc: StreamingContext,
                                                  specialKafkaParams: Map[String, String] = Map.empty[String, String]
                                                 ) extends Source {

  override val prefix: String = "spark.source.kafka.consume."

  // 分区数
  lazy val repartition: Int = sparkConf.get("spark.source.kafka.consume.repartition", "0").toInt

  // kafka 消费 topic
  private lazy val topicSet: Set[String] = specialKafkaParams.getOrElse("consume.topics",
    sparkConf.get("spark.source.kafka.consume.topics")).split(",").map(_.trim).toSet

  // 组装 Kafka 参数
  private lazy val kafkaParams: Map[String, String] = {
    sparkConf.getAll.flatMap {
      case (k, v) if k.startsWith(prefix) && Try(v.nonEmpty).getOrElse(false) => Some(k.substring(prefix.length) -> v)
      case _ => None
    } toMap
  } ++ specialKafkaParams ++ Map("enable.auto.commit" -> "false")

  lazy val groupId: Option[String] = kafkaParams.get("group.id")

  override type SourceType = ConsumerRecord[K, V]

  val kafkaClient = new KafkaClient(ssc.sparkContext.getConf)

  // 保存 offset
  private lazy val offsetRanges: java.util.Map[Long, Array[OffsetRange]] = new ConcurrentHashMap[Long, Array[OffsetRange]]

  /**
    * 获取DStream 流
    *
    * @return
    */
  override def getDStream[R: ClassTag](recordHandler: ConsumerRecord[K, V] => R): DStream[R] = {
    val stream = kafkaClient.createDirectStream[K, V](ssc, kafkaParams, topicSet)
    stream.transform((rdd, time) => {
      offsetRanges.put(time.milliseconds, rdd.asInstanceOf[HasOffsetRanges].offsetRanges)
      rdd
    }).map(recordHandler)
  }

  /**
    * 更新Offset 操作 一定要放在所有逻辑代码的最后
    * 这样才能保证,只有action执行成功后才更新offset
    */
  def updateOffset(time: Time): Unit = {
    // 更新 offset
    val milliseconds = time.milliseconds
    if (groupId.isDefined) {
      logInfo(s"[StreamX] updateOffset with ${kafkaClient.offsetStoreType} for time $milliseconds offsetRanges: $offsetRanges")
      val offsetRange = offsetRanges.get(milliseconds)
      kafkaClient.updateOffset(groupId.get, offsetRange)
    }
    offsetRanges.remove(milliseconds)
  }
}
