/*
 * Copyright 2016 Midokura SARL
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
import java.util.concurrent.{ExecutorService, Executors, ScheduledExecutorService, TimeUnit}

import scala.collection.JavaConversions._
import scala.collection.concurrent.TrieMap
import scala.util.control.NonFatal

import com.google.common.util.concurrent.AbstractService
import com.google.inject.Inject
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.recipes.leader.{LeaderLatch, LeaderLatchListener}
import org.slf4j.{Logger, LoggerFactory}
import rx.schedulers.Schedulers
import rx.{Observable, Observer, Scheduler, Subscription}

import org.midonet.cluster.ZookeeperLockFactory
import org.midonet.cluster.data.util.ZkOpLock
import org.midonet.cluster.models.Topology.Pool
import org.midonet.cluster.models.Topology.Pool.PoolHealthMonitorMappingStatus._
import org.midonet.cluster.models.Topology.Pool.{PoolHealthMonitorMappingStatus => PoolHMMappingStatus}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.storage.MidonetBackendConfig
import org.midonet.cluster.util.SequenceDispenser
import org.midonet.conf.HostIdGenerator
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.util.concurrent.{NamedThreadFactory, toFutureOps}
import org.midonet.util.functors.makeRunnable

object HealthMonitor {
    trait HealthMonitorMessage
    case class ConfigUpdated(poolId: UUID, config: PoolConfig, routerId: UUID)
        extends HealthMonitorMessage
    case class ConfigDeleted(id: UUID) extends HealthMonitorMessage
    case class ConfigAdded(poolId: UUID, config: PoolConfig, routerId: UUID)
        extends HealthMonitorMessage
    case class RouterChanged(poolId: UUID, config: PoolConfig, routerId: UUID)
        extends HealthMonitorMessage

    var ipCommand = new IP()
    private final val lockOpNumber = new AtomicInteger(1)

    private val log: Logger
        = LoggerFactory.getLogger("org.midonet.haproxy")

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
            log.info("PID " + pid + " does not match up with contents " +
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

    // TODO: Move this functionality to the cluster and make it more generic
    //       (mna-1054).
    private[l4lb] def zkLock(lockFactory: ZookeeperLockFactory)
                            (f: => Unit) : Unit = {
        val lock = new ZkOpLock(lockFactory, lockOpNumber.getAndIncrement,
                                ZookeeperLockFactory.ZOOM_TOPOLOGY)
        try lock.acquire() catch {
            case NonFatal(e) =>
                log.info("Could not acquire exclusive write access to " +
                         "storage.", e)
                throw e
        }

        try {
            f
        } finally {
            lock.release()
        }
    }
}

/**
  * Parent class to manage the haproxy instances running on the system.
  * It accepts notifications that a config has been added, deleted, or changed,
  * then updates, creates, or destroys haproxy instances accordingly.
  */
