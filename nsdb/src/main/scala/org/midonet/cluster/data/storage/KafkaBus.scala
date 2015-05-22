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

import java.lang.Long
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.{Map => JMap, Properties}

import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap

import com.google.common.annotations.VisibleForTesting
import com.typesafe.scalalogging.Logger
import kafka.admin.AdminUtils
import kafka.api.TopicData
import kafka.consumer.{ConsumerConfig, ConsumerIterator}
import kafka.javaapi.consumer.ConsumerConnector
import kafka.serializer.{Decoder, StringDecoder}
import org.I0Itec.zkclient.ZkClient
import org.apache.kafka.clients.consumer.{ConsumerRecord, ConsumerRecords, KafkaConsumer}
import org.apache.kafka.clients.producer.{Callback, KafkaProducer, ProducerRecord, RecordMetadata}
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization._
import org.slf4j.LoggerFactory
import rx.Observer
import rx.subjects.PublishSubject

import org.midonet.cluster.data.storage.MergedMap._
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
 * This class allows to use a single consumer to subscribe to multiple topics
 * and consume messages in a blocking manner. Consequently, a single thread is
 * sufficient for all merged maps of a Midonet agent.
 *
 * As of Kafka-0.8.2.1, the KafkaConsumer class used is not complete and the
 * class below cannot be used for the moment. KafkaConsumer is expected
 * to be completed in version 0.8.3 of Kafka, which is scheduled for July 2015.
 */
