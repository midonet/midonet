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

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.{ConcurrentModificationException, Properties}

import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.util.control.NonFatal

import com.google.common.annotations.VisibleForTesting
import com.typesafe.scalalogging.Logger
import kafka.admin.AdminUtils
import org.I0Itec.zkclient.ZkClient
import org.apache.kafka.clients.consumer.{ConsumerRecord, ConsumerRecords, ConsumerWakeupException, KafkaConsumer}
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
 * The consumer polls messages from subscribed topics with a timeout
 * set to a constant named POLL_TIMEOUT_MS.
 *
 * This class is not thread-safe and does not allow concurrent calls
 * of methods accessible from outside of this class. Some of the data structures
 * must nevertheless be thread-safe because two threads can access them:
 * the consumer thread and the thread invoking a method from outside of this
 * class.
 */
private[storage] class MultiTopicConsumer(config: KafkaConfig, owner: String) {
    type MsgsType = ConsumerRecords[String, Array[Byte]]
    type MsgType = ConsumerRecord[String, Array[Byte]]
    type Opinion = (_, _ >: Null <: AnyRef, String)

    private val POLL_TIMEOUT_MS = 1000

    private val log =
        Logger(LoggerFactory.getLogger("org.midonet.cluster.kafka"))

    /**
     * A class that deserializes kafka messages received in arrays of bytes,
     * and publishes the resulting message to the corresponding
     * merged map observer.
     */
    private class TopicSubscriber[T <: Opinion](topic: String,
        observer: Observer[T], deserializer: Deserializer[T]) {

        def onMessage(msgInBytes: Array[Byte]): Unit = {
            val msg = deserializer.deserialize(topic, msgInBytes)
            observer.onNext(msg)
        }
        def close(): Unit = {
            observer.onCompleted()
        }
    }

    private val consumer = createConsumer(config, owner)
    private val observers = new TrieMap[String, TopicSubscriber[_ <: Opinion]]
    private val running = new AtomicBoolean(true)

    private val consumerRunnable = makeRunnable({
        do {
            while (running.get) {
                try {
                    var msgs = ConsumerRecords.empty[String, Array[Byte]]()
                    retryOnConcurrentModification {
                        msgs = consumer.poll(POLL_TIMEOUT_MS)
                    }
                    for (msg: MsgType <- msgs.asScala) {
                        val observer = observers(msg.topic)
                        observer.onMessage(msg.value)
                    }
                } catch {
                    case cwe: ConsumerWakeupException =>
                        log.debug("Kafka consumer woken up", cwe)
                    case NonFatal(e) =>
                        log.error("Kafka consumer caught exception", e)
                }
            }
            running.synchronized {
                while (!running.get) {
                    running.wait()
                }
            }
        } while (true)
    })
    private val consumerThread = Executors.newSingleThreadExecutor()
    consumerThread.submit(consumerRunnable)

    /**
     * Concurrent calls to the consumer throw a ConcurrentModificationException.
     * This method is used to retry calling a method on the consumer until
     * no concurrent invocations occurs.
     */
    private def retryOnConcurrentModification(f: => Unit)
    : Unit = {
        var success = false
        while (!success) {
            try {
                f
                success = true
            } catch {
                case e: ConcurrentModificationException =>
                    // Retry when a concurrent modification exception is raised.
            }
        }
    }

    private def createConsumer(config: KafkaConfig, owner: String)
    : KafkaConsumer[String, Array[Byte]] = {
        val consProps = new Properties
        consProps.put("zookeeper.connect", config.zkHosts)
        consProps.put("zookeeper.session.timeout",
                      config.zkSessionTimeout.toString)
        consProps.put("bootstrap.servers", config.brokers)
        consProps.put("group.id", "group-" + owner)
        consProps.put("key.deserializer", classOf[StringDeserializer].getName)
        consProps.put("value.deserializer",
                      classOf[ByteArrayDeserializer].getName)
        new KafkaConsumer[String, Array[Byte]](consProps)
    }

    /**
     * Subscribes the consumer to the given topic. This method should not be
     * called for a given topic if the consumer is already subscribed to
     * this topic.
     */
    private[storage] def subscribe[K, V >: Null <: AnyRef]
        (topic: String, observer: Observer[(K, V, String)],
         deserializer: Deserializer[(K, V, String)]): Unit = {

        if (!observers.contains(topic)) {
            val topicPartition = new TopicPartition(topic, 0 /*partition*/)
            observers.put(topic, new TopicSubscriber(topic, observer,
                                                     deserializer))
            retryOnConcurrentModification(consumer.subscribe(topicPartition))
            retryOnConcurrentModification(
                consumer.seekToBeginning(topicPartition)
            )
        } else {
            log.warn("Already subscribed to topic: {}", topic)
        }
    }

    /**
     * Unsubscribes the consumer from the given topic. This method should not
     * be called if the consumer is not subscribed to this topic.
     */
    private[storage] def unsubscribe(topic: String): Unit = {
        if (observers.contains(topic)) {
            retryOnConcurrentModification(
                consumer
                    .unsubscribe(new TopicPartition(topic, 0 /* partition */))
            )
            observers(topic).close()
            observers.remove(topic)
        } else {
            log.warn("Not subscribed to topic: {}", topic)
        }
    }

    private [storage] def startIfStopped(): Unit = {
        if (running.compareAndSet(false, true)) {
            running.synchronized {
                running.notify()
            }
        }
    }

    private[storage] def stop(): Unit = {
        if (running.compareAndSet(true, false)) {
            consumer.wakeup()
        }
    }

    @VisibleForTesting
    private[storage] def isStopped(): Boolean = !running.get()
}

