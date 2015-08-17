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

package org.midonet.cluster.services.kafka

import java.io.File
import java.net.InetAddress
import java.util.Properties
import java.util.concurrent.Executors

import com.google.inject.Inject
import kafka.server.{KafkaConfig, KafkaServer, RunningAsBroker}
import org.slf4j.LoggerFactory

import org.midonet.cluster.ClusterNode.Context
import org.midonet.cluster.services.{ClusterService, Minion}
import org.midonet.cluster.storage.{KafkaConfig => BrokerConfig, MidonetBackendConfig}
import org.midonet.util.functors.makeRunnable

@ClusterService(name = "kafka")
class KafkaMinion @Inject() (nodeCtx: Context, conf: MidonetBackendConfig)
    extends Minion(nodeCtx) {

    private val log = LoggerFactory.getLogger("org.midonet.cluster.kafka")

    private var kafkaServer: KafkaServer = _
    private val ec = Executors.newSingleThreadExecutor()
    private val kafkaConf: BrokerConfig = conf.kafka

    override def isEnabled: Boolean = kafkaConf.useMergedMaps

    private def getPort: String = {
        val localIp = InetAddress.getLocalHost().getHostAddress
        val brokers = kafkaConf.brokers.split(",")
        val matchingFunc = (broker: String) => {
            broker.startsWith(localIp) || broker.startsWith("localhost") ||
            broker.startsWith("127.0.0.1")
        }

        val localBroker = brokers.filter(matchingFunc)
        if (localBroker.length == 1) {
            localBroker(0).split(":")(1)
        } else {
            throw new IllegalStateException(
                "Could not find local broker port in the configuration"
            )
        }
    }

    private def createDirs(): Unit = {
        val dataDir = new File(kafkaConf.dataDir)
        val logDirs = kafkaConf.logDirs.split(",").map(new File(_))

        try {
            var dirsPresent = true
            if (!dataDir.exists()) {
                log.debug(s"Creating data dir: ${dataDir.getAbsolutePath}")
                dirsPresent &= dataDir.mkdirs()
            }
            logDirs.filter(!_.exists()).foreach(logDir => {
                log.debug(s"Creating log dir: ${logDir.getAbsolutePath}")
                dirsPresent &= logDir.mkdirs()
            })
            if (!dirsPresent) {
                val msg = "Kafka data/log paths are not writable"
                log.error(msg)
                notifyFailed(new IllegalStateException(msg))
            }
        }  catch {
            case t: Throwable =>
                log.error("Cannot start Kafka service: data/log paths not " +
                          "created or incorrect configuration file.")
                notifyFailed(t)
        }
    }

    private val runEmbeddedKafka: Runnable = makeRunnable {
        log.info("This node will run embedded Kafka")
        createDirs()

        val props = new Properties()
        val hostName = InetAddress.getLocalHost.getHostName
        val port = getPort
        props.put("host.name", hostName)
        props.put("port", getPort)
        props.put("broker.id", kafkaConf.brokerId)
        props.put("log.dir", kafkaConf.dataDir)
        props.put("log.dirs", kafkaConf.logDirs)
        props.put("zookeeper.connect", kafkaConf.zkHosts)
        props.put("zookeeper.session.timeout.ms",
                  kafkaConf.zkSessionTimeout.toString)
        // Enabling log GC takes a lot of memory, set max. heap size to
        // at least 3GB if you enable this.
        props.put("log.cleaner.enable", "true")
        props.put("log.cleanup.policy", "compact")
        props.put("advertised.host.name", hostName)
        props.put("advertised.port", port)
        kafkaServer = new KafkaServer(new KafkaConfig(props))
        kafkaServer.startup()

        do {
            Thread.sleep(1000)
        } while (kafkaServer.brokerState.currentState != RunningAsBroker.state)
    }

    override def doStart(): Unit = ec.submit(runEmbeddedKafka)

    override def doStop(): Unit = {
        kafkaServer.shutdown()
        kafkaServer.awaitShutdown()
        ec.shutdown()
    }
}