private class ConsumerService(zkClient: ZkClient, config: MergedMapConfig,
                              owner: String) {

    type MsgsType = ConsumerRecords[String, Array[Byte]]
    type MsgType = ConsumerRecord[String, Array[Byte]]
    type Opinion = (_, _ >: Null <: AnyRef, String)

    private class TopicObserver[T <: Opinion](topic: String,
                                              observer: Observer[T],
                                              deserializer: Deserializer[T]) {
        def onNext(msgInBytes: Array[Byte]): Unit = {
            val msg = deserializer.deserialize(topic, msgInBytes)
            observer.onNext(msg)
        }
    }

    private val consumer = createConsumer(config.zkHosts, owner)
    private val observers = new TrieMap[String, TopicObserver[_ <: Opinion]]
    private val run = new AtomicBoolean(true)

    private val consumerThread = Executors.newSingleThreadExecutor()
    private val consumerRunnable = makeRunnable({
        while(run.get) {
            val topicMsgsMap = consumer.poll(0 /*timeout, 0=block*/)
            consume(topicMsgsMap)
        }
    })
    consumerThread.submit(consumerRunnable)

    private def createConsumer(zkHosts: String, owner: String)
    : KafkaConsumer[String, Array[Byte]] = {

        val consProps = new Properties
        consProps.put("zookeeper.connect", zkHosts)
        consProps.put("group.id", "group-" + owner)
        /* We never call commit on the consumer. This is what we want, if the
           agent restarts, the consumer will consume all messages from the topic
           and rebuild the map properly. */
        // TODO: What happens when a leader broker dies and we reconnect to a
        //       new one, do we re-consume all messages in that case?
        consProps.put("auto.commit", "false")
        /* Configure the consumer such that it can read all messages in
           the topic (also those published before subscribing to the topic). */
        consProps.put("auto.offset.reset", "smallest")
        consProps.put("key.deserializer", classOf[StringDeserializer].getName)
        consProps.put("value.deserializer",
                      classOf[ByteArrayDeserializer].getName)
        new KafkaConsumer[String, Array[Byte]](consProps)
    }

    private[storage] def computeMajority(replicaCount: Int): Int =
        Math.ceil((replicaCount + 1.0d) / 2.0d).asInstanceOf[Int]

    private def createTopicIfNeeded(topic: String, replFactor: Int): Unit = {
        if (!AdminUtils.topicExists(zkClient, topic)) {
            val props = new Properties()
            // Always keep the last message for each key in the log.
            props.put("cleanup.policy", "compact")
            props.put("min.insync.replicas",
                      computeMajority(replFactor).toString)
            AdminUtils.createTopic(zkClient, topic, partitions = 1,
                                   config.replicationFactor, props)
        }
    }

    private def consume(topicMsgsMap: JMap[String, MsgsType]): Unit = {
        for (entry: (String, MsgsType) <- topicMsgsMap.asScala) {
            val topic = entry._1
            val records = entry._2
            val observer = observers(topic)
            val recordsToConsider = records.records(0 /*partition*/)
            for (record: MsgType <- recordsToConsider.asScala) {
                observer.onNext(record.value)
            }
        }
    }

    def subscribe[K, V >: Null <: AnyRef]
        (topic: String, observer: Observer[(K, V, String)],
         deserializer: Deserializer[(K, V, String)]): Unit = {

        createTopicIfNeeded(topic, config.replicationFactor)
        consumer.subscribe(topic)
        val topicPartition = new TopicPartition(topic, 0 /*partition*/)
        val offsets = Map(topicPartition -> new Long(0) /*offset*/).asJava
        consumer.seek(offsets)
        observers.put(topic, new TopicObserver(topic, observer, deserializer))
    }

    def unsubscribe(topic: String): Unit = {
        consumer.unsubscribe(topic)
        observers.remove(topic)
    }

    def stop(): Unit = run.set(false)
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
class KafkaBus[K, V >: Null <: AnyRef](id: String, ownerId: String,
                                       config: MergedMapConfig,
                                       zkClient: ZkClient,
                                       val kafkaIO: KafkaSerialization[K, V])
    (implicit crStrategy: Ordering[V]) extends MergedMapBus[K, V] {

    type Opinion = (K, V, String)

    private val log =
        Logger(LoggerFactory.getLogger(getClass.getName + "-" + mapId.toString))

    private val opinionOutput = PublishSubject.create[Opinion]()
    private val opinionOutputBckPressure = opinionOutput.onBackpressureBuffer()
    private val opinionInput = PublishSubject.create[Opinion]()
    private val opinionInputBckPressure = opinionInput.onBackpressureBuffer()

    createTopicIfNeeded(config)
    private val producer = createProducer(config)
    private val consumer = createConsumer(config)
    private val consumerIterator = createConsumerIt(consumer)

    /* The consumer */
    // TODO: Is there a way to not have one thread per KafkaBus for the
    //       consumer?
    private val consumerThread = Executors.newSingleThreadExecutor()
    private val consumerRunnable = makeRunnable({
        /* By default consumer.timeout.ms is -1 and as a consequence,
           hasNext blocks until a message is available. */
        while(consumerIterator.hasNext()) {
            val msg = consumerIterator.next
            opinionOutput onNext msg.message
        }
    })
    consumerThread.submit(consumerRunnable)

    private val sendCallBack = new Callback() {
        def onCompletion(metadata: RecordMetadata, e: Exception): Unit = {
            if (e != null) {
                log.warn("Unable to send Kafka message", e)
            }
        }
    }

    private def shutdown(): Unit = {
        producer.close()
        consumerThread.shutdownNow()
        consumer.shutdown()
    }

    /* The producer */
    private val producerObserver = new Observer[Opinion] {
        override def onCompleted(): Unit = {
            log.info("Kafka bus output completed, shutting down.")
            shutdown()
        }
        override def onError(e: Throwable): Unit = {
            log.warn("Error on output kafka bus, shutting down.", e)
            shutdown()
        }
        override def onNext(opinion: Opinion): Unit = {
            val msgKey = kafkaIO.keyAsString(opinion._1) + "-" + ownerId
            val msg =
                new ProducerRecord[String, Opinion](mapId.toString /*topic*/,
                                                    msgKey /*key*/,
                                                    opinion /*value*/)
            producer.send(msg, sendCallBack)
        }
    }
    opinionInputBckPressure.observeOn(scheduler)
                           .subscribe(producerObserver)

    private def createTopicIfNeeded(config: MergedMapConfig): Unit = {
        if (!AdminUtils.topicExists(zkClient, topic = mapId.toString)) {
            val props = new Properties()
            // Always keep the last message for each key in the log.
            props.put("cleanup.policy", "compact")
            props.put("min.insync.replicas",
                      computeMajority(config.replicationFactor).toString)
            AdminUtils
                .createTopic(zkClient, topic = mapId.toString, partitions = 1,
                             config.replicationFactor, props)
        }
    }

    private def createConsumer(config: MergedMapConfig)
    : ConsumerConnector = {
        val consProps = new Properties
        consProps.put("zookeeper.connect", config.zkHosts)
        consProps.put("group.id", "group-" + owner)
        /* We never call commit on the consumer. This is what we want, if the
           agent restarts, the consumer will consume all messages from the topic
           and rebuild the map properly. */
        // TODO: What happens when a leader broker dies and we reconnect to a new
        //       one, do we re-consume all messages in that case?
        consProps.put("auto.commit", "false")
        /* Configure the consumer such that it can read all messages in
           the topic (also those published before subscribing to the topic). */
        consProps.put("auto.offset.reset", "smallest")
        kafka.consumer.Consumer.createJavaConsumerConnector(
            new ConsumerConfig(consProps))
    }

    @VisibleForTesting
    private[storage] def computeMajority(replicaCount: Int): Int =
        Math.ceil((replicaCount + 1.0d) / 2.0d).asInstanceOf[Int]

    private def createProducer(config: MergedMapConfig)
    : KafkaProducer[String, Opinion] = {
        val prodProps = new Properties()
        prodProps.put("bootstrap.servers", config.brokers)
        /* This specifies that the producer must receive an acknowledgment
           from min.insync.replicas (set when creating the topic) before
           considering the publishing successful. */
        prodProps.put("acks", "all")
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
        val stream = consumerMap.get(mapId.toString).get(0)
        stream.iterator()
    }

    override def opinionObservable = opinionOutputBckPressure
    override def opinionObserver = opinionInput

    /**
     * @return The map id this storage corresponds to.
     */
    override def mapId: String = id

    /**
     * @return The owner this storage is built for.
     */
    override def owner: String = ownerId
}

