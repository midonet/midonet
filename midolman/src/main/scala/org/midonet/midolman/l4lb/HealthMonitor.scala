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

package org.midonet.midolman.l4lb

import java.io._
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

import scala.collection.JavaConversions._
import scala.concurrent.duration.{Duration, _}
import scala.util.control.NonFatal

import akka.actor.{Actor, ActorRef, Props}

import com.google.inject.Inject

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.recipes.leader.{LeaderLatch, LeaderLatchListener}
import org.slf4j.{Logger, LoggerFactory}

import org.midonet.cluster.models.Topology.Pool
import org.midonet.cluster.models.Topology.Pool.PoolHealthMonitorMappingStatus._
import org.midonet.cluster.models.Topology.Pool.{PoolHealthMonitorMappingStatus => PoolHMMappingStatus}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.storage.MidonetBackendConfig
import org.midonet.conf.HostIdGenerator
import org.midonet.midolman.Referenceable
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.l4lb.HaproxyHealthMonitor._
import org.midonet.midolman.l4lb.HealthMonitorConfigWatcher.BecomeHaproxyNode
import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.util.{AwaitRetriable, DefaultRetriable}

object HealthMonitor extends Referenceable with DefaultRetriable
                             with AwaitRetriable {
    override val interval: Duration = 10 seconds
    override val maxRetries = 3
    override val Name = "HealthMonitor"

    case class ConfigUpdated(poolId: UUID, config: PoolConfig, routerId: UUID)
    case class ConfigDeleted(id: UUID)
    case class ConfigAdded(poolId: UUID, config: PoolConfig, routerId: UUID)
    case class RouterChanged(poolId: UUID, config: PoolConfig, routerId: UUID)

    var ipCommand = new IP()
    private final val lockOpNumber = new AtomicInteger(1)

    private val log: Logger
        = LoggerFactory.getLogger(classOf[HealthMonitor])

    def isRunningHaproxyPid(pid: Int, pidFilePath: String,
                            confFilePath: String): Boolean = {
        val cmdFilePath = "/proc/" + pid + "/cmdline"
        val cmdFile = new File(cmdFilePath)
        if (!cmdFile.exists())
            return false
        val fileReader = new FileReader(cmdFilePath)
        val bufReader = new BufferedReader(fileReader)
        var cmdLine: String = null
        try {
            cmdLine = bufReader.readLine()
            if (cmdLine == null)
                return false
        } catch {
            case ioe: IOException => return false
        }
        cmdLine = cmdLine.replace('\u0000', ' ')
        if (!cmdLine.startsWith("haproxy "))
            return false
        if (!cmdLine.contains(pidFilePath))
            return false
        if (!cmdLine.contains(confFilePath))
            return false

        true
    }

    def getHaproxyPid(pidFileLoc: String): Option[Int] = {
        try {
            val fileReader = new FileReader(pidFileLoc)
            val bufReader = new BufferedReader(fileReader)
            val pidString = bufReader.readLine()
            Some(pidString.toInt)
        } catch {
            case ioe: IOException =>
                log.info("Unable to get pid info from " + pidFileLoc)
                None
            case nfe: NumberFormatException =>
                log.error("The pid in " + pidFileLoc + " is malformed")
                None
        }
    }

    def killHaproxy(ns: String, pid: Int, pidFilePath: String,
                    confFilePath: String): Unit = {
        if (isRunningHaproxyPid(pid, pidFilePath, confFilePath)) {
            ipCommand.exec("kill -15 " + pid)
            if (!isRunningHaproxyPid(pid, pidFilePath, confFilePath))
                return
            Thread.sleep(200)
            if (!isRunningHaproxyPid(pid, pidFilePath, confFilePath))
                return
            Thread.sleep(5000)
            if (!isRunningHaproxyPid(pid, pidFilePath, confFilePath))
                return
            ipCommand.exec("kill -9 " + pid)
            Thread.sleep(200)
            if (!isRunningHaproxyPid(pid, pidFilePath, confFilePath))
                return
            log.error("Unable to kill haproxy process " + pid)
        } else {
            log.info("pid " + pid + " does not match up with contents " +
                      " of " + pidFilePath)
        }
    }

    /*
     * This is a little bit tricky. We need the haproxy pid, but all we have
     * is the namespace name. We can get it from the pid file, but only if no
     * one messed with the haproxy configs. We then have to make sure the pid
     * is valid and no one is trying to make us execute "kill -9 0".
     */
    def killHaproxyIfRunning(nsName: String, nsPostfix: String,
                             fileLoc: String): Unit = {
        val idPrefix = nsName.dropRight(nsPostfix.length)
        val l4lbFolder = new File(fileLoc)
        val nameSpaceFiles = l4lbFolder.listFiles(new FilenameFilter {
            def accept(p1: File, fileName: String): Boolean =
                fileName.startsWith(idPrefix)
            })
        if (nameSpaceFiles.length != 1) {
            // There should be exactly one. If there is 0, then the pid
            // path name doesn't exist. If there is more than 1, then
            // someone put another file in the directory and we can't tell
            // which is the correct pid file.
            return
        }

        val pidFilePath = fileLoc + nameSpaceFiles(0).getName + "/" +
                          PoolConfig.PID
        val confFilePath = fileLoc + nameSpaceFiles(0).getName + "/" +
                           PoolConfig.CONF

        // If we got this far, we have pid. Lets make sure it matches what
        // we expect in /proc/PID/cmdline

        val haproxyPid = getHaproxyPid(pidFilePath) match {
            case Some(pid) => pid
            case None => return
        }

        killHaproxy(nsName, haproxyPid, pidFilePath, confFilePath)
    }

    def deleteLink(ns: String) = {
        val ns_dev = ns + "_dp"
        if (ipCommand.interfaceExistsInNs(ns, ns_dev)) {
            ipCommand.exec("ip link delete " + ns_dev)
        }
    }

    def cleanAndDeleteNamespace(nsName: String, nsPostfix: String,
                                fileLoc: String): Unit = {
        if (ipCommand.namespaceExist(nsName)) {
            deleteLink(nsName)
            for (i <- 1 to 10) {
                killHaproxyIfRunning(nsName, nsPostfix, fileLoc)
                if (0 == ipCommand.deleteNS(nsName)) {
                    return
                } else {
                    Thread.sleep(200)
                }
            }
        }
    }

}