/**
 * This object keeps a single reference to a multi-topic consumer and counts the
 * number of subscriptions to the consumer. The consumer is stopped when
 * the number of subscriptions reaches zero and it is started when needed.
 *
 * All calls to the consumer are synchronized, effectively allowing
 * a single thread to pass through.
 */
object KafkaBus {
    private var multiTopicConsumer: MultiTopicConsumer = _
    private var refCount = 0

    private def multiTopicConsumer(config: KafkaConfig,
                                   owner: String): MultiTopicConsumer = {
        if (refCount == 0) {
            multiTopicConsumer = new MultiTopicConsumer(config, owner)
        }
        refCount += 1
        multiTopicConsumer
    }

    /**
     * Subscribes to the given topic as the given owner and with the specified
     * configuration. Opinions received on the topic will deserialized using
     * the specified deserializer and, once deserialized, the opinion will
     * be notified on the provided observer.
     */
    private[storage] def subscribe[K, V >: Null <: AnyRef]
        (topic: String, owner: String, config: KafkaConfig,
         deserializer: Deserializer[(K, V, String)],
         observer: Observer[(K, V, String)]): Unit = {

        this.synchronized {
            val consumer = multiTopicConsumer(config, owner)
            consumer.subscribe(topic, observer, deserializer)
            consumer.startIfStopped()
        }
    }

    /**
     * Unsubscribes from the given topic. This method should not be called
     * if the multi-topic consumer is not subscribed to this topic.
     */
    private[storage] def unsubscribe(topic: String): Unit = {
        this.synchronized {
            multiTopicConsumer.unsubscribe(topic)
            refCount -= 1
            if (refCount == 0) {
                multiTopicConsumer.stop()
            }
        }
    }

    @VisibleForTesting
    private[storage] def isMultiTopicConsumerStopped: Boolean =
        multiTopicConsumer.isStopped()

    @VisibleForTesting
    private[storage] def multiTopicConsumerRefCount: Int = {
        this.synchronized{
            refCount
        }
    }
}

/**
 * A Kafka-based implementation of the MergedMapBus trait.
 * !!! WARNING !!!: It is assumed that at most one instance is created for a
 * given id. If you wish to create multiple instances of the same merged map
 * (i.e., merged maps with the same id), please re-use the corresponding kafka
 * bus.
 *
 * An opinion for key K and value V from an owner O is published as a kafka
 * message with key K-O, and value K-V-O. By using Kafka's log compaction
 * feature we only keep the latest message for each kafka key in the log.
 * This means that we keep the latest opinion for each owner and key.
 */
//TODO: Garbage collect opinions of owners that have left.
class KafkaBus[K, V >: Null <: AnyRef](id: String, ownerId: String,
                                       config: KafkaConfig,
                                       zkClient: ZkClient,
                                       val kafkaIO: KafkaSerialization[K, V])
    extends MergedMapBus[K, V] {

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
    KafkaBus.subscribe(id, ownerId, config, kafkaIO.messageDecoder,
                       opinionOutput)
    private val producer = createProducer(config)
    private val closed = new AtomicBoolean(false)

    private val sendCallBack = new Callback() {
        def onCompletion(metadata: RecordMetadata, e: Exception): Unit = {
            if (e != null) {
                log.error("Unable to send Kafka message", e)
            }
        }
    }

    override def close(): Unit = {
        if (closed.compareAndSet(false, true)) {
            producer.close()
            KafkaBus.unsubscribe(id)
            opinionOutput.onCompleted()
        }
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
            // Minimum number of replicas that must be in-sync to allow
            // message publishing.
            props.put("min.insync.replicas",
                      computeMajority(config.replicationFactor).toString)
            // Only elect as leader brokers part of the in-sync set.
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

