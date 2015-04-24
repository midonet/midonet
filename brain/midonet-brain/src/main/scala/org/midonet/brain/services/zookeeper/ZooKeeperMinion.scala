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

package org.midonet.brain.services.zookeeper

import java.io.File
import java.net.InetSocketAddress

import com.google.inject.Inject
import org.apache.zookeeper.server.ZooKeeperServer.BasicDataTreeBuilder
import org.apache.zookeeper.server.persistence.FileTxnSnapLog
import org.apache.zookeeper.server.{NIOServerCnxnFactory, ZooKeeperServer}
import org.slf4j.LoggerFactory

import org.midonet.brain.{BrainConfig, ClusterMinion, ClusterNode}

class ZooKeeperMinion @Inject()(nodeCtx: ClusterNode.Context,
                                brainConf: BrainConfig)
    extends ClusterMinion(nodeCtx) {

    private val log = LoggerFactory.getLogger("org.midonet.cluster.zookeeper")

    private var zkServer: ZooKeeperServer = _
    private var standaloneServerFactory: NIOServerCnxnFactory = _

    private val clientPort = brainConf.zk.clientPort
    private val tickTime = brainConf.zk.tickTime
    private val numCnxns = brainConf.zk.numCnxns
    private val dataDir = new File(brainConf.zk.dataDir)
    private val logDir = new File(brainConf.zk.logDir)

    override def doStart(): Unit = {
        log.info("Starting...")
        try {
            dataDir.mkdirs()
        } catch {
            case t: Throwable =>
                log.error("Cannot start service: data file not writable?")
                notifyFailed(t)
        }
        val fileTxnLog = new FileTxnSnapLog(dataDir, logDir)
        zkServer = new ZooKeeperServer(fileTxnLog, tickTime,
                                       new BasicDataTreeBuilder)
        standaloneServerFactory = new NIOServerCnxnFactory
        standaloneServerFactory.configure(new InetSocketAddress(clientPort),
                                          numCnxns)
        standaloneServerFactory.startup(zkServer)
        notifyStarted()
    }

    override def doStop(): Unit = {
        log.info("Stopping...")
        standaloneServerFactory.shutdown()
        notifyStopped()
    }

}
