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
import java.util.concurrent.{ExecutorService, Executors}

import scala.collection.JavaConverters._

import com.typesafe.scalalogging.Logger
import kafka.admin.AdminUtils
import kafka.consumer.{ConsumerConfig, ConsumerIterator}
import kafka.javaapi.consumer.ConsumerConnector
import kafka.producer.{KeyedMessage, Producer, ProducerConfig}
import kafka.serializer.{Decoder, Encoder}
import org.I0Itec.zkclient.ZkClient
import org.slf4j.LoggerFactory
import rx.Observer
import rx.subjects.PublishSubject

import org.midonet.cluster.data.storage.MergedMap.MergedMapId
import org.midonet.cluster.storage.ReplicatedMapConfig
import org.midonet.util.functors.makeRunnable

trait KafkaSerialization[K, V >: Null <: AnyRef] {
    /**
     * Returns an encoder that is used to serialize Kafka message keys.
     */
    def keyEncoder: Encoder[K]

    /**
     * Returns a decoder that is used to deserialize Kafka message keys.
     */
    def keyDecoder: Decoder[K]

    /**
     * Returns an encoder that is used to serialize a (key, value, owner)
     * triple into an array of bytes.
     */
    def messageEncoder: Encoder[(K, V, String)]

    /**
     * Returns a decoder that is used to deserialize an array of bytes into
     * a (key, value, owner) triple.
     */
    def messageDecoder: Decoder[(K, V, String)]
}

/**
 * A highly-available implementation of the MergedMap trait. This class
 * relies on Kafka to publish opinion addition and removals.
 *
 * An opinion for key K and value V from an owner O is published as a kafka
 * message with key K, and value (V, O). By using Kafka's log compaction feature
 * we only keep the latest message for each key in the log. This may be
 * incompatible with the specified conflict resolution strategy but avoids
 * manual garbage collection of opinions.
 *
 * To keep the latest opinion of each owner, one could make the kafka key the
 * combination of the map key and the owner. This requires to perform
 * manual garbage collection when an owner leaves the system. This could be
 * done by inserting a null value for each key inserted by the owner that
 * just left.
 */
//TODO: Have just one consumer per agent and add topics gradually? That cannot be done...
//TODO: The consumer we use does not allow seeks and KafkaConsumer is not
//      completely implemented, wait for the next release?
class HAMergedMap[K, V >: Null <: AnyRef](id: MergedMapId, ownerId: String,
                                          config: ReplicatedMapConfig,
                                          zkClient: ZkClient,
                                          kafkaIO: KafkaSerialization[K, V])
    (implicit crStrategy: Ordering[V]) extends MergedMapStorage[K, V] {

    private val log =
        Logger(LoggerFactory.getLogger(getClass.getName + "-" + mapId.toString))

    private val inputSubj = PublishSubject.create[(K, V, String)]()
    private val outputSubj = PublishSubject.create[(K, V, String)]()

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

    /* The producer */
    private val producerObserver = new Observer[(K, V, String)] {
        override def onCompleted(): Unit =
            log.info("Output subject completed")
        override def onError(e: Throwable): Unit =
            log.warn("Error on output subject", e)
        override def onNext(opinion: (K, V, String)): Unit = {
            val msg =
                new KeyedMessage[K, (K, V, String)](topic = mapId.toString,
                                                    key = opinion._1,
                                                    message = opinion)
            producer.send(msg)
        }
    }
    outputSubj subscribe producerObserver

    private def createTopicIfNeeded(config: ReplicatedMapConfig): Unit = {
        if (!AdminUtils.topicExists(zkClient, topic = mapId.toString)) {
            val props = new Properties()
            // Always keep the last message for each key in the log.
            props.put("cleanup.policy", "compact")
            AdminUtils
                .createTopic(zkClient, topic = mapId.toString, partitions = 1,
                             config.replicationFactor, props)
        }
    }

    private def createConsumer(config: ReplicatedMapConfig)
    : ConsumerConnector = {
        val consProps = new Properties
        consProps.put("zookeeper.connect", config.zkHosts)
        consProps.put("bootstrap.servers", config.brokers)
        consProps.put("group.id", "group-" + owner)
        consProps.put("consumer.timeout.ms", "10000")
        consProps.put("fetch.wait.max.ms", "10000")
        // TODO: Configure the consumer such that it can read all messages in
        //       the topic to build the entire map.
        //       The commented parameter below does not work at the moment...
        // consProps.put("auto.offset.reset", "smallest")
        kafka.consumer.Consumer.createJavaConsumerConnector(
            new ConsumerConfig((consProps)))
    }

    private def createProducer(config: ReplicatedMapConfig)
    : Producer[K, (K, V, String)] = {
        val prodProps = new Properties()
        prodProps.put("metadata.broker.list", config.brokers)
        prodProps.put("serializer.class", kafkaIO.messageEncoder.getClass.getName)
        prodProps.put("key.serializer.class", kafkaIO.keyEncoder.getClass.getName)
        new Producer[K, (K, V, String)](new ProducerConfig(prodProps))
    }

    private def createConsumerIt(cons: ConsumerConnector)
    : ConsumerIterator[K, (K, V, String)] = {
        val topicCountMap = Map(mapId.toString -> Int.box(1) /* #partitions */)
                                .asJava
        val consumerMap = cons.createMessageStreams(topicCountMap,
                                                    kafkaIO.keyDecoder,
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

