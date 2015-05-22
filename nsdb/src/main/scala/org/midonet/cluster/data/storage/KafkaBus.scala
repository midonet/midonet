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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{Executors, TimeUnit}

import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.util.control.NonFatal

import com.google.common.annotations.VisibleForTesting
import com.typesafe.scalalogging.Logger
import kafka.admin.AdminUtils
import org.I0Itec.zkclient.ZkClient
import org.apache.kafka.clients.consumer.{ConsumerRecord, ConsumerRecords, KafkaConsumer}
import org.apache.kafka.clients.producer.{Callback, KafkaProducer, ProducerRecord, RecordMetadata}
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization._
import org.slf4j.LoggerFactory
import rx.Observer
import rx.subjects.PublishSubject

import org.midonet.cluster.data.storage.MergedMap._
import org.midonet.cluster.storage.KafkaConfig
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
    def messageDecoder: Deserializer[(K, V, String)]
}

/**
 * This class allows to use a single consumer to subscribe to multiple topics.
 * A single thread is thus sufficient for all merged maps of a MidoNet agent.
 */
private[storage] class ConsumerService(config: KafkaConfig, owner: String) {
    type MsgsType = ConsumerRecords[String, Array[Byte]]
    type MsgType = ConsumerRecord[String, Array[Byte]]
    type Opinion = (_, _ >: Null <: AnyRef, String)

    private val POLL_TIMEOUT_MS = 100

    private val log = Logger(LoggerFactory.getLogger(
        "org.midonet.cluster.state.MergedMap.ConsumerService"))

    private class TopicObserver[T <: Opinion](topic: String,
        observer: Observer[T], deserializer: Deserializer[T])
        extends Observer[Array[Byte]] {

        override def onNext(msgInBytes: Array[Byte]): Unit = {
            val msg = deserializer.deserialize(topic, msgInBytes)
            observer.onNext(msg)
        }
        override def onCompleted(): Unit = {
            observer.onCompleted()
        }
        override def onError(e: Throwable): Unit = {
            observer.onError(e)
        }
    }

    private var consumer = createConsumer(config.zkHosts, config.brokers, owner)
    private val observers = new TrieMap[String, TopicObserver[_ <: Opinion]]
    private val run = new AtomicBoolean(false)
    private val consumerThread = Executors.newSingleThreadExecutor()

    private val consumerRunnable = makeRunnable({
        while(run.get) {
            try {
                var msgs: ConsumerRecords[String, Array[Byte]] = null
                this.synchronized {
                    msgs = consumer.poll(POLL_TIMEOUT_MS)
                }
                for (msg: MsgType <- msgs.asScala) {
                    val observer = observers(msg.topic)
                    observer.onNext(msg.value)
                }
            } catch {
                case NonFatal(e) =>
                    log.error("Closing Kafka consumer service", e)
                    close()
            }
        }
    })

    private def createConsumer(zkHosts: String, brokers: String, owner: String)
    : KafkaConsumer[String, Array[Byte]] = {
        val consProps = new Properties
        consProps.put("zookeeper.connect", zkHosts)
        consProps.put("zookeeper.session.timeout", "30000")
        consProps.put("bootstrap.servers", brokers)
        consProps.put("group.id", "group-" + owner)
        consProps.put("key.deserializer", classOf[StringDeserializer].getName)
        consProps.put("value.deserializer",
                      classOf[ByteArrayDeserializer].getName)
        new KafkaConsumer[String, Array[Byte]](consProps)
    }

    private[storage] def subscribe[K, V >: Null <: AnyRef]
        (topic: String, observer: Observer[(K, V, String)],
         deserializer: Deserializer[(K, V, String)]): Unit = {

        this.synchronized {
            var startConsumer = false
            if (observers.size == 0) {
                startConsumer = true
            }
            if (!observers.contains(topic)) {
                val topicPartition = new TopicPartition(topic, 0 /*partition*/)
                observers.put(topic, new TopicObserver(topic, observer,
                                                       deserializer))
                consumer.subscribe(topicPartition)
                consumer.seekToBeginning(topicPartition)

                if (startConsumer) {
                    run.set(true)
                    consumerThread.submit(consumerRunnable)
                }
            }
        }
    }

    /**
     * Unsubscribes from the given topic and if this is the last topic the
     * consumer was subscribed to, we close the consumer.
     */
    private[storage] def unsubscribe(topic: String): Unit = {
        this.synchronized {
            consumer.unsubscribe(new TopicPartition(topic,
                                                    0 /* partition */))
            observers(topic).onCompleted()
            observers.remove(topic)
        }
    }

    private[storage] def close(): Unit = {
        run.set(false)
        consumerThread.shutdownNow()
        consumerThread.awaitTermination(1, TimeUnit.SECONDS)
        consumer.close()
        // TODO: Can we avoid an NPE in the consumerRunnable?
        consumer = null
    }
}

