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

package org.midonet.cluster.services.zookeeper

import java.io.File
import java.net.{InetAddress, InetSocketAddress, NetworkInterface}
import java.util.concurrent.Executors

import com.google.inject.Inject
import org.apache.zookeeper.server.ZooKeeperServer.BasicDataTreeBuilder
import org.apache.zookeeper.server.persistence.FileTxnSnapLog
import org.apache.zookeeper.server.{NIOServerCnxnFactory, ZooKeeperServer}
import org.slf4j.LoggerFactory

import org.midonet.cluster.ClusterNode
import org.midonet.cluster.services.{ClusterService, Minion}
import org.midonet.cluster.storage.MidonetBackendConfig
import org.midonet.util.functors.makeRunnable

@ClusterService(name = "zookeeper")
class ZooKeeperMinion @Inject()(nodeCtx: ClusterNode.Context,
                                bootstrapConfig: MidonetBackendConfig)
    extends Minion(nodeCtx) {

    private val log = LoggerFactory.getLogger("org.midonet.cluster.zookeeper")

    private var zkServer: ZooKeeperServer = _
    private var serverFactory: NIOServerCnxnFactory = _

    private val clientPort = 2181
    private val tickTime = 1000
    private val numCnxns = 1000
    private val dataDir = new File(bootstrapConfig.dataDir)
    private val logDir = new File(bootstrapConfig.logDir)

    private val ec = Executors.newSingleThreadExecutor()

    override def doStart(): Unit = {
        bootstrapConfig.hosts.split(",") find { addr =>
            val ip = InetAddress.getByName(addr.split(":")(0))
            ip.isLoopbackAddress ||
            ip.isAnyLocalAddress ||
            NetworkInterface.getByInetAddress(ip) != null
        } match {
            case Some(s) => ec.submit(runEmbeddedZk)
            case None => doNothing()
        }
        notifyStarted()
    }

    private val runEmbeddedZk: Runnable = makeRunnable {
        log.info("This node will run embedded ZooKeeper")
        try {
            log.debug(s"Creating data dir: ${dataDir.getAbsolutePath} and " +
                      s" log dir: ${logDir.getAbsolutePath}")
            val dirsOk = logDir.mkdirs() && dataDir.mkdirs()
            if (!dirsOk) {
                val msg = "ZooKeeper data/log paths are not writable"
                log.error(msg)
                notifyFailed(new IllegalStateException(msg))
            }
        } catch {
            case t: Throwable =>
                log.error("Cannot start service: data/log paths not writable?")
                notifyFailed(t)
        }
        val fileTxnLog = new FileTxnSnapLog(dataDir, logDir)
        zkServer = new ZooKeeperServer(fileTxnLog, tickTime,
                                       new BasicDataTreeBuilder)
        serverFactory = new NIOServerCnxnFactory
        serverFactory.configure(new InetSocketAddress(clientPort), numCnxns)
        serverFactory.startup(zkServer)
        log.info("ZooKeeper node active")
    }

    private def doNothing(): Unit = {
        log.info("This node will use preconfigured ZooKeeper, " +
                 "ensemble, nodes at " + bootstrapConfig.hosts)
    }

    override def doStop(): Unit = {
        log.info("Stopping...")
        ec.shutdown()
        if (serverFactory != null) {
            serverFactory.shutdown()
        }
        notifyStopped()
    }

    override def isEnabled: Boolean = true
}
