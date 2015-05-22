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

import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.util.Random

import com.typesafe.config.{ConfigFactory, Config}
import kafka.consumer.ConsumerConfig
import kafka.javaapi.consumer.ConsumerConnector
import kafka.producer.{Producer, ProducerConfig}
import kafka.serializer.{Decoder, Encoder}
import kafka.server.{KafkaConfig, KafkaServer}
import org.apache.curator.test.TestingServer
import org.scalatest._

import org.midonet.cluster.storage.ReplicatedMapConfig

/**
 * A trait that embeds a kafka broker as well as methods to create
 * producers and consumers for unit testing purposes.
 * The key class for kafka messages is assumed to be K, and
 * the kafka message class is V.
 */
trait KafkaTest[K, V >: Null <: AnyRef] extends FeatureSpecLike
                                        with BeforeAndAfter
                                        with BeforeAndAfterAll
                                        with Matchers
                                        with GivenWhenThen {

    protected var zkServer: TestingServer = _
    protected var kafkaServer: KafkaServer = _
    protected var config: ReplicatedMapConfig = _
    private val kafkaBrokerPort = 9000 + Random.nextInt(1000)

    protected def fillConfig(config: Config = ConfigFactory.empty)
    : ReplicatedMapConfig = {
        val props = new Properties()
        props.put("kafka.brokers", "localhost:" + kafkaBrokerPort)
        props.put("kafka.zk.hosts", zkServer.getConnectString)
        props.put("kafka.replication.factor", "1")
        new ReplicatedMapConfig(ConfigFactory.parseProperties(props))
    }

    override protected def beforeAll(): Unit = {
        // This constructor starts the server and blocks until the
        // server is started.
        zkServer = new TestingServer
        config = fillConfig()
    }

    protected def setupKafka(config: ReplicatedMapConfig): Unit = {
        val props = new Properties()
        props.put("host.name", "localhost")
        props.put("port", kafkaBrokerPort.toString)
        props.put("broker.id", "0")
        props.put("log.dir", "/tmp/embeddedkafka/")
        props.put("zookeeper.connect", config.zkHosts)
        // TODO: provide config parameters for these
        props.put("zookeeper.session.timeout.ms", "20000")
        props.put("zookeeper.connection.timeout.ms", "20000")
        props.put("log.cleaner.enable", "true")
        kafkaServer = new KafkaServer(new KafkaConfig(props))
        kafkaServer.startup()

        Thread.sleep(2000)
        kafkaServer.zkClient.waitUntilConnected()
    }

    protected def createConsumer(config: ReplicatedMapConfig, groupId: String)
    : ConsumerConnector = {
        val consProps = new Properties
        consProps.put("zookeeper.connect", config.zkHosts)
        consProps.put("bootstrap.servers", config.brokers)
        consProps.put("group.id", groupId)
        consProps.put("consumer.timeout.ms", "10000")
        consProps.put("fetch.wait.max.ms", "10000")
        kafka.consumer.Consumer.createJavaConsumerConnector(
            new ConsumerConfig((consProps)))
    }

    protected def createProducer(config: ReplicatedMapConfig,
                                 keyEncoderClass: String,
                                 messageEncoderClass: String)
    : Producer[K, V] = {
        val prodProps = new Properties()
        prodProps.put("metadata.broker.list", config.brokers)
        prodProps.put("serializer.class", messageEncoderClass)
        prodProps.put("key.serializer.class", keyEncoderClass)
        new Producer[K, V](new ProducerConfig(prodProps))
    }

    protected def awaitTopicCreation(topic: String, partition: Int,
                                   timeout: Duration): Unit =
        awaitCond(
            kafkaServer.apis.metadataCache.getPartitionInfo(topic, partition)
                .exists(_.leaderIsrAndControllerEpoch.leaderAndIsr.leader >= 0),
            max = timeout,
            message = s"Partition [$topic, $partition] metadata not propagated after timeout"
        )

    /**
     * Await until the given condition evaluates to `true` or the timeout
     * expires, whichever comes first.
     * If no timeout is given, take it from the innermost enclosing `within`
     * block.
     */
    private def awaitCond(p: => Boolean, max: Duration = 3 seconds,
                          interval: Duration = 100 millis, message: String = "") {
        /** Obtain current time (`System.nanoTime`) as Duration. */
        def now: FiniteDuration = System.nanoTime.nanos

        val stop = now + max

        @tailrec
        def poll(t: Duration) {
            if (!p) {
                assert(now < stop, s"timeout ${max} expired: $message")
                Thread.sleep(t.toMillis)
                poll((stop - now) min interval)
            }
        }
        poll(max min interval)
    }

    before {
        setupKafka(config)
        setup()
    }

    after {
        closeKafka()
        teardown()
    }

    protected def closeKafka(): Unit = {
        kafkaServer.shutdown()
        kafkaServer.awaitShutdown()
    }

    override protected def afterAll(): Unit = {
        zkServer.close()
    }

    /**
     * Override this function to perform a custom set-up needed for the test.
     */
    protected def setup(): Unit = {}

    /**
     * Override this function to perform a custom shut-down operations needed
     * for the test.
     */
    protected def teardown(): Unit = {}
}