/*
 * parent class to manage the haproxy instances running on the system.
 * It accepts notifications that a config has been added, deleted, or changed,
 * then updates, creates, or destroys haproxy instances accordingly.
 */
class HealthMonitor @Inject() (config: MidolmanConfig,
                               backend: MidonetBackend,
                               curator: CuratorFramework,
                               backendCfg: MidonetBackendConfig)
    extends Actor with ActorLogWithoutPath {

    import HealthMonitor._

    val namespaceSuffix: String = "_hm"
    private var hostId: UUID = null
    val store = backend.store

    private var watcher: ActorRef = null

    val ipCom = HealthMonitor.ipCommand

    def getHostId = HostIdGenerator.getIdFromPropertiesFile

    private val hmLatchListener = new LeaderLatchListener {

        override def isLeader() = watcher ! BecomeHaproxyNode
        override def notLeader() = {
            // There is nothing to be done in this case.
            log.warn("HealthMonitor actor is no longer the leader.")
        }
    }

    def getWatcher = context.actorOf(HealthMonitorConfigWatcher.props(
            config.healthMonitor.haproxyFileLoc, namespaceSuffix, self)
            .withDispatcher(context.props.dispatcher))

    private def setPoolMappingStatus(poolId: UUID, status: PoolHMMappingStatus)
    : Unit = {
        try {
            store.tryTransaction { tx =>
                val pool = tx.get(classOf[Pool], poolId)
                tx.update(pool.toBuilder.setMappingStatus(status).build())
            }
        } catch {
            case NonFatal(e) =>
                log.error("Unable to set the mapping status for pool {}",
                          poolId, e)
        }
    }

    override def preStart(): Unit = {
        if (config.healthMonitor.namespaceCleanup) {
            cleanupNamespaces()
        }
        watcher = getWatcher
        log.info("Starting Health Monitor")
        hostId = getHostId
        if (curator == null) {
            watcher ! BecomeHaproxyNode
        } else {
            val hmLatch = new LeaderLatch(curator,
                                          config.zookeeper.rootKey +
                                          "/lb/hm-latch")
            hmLatch.addListener(hmLatchListener)
            hmLatch.start()
        }
    }

    def receive = {
        case ConfigUpdated(poolId, poolConf, routerId) =>
            context.child(poolId.toString) match {
                case Some(child) if !poolConf.isConfigurable =>
                    log.info("received unconfigurable update for pool {}",
                        poolId.toString)
                    context.stop(child)

                case Some(child) =>
                    log.info("received configurable update for pool {}",
                        poolId.toString)
                    child ! ConfigUpdate(poolConf)

                case None if poolConf.isConfigurable && routerId != null =>
                    log.info("received configurable update for non-existing" +
                             "pool {}", poolId.toString)
                    startChildHaproxyMonitor(poolId, poolConf, routerId)

                case _ =>
                    log.info("received unconfigurable update for non-existing" +
                             "pool {}", poolId.toString)
                    setPoolMappingStatus(poolId, INACTIVE)
            }

        case ConfigAdded(poolId, poolConfig, routerId) =>
            context.child(poolId.toString) match {
                case Some(child) => log.error("Request to add health monitor" +
                    "that already exists: " + poolId.toString)

                case None if !poolConfig.isConfigurable || routerId == null =>
                    log.info("received unconfigurable add for pool {}",
                        poolId.toString)
                    setPoolMappingStatus(poolId, INACTIVE)

                case None =>
                    log.info("received configurable add for pool {}",
                             poolId.toString)
                    startChildHaproxyMonitor(poolId, poolConfig, routerId)
            }

        case ConfigDeleted(poolId) =>
            context.child(poolId.toString) match {
                case Some(child) =>
                    log.info("received delete for pool {}",
                             poolId.toString)
                    context.stop(child)

                case None =>
                    log.info("received delete for non-existent pool {}",
                             poolId.toString)
                    setPoolMappingStatus(poolId, INACTIVE)
            }

        case RouterChanged(poolId, poolConfig, routerId) =>
            context.child(poolId.toString) match {
                case Some(child) if routerId == null =>
                    log.info("router removed for pool {}", poolId.toString)
                    child ! RouterRemoved

                case Some(child) =>
                    log.info("router added for pool {}", poolId.toString)
                    child ! RouterAdded(routerId)

                case None if poolConfig.isConfigurable && routerId != null =>
                    log.info("router added for non-existent pool {}",
                             poolId.toString)
                    startChildHaproxyMonitor(poolId, poolConfig, routerId)

                case _ =>
                    log.info("router changed for unconfigurable and non-" +
                             "existent pool {}", poolId.toString)
                    setPoolMappingStatus(poolId, INACTIVE)
            }

        case SetupFailure => context.stop(sender)

        case SockReadFailure => context.stop(sender)
    }

    def startChildHaproxyMonitor(poolId: UUID, config: PoolConfig,
                                 routerId: UUID) = {
        context.actorOf(
            Props(
                new HaproxyHealthMonitor(config, self, routerId, store, hostId)
            ).withDispatcher(context.props.dispatcher),
            config.id.toString)
    }

    def cleanupNamespaces() = {
        val namespaces = ipCom.execGetOutput("ip netns")
        /*
         * First kill all haproxy instances, then delete the namespaces.
         * We separate these steps due to a bug in the kernel that keeps
         * us from removing a namespace while a process is running in
         * an unrelated namespace:
         * https://bugs.launchpad.net/ubuntu/+source/iproute/+bug/1238981
         */

        // Kill all Haproxy Instances.
        namespaces.toSet
            .filter(ns => ns.endsWith(namespaceSuffix) &&
                                      ipCom.namespaceExist(ns))
            .foreach { ns =>
                HealthMonitor.killHaproxyIfRunning(ns, namespaceSuffix,
                                                   config.healthMonitor.haproxyFileLoc)
            }

        // unlink all links
        namespaces.toSet
            .filter(ns => ns.endsWith(namespaceSuffix) &&
                          ipCom.namespaceExist(ns))
            .foreach { ns =>
                HealthMonitor.deleteLink(ns)
            }

        // delete all namespaces
        namespaces.toSet
            .filter(ns => ns.endsWith(namespaceSuffix) &&
            ipCom.namespaceExist(ns))
            .foreach { ns =>
                var count = 0
                while (0 != ipCom.deleteNS(ns) && count < 10) {
                    Thread.sleep(200)
                    count +=1
                }

            }
    }
}

