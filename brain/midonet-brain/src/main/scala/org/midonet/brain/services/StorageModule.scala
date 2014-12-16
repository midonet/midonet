/*
 * Copyright 2014 Midokura SARL
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
package org.midonet.brain.services

import com.google.inject.AbstractModule
import org.apache.curator.framework.recipes.leader.LeaderLatch
import org.apache.curator.framework.{CuratorFramework, CuratorFrameworkFactory}
import org.apache.curator.retry.ExponentialBackoffRetry

import org.midonet.brain.services.c3po.C3POStorageManager
import org.midonet.cluster.data.storage.{Storage, ZookeeperObjectMapper}
import org.midonet.cluster.models.C3PO.C3POState
import org.midonet.cluster.models.Neutron.NeutronNetwork
import org.midonet.cluster.models.Topology.Network
import org.midonet.config.{ConfigGroup, ConfigInt, ConfigProvider, ConfigString}

class StorageModule(cfgProvider: ConfigProvider) extends AbstractModule {

    private val curatorCfg = cfgProvider.getConfig(classOf[CuratorConfig])
    private val curator = CuratorFrameworkFactory.newClient(
        curatorCfg.zkHosts, new ExponentialBackoffRetry(curatorCfg.baseRetryMs,
                                                        curatorCfg.maxRetries))
    curator.start()

    private val storage = initStorage(curator, curatorCfg.basePath)

    private val latchPath = "/midonet/c3po/latch"
    private val leaderLatch = new LeaderLatch(curator, latchPath)
    leaderLatch.start()

    override def configure(): Unit = {
        bind(classOf[CuratorFramework]).toInstance(curator)
        bind(classOf[LeaderLatch]).toInstance(leaderLatch)
        bind(classOf[Storage]).toInstance(storage)
    }

    def initStorage(curator: CuratorFramework, basePath: String): Storage = {
        val storage = new ZookeeperObjectMapper(basePath, curator)
        List(classOf[Network],
             classOf[NeutronNetwork],
             classOf[C3POState]).foreach(storage.registerClass)
        storage.build()
        storage
    }
}

@ConfigGroup("curator")
trait CuratorConfig {
    @ConfigString(key = "zookeeper_hosts", defaultValue = "127.0.0.1:2181")
    def zkHosts: String

    @ConfigString(key = "base_path")
    def basePath: String

    @ConfigInt(key = "base_retry_ms", defaultValue = 1000)
    def baseRetryMs: Int

    @ConfigInt(key = "max_retries", defaultValue = 10)
    def maxRetries: Int
}