class HealthMonitor @Inject() (config: MidolmanConfig,
                               backend: MidonetBackend,
                               lockFactory: ZookeeperLockFactory,
                               curator: CuratorFramework,
                               backendCfg: MidonetBackendConfig)
    extends AbstractService {

    import HealthMonitor._

    protected val namespaceSuffix: String = "_hm"
    private var hostId: UUID = null
    private val store = backend.store
    private val timeoutInSec = 10
    private val checkHealthPeriodInSec = 1
    private val haProxies = new TrieMap[UUID, HaproxyHealthMonitor]()

    private val seqDispenser = new SequenceDispenser(curator, backendCfg)

    protected var watcher: HealthMonitorConfigWatcher = _
    private var executor: ScheduledExecutorService = _
    private var scheduler: Scheduler = _
    private var subscription: Subscription = _

    val ipCom = HealthMonitor.ipCommand

    def getHostId = HostIdGenerator.getIdFromPropertiesFile

    private val hmLatchListener = new LeaderLatchListener {
        override def isLeader() = watcher.becomeHaproxyNode()
        override def notLeader() = {
            // There is nothing to be done in this case.
            log.info("HealthMonitor service is no longer the leader.")
        }
    }

    private var hmLatch: LeaderLatch = null

    private def setPoolMappingStatus(poolId: UUID, status: PoolHMMappingStatus)
    : Unit = {
        try {
            HealthMonitor.zkLock(lockFactory) {
                val pool = store.get(classOf[Pool], poolId).await()
                store.update(pool.toBuilder.setMappingStatus(status).build())
            }
        } catch {
            case NonFatal(e) =>
                log.warn(s"Unable to set the mapping status for pool $poolId", e)
        }
    }

    private def stopHealthMonitor(): Unit = {
        if (subscription ne null) {
            subscription.unsubscribe()
            stopAsync().awaitTerminated(timeoutInSec, TimeUnit.SECONDS)
        }
    }

    protected def getWatcher(config: MidolmanConfig, executor: ExecutorService)
    : HealthMonitorConfigWatcher =
        new HealthMonitorConfigWatcher(config.healthMonitor.haproxyFileLoc,
                                       namespaceSuffix, executor)

    override def doStart(): Unit = {
        log.info("Starting Health Monitor")

        executor = Executors.newSingleThreadScheduledExecutor(
            new NamedThreadFactory("HaProxy", isDaemon = true))
        scheduler = Schedulers.from(executor)
        watcher = getWatcher(config, executor)

        val observer = new Observer[HealthMonitorMessage]() {
            override def onCompleted(): Unit = {
                log.warn("Health monitor configuration stream completed " +
                         "unexpectedly, stopping the health monitor")
                stopHealthMonitor()
            }
            override def onError(e: Throwable): Unit = {
                log.warn("Health monitor configuration stream experienced " +
                         "an error, stopping the health monitor", e)
                stopHealthMonitor()
            }
            override def onNext(msg: HealthMonitorMessage): Unit =
                handleUpdate(msg)
        }

        executor.submit(makeRunnable {
            try {
                if (config.healthMonitor.namespaceCleanup) {
                    cleanupNamespaces()
                }
                hostId = getHostId
                subscription = Observable.create(watcher).subscribe(observer)
                hmLatch = new LeaderLatch(curator, config.zookeeper.rootKey +
                                                   "/lb/hm-latch")
                hmLatch.addListener(hmLatchListener)
                hmLatch.start()
                notifyStarted()
            } catch {
                case NonFatal(e) =>
                    log.warn("Unable to start the health monitor")
                    notifyFailed(e)
            }
        })
    }

    override def doStop(): Unit = {
        executor.submit(makeRunnable {
            subscription.unsubscribe()
            haProxies.keySet.foreach(stopHaProxy)

            try {
                hmLatch.close()
                executor.shutdown()
            } catch {
                case NonFatal(e) =>
                    log.warn("Unable to properly stop the health monitor", e)

            } finally {
                notifyStopped()
            }
        })
    }

    private def checkHaProxyHealth(poolId: UUID): Unit = {
        haProxies.get(poolId) match {
            case Some(haProxy) =>
                if (!haProxy.checkHealthAndSetMemberStatus()) {
                    stopHaProxy(poolId)
                } else {
                    executor.schedule(makeRunnable(checkHaProxyHealth(poolId)),
                                      checkHealthPeriodInSec,
                                      TimeUnit.SECONDS)
                }
            case None =>
        }
    }

    protected def getHaProxy(config: PoolConfig, routerId: UUID)
    : HaproxyHealthMonitor =
        new HaproxyHealthMonitor(config, routerId, store, hostId, lockFactory,
                                 seqDispenser)

    protected def startHaProxy(poolId: UUID, config: PoolConfig,
                              routerId: UUID) = {
        val haProxy = getHaProxy(config, routerId)
        try {
            haProxy.startAsync().awaitRunning(timeoutInSec, TimeUnit.SECONDS)
            haProxies.put(poolId, haProxy)
            executor.schedule(makeRunnable(checkHaProxyHealth(poolId)),
                              checkHealthPeriodInSec,
                              TimeUnit.SECONDS)
        } catch {
            case NonFatal(e) => stopHaProxy(poolId)
        }
    }

    private def stopHaProxy(poolId: UUID): Unit = {
        try {
            val haProxy = haProxies.remove(poolId).foreach(
                _.stopAsync.awaitTerminated(timeoutInSec, TimeUnit.SECONDS))
        } catch {
            case NonFatal(e) =>
                log.info(s"Unable to stop health monitor proxy for pool: $poolId")
        }
    }

    private def handleUpdate(update: AnyRef): Unit = update match {
        case ConfigUpdated(poolId, poolConf, routerId) =>
            haProxies.get(poolId) match {
                case Some(haProxy) if !poolConf.isConfigurable =>
                    log.info("Received unconfigurable update for pool {}",
                             poolId.toString)
                    stopHaProxy(poolId)
                case Some(haProxy) =>
                    log.info("Received configurable update for pool {}",
                             poolId.toString)
                    if (!haProxy.updateConfig(poolConf)) {
                        stopHaProxy(poolId)
                    }
                case None if poolConf.isConfigurable && routerId != null =>
                    log.info("Received configurable update for non-existing" +
                             "pool {}", poolId.toString)
                    startHaProxy(poolId, poolConf, routerId)
                case None =>
                    log.info("Received unconfigurable update for non-existing" +
                             "pool {}", poolId.toString)
                    setPoolMappingStatus(poolId, INACTIVE)
            }

        case ConfigAdded(poolId, poolConfig, routerId) =>
            haProxies.get(poolId) match {
                case Some(haProxy) =>
                    log.error("Request to add health monitor" +
                              "that already exists: " + poolId.toString)
                case None if !poolConfig.isConfigurable || routerId == null =>
                    log.info("Received unconfigurable add for pool {}",
                             poolId.toString)
                    setPoolMappingStatus(poolId, INACTIVE)
                case None =>
                    log.info("Received configurable add for pool {}",
                             poolId.toString)
                    startHaProxy(poolId, poolConfig, routerId)
            }

        case ConfigDeleted(poolId) =>
            haProxies.get(poolId) match {
                case Some(haProxy) =>
                    log.info("Received delete for pool {}",
                             poolId.toString)
                    stopHaProxy(poolId)
                case None =>
                    log.info("Received delete for non-existent pool {}",
                             poolId.toString)
                    setPoolMappingStatus(poolId, INACTIVE)
            }

        case RouterChanged(poolId, poolConfig, routerId) =>
            haProxies.get(poolId) match {
                case Some(haProxy) if routerId == null =>
                    log.info("Router removed for pool {}", poolId.toString)
                    haProxy.removeRouter()

                case Some(haProxy) =>
                    log.info("Router added for pool {}", poolId.toString)
                    haProxy.newRouter(routerId)

                case None if poolConfig.isConfigurable && routerId != null =>
                    log.info("Router added for non-existent pool {}",
                             poolId.toString)
                    startHaProxy(poolId, poolConfig, routerId)
                case None =>
                    log.info("Router changed for unconfigurable and non-" +
                             "existent pool {}", poolId.toString)
                    setPoolMappingStatus(poolId, INACTIVE)
            }
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

