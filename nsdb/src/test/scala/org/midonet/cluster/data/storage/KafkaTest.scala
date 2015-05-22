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

import java.io.File
import java.util.Properties

import scala.util.Random

import com.typesafe.config.{Config, ConfigFactory}
import kafka.server.{RunningAsBroker, KafkaConfig, KafkaServer}
import org.apache.curator.test.TestingServer
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.common.serialization.{StringDeserializer, StringSerializer}
import org.scalatest._

import org.midonet.cluster.storage.{KafkaConfig => MidonetKafkaConfig}

/**
 * A trait that embeds a kafka broker as well as methods to create
 * producers and consumers for unit testing purposes.
 * The key class for kafka messages is String, and
 * the kafka message class is V.
 */
trait KafkaTest[V >: Null <: AnyRef] extends FeatureSpecLike
                                        with BeforeAndAfter
                                        with BeforeAndAfterAll
                                        with Matchers
                                        with GivenWhenThen {

    private val kafkaLogDir = "/tmp/embeddedkafka/"
    protected var zkServer: TestingServer = _
    protected var kafkaServer: KafkaServer = _
    protected var config: MidonetKafkaConfig = _
    private val kafkaBrokerPort = 9000 + Random.nextInt(1000)

    protected def fillConfig(config: Config = ConfigFactory.empty)
    : MidonetKafkaConfig = {
        val props = new Properties()
        props.put("kafka.brokers", "localhost:" + kafkaBrokerPort)
        props.put("kafka.zk_hosts", zkServer.getConnectString)
        props.put("kafka.replication_factor", "1")
        props.put("kafka.zk_session_timeout", "30000")
        new MidonetKafkaConfig(ConfigFactory.parseProperties(props))
    }

    override protected def beforeAll(): Unit = {}

    protected def setupKafka(config: MidonetKafkaConfig): Unit = {
        val props = new Properties()
        props.put("host.name", "localhost")
        props.put("port", kafkaBrokerPort.toString)
        props.put("broker.id", "0")
        props.put("log.dir", kafkaLogDir)
        props.put("zookeeper.connect", config.zkHosts)
        props.put("zookeeper.session.timeout.ms", "30000")
        // Enabling log GC takes a lot of memory, set max. heap size to
        // at least 3GB if you enable this.
        props.put("log.cleaner.enable", "false")
        props.put("log.cleanup.policy", "compact")
        props.put("advertised.host.name", "localhost")
        props.put("advertised.port", kafkaBrokerPort.toString)
        kafkaServer = new KafkaServer(new KafkaConfig(props))
        kafkaServer.startup()

        do {
            Thread.sleep(1000)
        } while (kafkaServer.brokerState.currentState != RunningAsBroker.state)
    }

    protected def createConsumer(config: MidonetKafkaConfig, groupId: String,
                                 deserializerClazz: String)
    : KafkaConsumer[String, V] = {
        val consProps = new Properties
        consProps.put("zookeeper.connect", config.zkHosts)
        consProps.put("zookeeper.session.timeout", "30000")
        consProps.put("bootstrap.servers", config.brokers)
        consProps.put("group.id", groupId)
        consProps.put("key.deserializer", classOf[StringDeserializer].getName)
        consProps.put("value.deserializer", deserializerClazz)
        new KafkaConsumer[String, V](consProps)
    }

    protected def createProducer(config: MidonetKafkaConfig,
                                 keyEncoderClass: String,
                                 messageEncoderClass: String)
    : KafkaProducer[String, V] = {
        val prodProps = new Properties()
        prodProps.put("bootstrap.servers", config.brokers)
        /* This specifies that the producer must receive an acknowledgment
           from min.insync.replicas (set when creating the topic) before
           considering the publishing successful. */
        prodProps.put("acks", "all")
        prodProps.put("key.serializer", classOf[StringSerializer].getName)
        prodProps.put("value.serializer", messageEncoderClass)
        new KafkaProducer[String, V](prodProps)
    }

    before {
        // This constructor starts the server and blocks until the
        // server is started.
        zkServer = new TestingServer
        config = fillConfig()

        setupKafka(config)
        setup()
    }

    after {
        teardown()
        closeKafka()
        zkServer.close()
    }

    protected def closeKafka(): Unit = {
        for (log <- kafkaServer.logManager.allLogs) {
            kafkaServer.logManager.deleteLog(log.topicAndPartition)
        }
        kafkaServer.shutdown()
        kafkaServer.awaitShutdown()
        for (fileName <- new File(kafkaLogDir).list) {
            new File(kafkaLogDir + "/" + fileName).delete()
        }
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
