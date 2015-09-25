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

import scala.collection.JavaConverters._
import scala.util.Random
import scala.util.control.NonFatal

import org.apache.curator.framework.{CuratorFramework, CuratorFrameworkFactory}
import org.apache.curator.retry.ExponentialBackoffRetry

import org.midonet.cluster.storage.MidonetBackendConfig
import org.midonet.conf.MidoNodeConfigurator

object Utils extends JGroupsConstants {
    case class IPPort(addr: InetAddress, port: Int)

    private[jgroups] def zkPath(ip: String, port: Int): String =
        ZK_SERVER_ROOT + "/" + ip + ":" + port

    private def getZkClient(): CuratorFramework = {
        //TODO: The wrong configuration file seems to be picked up. Fix this.
        val conf =
            new MidonetBackendConfig(MidoNodeConfigurator.bootstrapConfig())
        val retryPolicy = new ExponentialBackoffRetry(conf.retryMs.toInt,
                                                      conf.maxRetries)
        CuratorFrameworkFactory.newClient(conf.hosts, retryPolicy)
    }

    /**
     * Writes the broker (ip, port) pair to ZooKeeper and returns true if the
     * operation was successful, and false otherwise.
     */
    private[jgroups] def writeBrokerToZK(addr: InetAddress, port: Int): Boolean = {

        val zkClient = getZkClient()
        val path = zkPath(addr.getHostAddress, port)

        var attempt = 1
        var success = false

        while (attempt <= MAX_ZK_ATTEMPTS && !success) {
            try {
                zkClient.create().creatingParentsIfNeeded().forPath(path)
                success = true
            } catch {
                case NonFatal(e) => attempt += 1
            }
        }
        zkClient.close()
        success
    }

    /**
     * Returns a list of (ip, port) pair that represent the list of brokers.
     * This list is retrieved from ZooKeeper.
     */
    private[jgroups] def readBrokersFromZK: List[IPPort] = {
        val zkClient = getZkClient()
        val children = zkClient.getZookeeperClient
                               .getZooKeeper
                               .getChildren(ZK_SERVER_ROOT, false /*watch*/)
                               .asScala

        zkClient.close()

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
