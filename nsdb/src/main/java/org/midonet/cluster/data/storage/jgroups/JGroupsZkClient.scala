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

package org.midonet.cluster.data.storage.jgroups

import java.net.InetAddress

import org.apache.zookeeper.CreateMode
import org.midonet.conf.MidoNodeConfigurator
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.util.Random
import scala.util.control.NonFatal

import org.apache.curator.framework.{CuratorFramework, CuratorFrameworkFactory}
import org.apache.curator.retry.ExponentialBackoffRetry

import org.midonet.cluster.storage.{JGroupsConfig, MidonetBackendConfig}

object JGroupsZkClient {
    case class IPPort(addr: InetAddress, port: Int)
}

class JGroupsZkClient {

    import JGroupsZkClient._

    private val log = LoggerFactory.getLogger("org.midonet.cluster.storage.jgroups")

    val configurator = MidoNodeConfigurator.apply()
    if (!configurator.deployBundledConfig()) {
        log.info("Unable to deploy configuration into storage")
    }

    private val conf = configurator.runtimeConfig
    private[jgroups] val backendConf = new MidonetBackendConfig(conf)
    private[jgroups] val jgroupsConf = new JGroupsConfig(conf)
    private val zkClient = connectToZk()

    private def brokersRootPath: String = jgroupsConf.zkBrokersRoot
    private def brokerPath(ip: String, port: Int): String =
        brokersRootPath + "/" + ip + ":" + port

    private def connectToZk(): CuratorFramework = {
        val retryPolicy = new ExponentialBackoffRetry(backendConf.retryMs.toInt,
                                                      backendConf.maxRetries)
        val client = CuratorFrameworkFactory.newClient(backendConf.hosts, retryPolicy)
        client.start()
        client.getZookeeperClient.blockUntilConnectedOrTimedOut()
        client
    }

    private[jgroups] def clusterName: String = jgroupsConf.clusterName

    /**
     * Writes the broker (ip, port) pair to ZooKeeper and returns true if the
     * operation was successful, and false otherwise.
     */
    private[jgroups] def writeBrokerToZK(addr: InetAddress, port: Int): Boolean = {
        val path = brokerPath(addr.getHostAddress, port)

        var attempt = 1
        var success = false

        while (attempt <= backendConf.maxRetries && !success) {
            try {
                zkClient.create().creatingParentsIfNeeded()
                        .withMode(CreateMode.EPHEMERAL).forPath(path)
                success = true
            } catch {
                case NonFatal(e) => attempt += 1
            }
        }
        success
    }

    /**
     * Returns a list of (ip, port) pair that represent the list of brokers.
     * This list is retrieved from ZooKeeper.
     */
    private[jgroups] def readBrokersFromZK: List[IPPort] = {
        val path = brokersRootPath
        val children = zkClient.getZookeeperClient
                               .getZooKeeper
                               .getChildren(path, false /*watch*/)
                               .asScala

        children.map(child => {
            val tokens = child.split(":")
            IPPort(InetAddress.getByName(tokens(0)), tokens(1).toInt)
        }).toList
    }

    /**
     * Returns a randomly-selected broker server.
     */
    private[jgroups] def randomBroker: IPPort = {
        val brokers = readBrokersFromZK
        brokers(Random.nextInt(brokers.size))
    }
}
