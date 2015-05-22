/*
 * Copyright 2015 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.cluster.data.storage

import java.util.Properties
import java.util.concurrent.Executors

import scala.collection.JavaConverters._

import com.google.common.annotations.VisibleForTesting
import com.typesafe.scalalogging.Logger
import kafka.admin.AdminUtils
import kafka.consumer.{ConsumerConfig, ConsumerIterator}
import kafka.javaapi.consumer.ConsumerConnector
import kafka.serializer.{Decoder, StringDecoder}
import org.I0Itec.zkclient.ZkClient
import org.apache.kafka.clients.producer.{RecordMetadata, KafkaProducer, ProducerRecord, Callback}
import org.apache.kafka.common.serialization.{Serializer, StringSerializer}
import org.slf4j.LoggerFactory
import rx.Observer
import rx.subjects.PublishSubject

import org.midonet.cluster.data.storage.MergedMap.MergedMapId
import org.midonet.cluster.storage.MergedMapConfig
import org.midonet.util.functors.makeRunnable

trait KafkaSerialization[K, V >: Null <: AnyRef] {
    /**
     * Converts a map key into a string.
     */
    def keyAsString(key: K): String

    /**
     * Builds a key from its String representation.
     */
    def keyFromString(key: String): K

    /**
     * Returns an encoder that is used to serialize a (key, value, owner)
     * triple into an array of bytes.
     */
    def messageEncoder: Serializer[(K, V, String)]

    /**
     * Returns a decoder that is used to deserialize an array of bytes into
     * a (key, value, owner) triple.
     */
    def messageDecoder: Decoder[(K, V, String)]
}

/**
 * A Kafka-based implementation of the MergedMapStorage trait.
 *
 * An opinion for key K and value V from an owner O is published as a kafka
 * message with key K-O, and value K-V-O. By using Kafka's log compaction
 * feature we only keep the latest message for each kafka key in the log.
 * This means that we keep the latest opinion for each owner and key.
 * //TODO: Garbage collect opinions of owners that have left.
 */
//TODO: Have just one consumer per agent and add topics gradually? That cannot be done...
class KafkaStorage[K, V >: Null <: AnyRef](id: MergedMapId, ownerId: String,
                                           config: MergedMapConfig,
                                           zkClient: ZkClient,
                                           kafkaIO: KafkaSerialization[K, V])
    (implicit crStrategy: Ordering[V]) extends MergedMapStorage[K, V] {

    type Opinion = (K, V, String)

    private val log =
        Logger(LoggerFactory.getLogger(getClass.getName + "-" + mapId.toString))

    private val inputSubj = PublishSubject.create[Opinion]()
    private val outputSubj = PublishSubject.create[Opinion]()

    //Topics are automatically created if they do no exist
    createTopicIfNeeded(config)
    private val producer = createProducer(config)
    private val consumer = createConsumer(config)
    private val consumerIterator = createConsumerIt(consumer)

    /* The consumer */
    private val consumerThread = Executors.newSingleThreadExecutor()
    private val consumerRunnable = makeRunnable({
        while(consumerIterator.hasNext()) {
            val msg = consumerIterator.next
            inputSubj onNext msg.message
        }
    })
    consumerThread.submit(consumerRunnable)

    private val sendCallBack = new Callback() {
        def onCompletion(metadata: RecordMetadata, e: Exception): Unit = {
            if (e != null) {
                log.warn("Unable to send Kafka message for topic: {} and " +
                         "partition: {}", metadata.topic,
                         Int.box(metadata.partition), e)
            }
        }
    }

    /* The producer */
    private val producerObserver = new Observer[Opinion] {
        override def onCompleted(): Unit =
            log.info("Output subject completed")
        override def onError(e: Throwable): Unit =
            log.warn("Error on output subject", e)
        override def onNext(opinion: Opinion): Unit = {
            val msgKey = kafkaIO.keyAsString(opinion._1) + "-" + ownerId
            val msg =
                new ProducerRecord[String, Opinion](mapId.toString /*topic*/,
                                                    msgKey /*key*/,
                                                    opinion /*value*/)
            producer.send(msg, sendCallBack)
        }
    }
    outputSubj subscribe producerObserver

    private def createTopicIfNeeded(config: MergedMapConfig): Unit = {
        if (!AdminUtils.topicExists(zkClient, topic = mapId.toString)) {
            val props = new Properties()
            // Always keep the last message for each key in the log.
            props.put("cleanup.policy", "compact")
            AdminUtils
                .createTopic(zkClient, topic = mapId.toString, partitions = 1,
                             config.replicationFactor, props)
        }
    }

    private def createConsumer(config: MergedMapConfig)
    : ConsumerConnector = {
        val consProps = new Properties
        consProps.put("zookeeper.connect", config.zkHosts)
        consProps.put("bootstrap.servers", config.brokers)
        consProps.put("group.id", "group-" + owner)
        consProps.put("consumer.timeout.ms", "10000")
        consProps.put("fetch.wait.max.ms", "10000")
        // Configure the consumer such that it can read all messages in
        // the topic (also those published before subscribing to the topic).
        consProps.put("auto.offset.reset", "smallest")
        kafka.consumer.Consumer.createJavaConsumerConnector(
            new ConsumerConfig((consProps)))
    }

    @VisibleForTesting
    private[storage] def computeMajority(replicaCount: Int): Int =
        Math.ceil((replicaCount + 1.0d) / 2.0d).asInstanceOf[Int]

    private def createProducer(config: MergedMapConfig)
    : KafkaProducer[String, Opinion] = {
        val prodProps = new Properties()
        prodProps.put("bootstrap.servers", config.brokers)
        /* The number of acks the producer requires the broker to have received
           from the replicas before considering a request complete */
        prodProps.put("acks",
                      computeMajority(config.replicationFactor).toString)
        prodProps.put("key.serializer", classOf[StringSerializer].getName)
        prodProps.put("value.serializer",
                      kafkaIO.messageEncoder.getClass.getName)
        new KafkaProducer[String, Opinion](prodProps)
    }

    private def createConsumerIt(cons: ConsumerConnector)
    : ConsumerIterator[String, Opinion] = {
        val topicCountMap = Map(mapId.toString -> Int.box(1) /* #partitions */)
                                .asJava
        val consumerMap = cons.createMessageStreams(topicCountMap,
                                                    new StringDecoder(),
                                                    kafkaIO.messageDecoder)
        val stream =  consumerMap.get(mapId.toString).get(0)
        stream.iterator()
    }

    override def opinionObservable = inputSubj
    override def opinionObserver = outputSubj

    /**
     * @return The map id this storage corresponds to.
     */
    override def mapId: MergedMapId = id

    /**
     * @return The owner this storage is built for.
     */
    override def owner: String = ownerId
}