object KafkaBus {
    private var consumerService: ConsumerService = _
    var refCount = 0

    private def consumerService(config: KafkaConfig,
                                owner: String): ConsumerService = {
        if (consumerService eq null) {
            consumerService = new ConsumerService(config, owner)
        } else {
            refCount += 1
        }
        consumerService
    }

    private[storage] def subscribe[K, V >: Null <: AnyRef]
        (config: KafkaConfig, owner: String, topic: String,
         observer: Observer[(K, V, String)],
         deserializer: Deserializer[(K, V, String)]): Unit = {

        this.synchronized {
            val service = consumerService(config, owner)
            service.subscribe(topic, observer, deserializer)
        }
    }

    private[storage] def unsubscribe(topic: String): Unit = {
        consumerService.unsubscribe(topic)

        this.synchronized {
            refCount -= 1
            if (refCount == 0) {
                consumerService.close()
                consumerService = null
            }
        }
    }
}

/**
 * A Kafka-based implementation of the MergedMapBus trait.
 *
 * An opinion for key K and value V from an owner O is published as a kafka
 * message with key K-O, and value K-V-O. By using Kafka's log compaction
 * feature we only keep the latest message for each kafka key in the log.
 * This means that we keep the latest opinion for each owner and key.
 * //TODO: Garbage collect opinions of owners that have left.
 */
class KafkaBus[K, V >: Null <: AnyRef](id: String, ownerId: String,
                                       config: KafkaConfig,
                                       zkClient: ZkClient,
                                       val kafkaIO: KafkaSerialization[K, V])
    (implicit crStrategy: Ordering[V]) extends MergedMapBus[K, V] {

    type Opinion = (K, V, String)
    type MsgsType = ConsumerRecords[String, Array[Byte]]
    type MsgType = ConsumerRecord[String, Array[Byte]]

    private val log =
        Logger(LoggerFactory.getLogger(getClass.getName + "-" + mapId.toString))

    private val opinionOutput = PublishSubject.create[Opinion]()
    private val opinionOutputBckPressure = opinionOutput.onBackpressureBuffer()
    private val opinionInput = PublishSubject.create[Opinion]()
    private val opinionInputBckPressure = opinionInput.onBackpressureBuffer()

    createTopicIfNeeded(config)
    KafkaBus.subscribe(config, ownerId, id, opinionOutput,
                       kafkaIO.messageDecoder)
    private val producer = createProducer(config)

    private val sendCallBack = new Callback() {
        def onCompletion(metadata: RecordMetadata, e: Exception): Unit = {
            if (e != null) {
                log.warn("Unable to send Kafka message", e)
            }
        }
    }

    def close(): Unit = {
        producer.close()
        KafkaBus.unsubscribe(id)
    }

    private val producerObserver = new Observer[Opinion] {
        override def onCompleted(): Unit = {
            log.info("Kafka bus output completed, shutting down.")
            close()
        }
        override def onError(e: Throwable): Unit = {
            log.warn("Error on output kafka bus, shutting down.", e)
            close()
        }
        override def onNext(opinion: Opinion): Unit = {
            val msgKey = kafkaIO.keyAsString(opinion._1) + "-" + ownerId
            val msg =
                new ProducerRecord[String, Opinion](mapId.toString /*topic*/,
                                                    0 /*partition*/,
                                                    msgKey /*key*/,
                                                    opinion /*value*/)
            producer.send(msg, sendCallBack)
        }
    }
    opinionInputBckPressure.observeOn(scheduler)
                           .subscribe(producerObserver)

    private def createTopicIfNeeded(config: KafkaConfig): Unit = {
        if (!AdminUtils.topicExists(zkClient, topic = mapId.toString)) {
            val props = new Properties()
            // Always keep the last message for each key in the log.
            props.put("cleanup.policy", "compact")
            // Minimum number of replicas that must be in-synch to allow
            // message publishing.
            props.put("min.insync.replicas",
                      computeMajority(config.replicationFactor).toString)
            // Only elect as leader brokers part of the insync set.
            props.put("unclean.leader.election.enable", "false")
            AdminUtils
                .createTopic(zkClient, topic = mapId.toString, partitions = 1,
                             config.replicationFactor, props)
        }
    }

    @VisibleForTesting
    private[storage] def computeMajority(replicaCount: Int): Int =
        Math.ceil((replicaCount + 1.0d) / 2.0d).asInstanceOf[Int]

    private def createProducer(config: KafkaConfig)
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

