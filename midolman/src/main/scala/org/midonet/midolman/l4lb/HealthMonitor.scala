package org.midonet.midolman.l4lb

import akka.actor.Actor
import com.google.inject.Inject
import java.io._
import java.util.UUID

import org.midonet.cluster.DataClient
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.host.config.HostConfig
import org.midonet.midolman.host.HostIdGenerator
import org.midonet.midolman.l4lb.HaproxyHealthMonitor.ConfigUpdate
import org.midonet.midolman.l4lb.HaproxyHealthMonitor.{SetupFailure,
                                                       SockReadFailure}
import org.midonet.midolman.l4lb.HealthMonitor.{ConfigAdded, ConfigDeleted,
                                                ConfigUpdated, RouterChanged}
import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.midolman.Referenceable
import org.midonet.midolman.routingprotocols.IP

import org.slf4j.{LoggerFactory, Logger}
import scala.collection.JavaConversions._
import scala.Some


object HealthMonitor extends Referenceable {
    override val Name = "HealthMonitor"
    case class ConfigUpdated(poolId: UUID, config: PoolConfig, routerId: UUID)
    case class ConfigDeleted(id: UUID)
    case class ConfigAdded(poolId: UUID, config: PoolConfig, routerId: UUID)
    case class RouterChanged(poolId: UUID, config: PoolConfig, routerId: UUID)

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
        cmdLine = cmdLine.replace('\0', ' ')
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
                log.error("Unable to get pid info from " + pidFileLoc)
                None
            case nfe: NumberFormatException =>
                log.error("The pid in " + pidFileLoc + " is malformed")
                None
        }
    }

    def killHaproxy(ns: String, pid: Int, pidFilePath: String,
                    confFilePath: String): Unit = {
        if (isRunningHaproxyPid(pid, pidFilePath, confFilePath)) {
            IP.exec("kill -15 " + pid)
            if (!isRunningHaproxyPid(pid, pidFilePath, confFilePath))
                return
            Thread.sleep(200)
            if (!isRunningHaproxyPid(pid, pidFilePath, confFilePath))
                return
            Thread.sleep(5000)
            if (!isRunningHaproxyPid(pid, pidFilePath, confFilePath))
                return
            IP.exec("kill -9 " + pid)
            Thread.sleep(200)
            if (!isRunningHaproxyPid(pid, pidFilePath, confFilePath))
                return
            log.error("Unable to kill haproxy process " + pid)
        } else {
            log.error("pid " + pid + " does not match up with contents " +
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
        if (IP.interfaceExistsInNs(ns, ns_dev)) {
            IP.exec("ip link delete " + ns_dev)
        }
    }

    def cleanAndDeleteNamespace(nsName: String, nsPostfix: String,
                              fileLoc: String): Unit = {
        if (IP.namespaceExist(nsName)) {
            deleteLink(nsName)
            for (i <- 1 to 10) {
                killHaproxyIfRunning(nsName, nsPostfix, fileLoc)
                if (0 == IP.deleteNS(nsName)) {
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
class HealthMonitor extends Actor with ActorLogWithoutPath {
    @Inject private val configuration: HostConfig = null
    @Inject private val midolmanConfig: MidolmanConfig = null
    @Inject private val client: DataClient = null

    private var fileLocation: String = null
    private var namespaceSuffix: String = null

    private var hostId: UUID = null

    override def preStart(): Unit = {

        fileLocation =  midolmanConfig.getHaproxyFileLoc
        namespaceSuffix = midolmanConfig.getNamespaceSuffix

        if (midolmanConfig.getNamespaceCleanup) {
            cleanupNamespaces()
        }
        if (!midolmanConfig.getHealthMonitorEnable) {
            context.stop(self)
            return
        }
        log.info("Starting Health Monitor")
        val hostPropertiesFile = configuration.getHostPropertiesFilePath
        hostId = HostIdGenerator.getIdFromPropertiesFile(hostPropertiesFile)
    }

    def receive = {
        case ConfigUpdated(poolId, config, routerId) =>
            context.child(poolId.toString) match {
                case Some(child) if !config.adminStateUp || routerId == null =>
                    context.stop(child)

                case Some(child) => child ! ConfigUpdate(config)

                case None if config.adminStateUp && routerId != null =>
                    startChildHaproxyMonitor(poolId, config, routerId)

                case None => log.info("Request to update config not " +
                                       "associated with child: " +
                                       poolId.toString)
            }

        case ConfigAdded(poolId, config, routerId) =>
            context.child(poolId.toString) match {
                case Some(child) => log.error("Request to add health monitor" +
                    "that already exists: " + poolId.toString)

                case None if !config.adminStateUp =>
                    // Wait until the admin state is up to start this.

                case None if routerId == null =>
                    // Wait until we get a new router to start this.

                case None => startChildHaproxyMonitor(poolId, config, routerId)
            }

        case ConfigDeleted(poolId) =>
            context.child(poolId.toString) match {
                case Some(child) => context.stop(child)
                case None => log.error("Request to delete config not" +
                                       "associated with child: " +
                                       poolId.toString)
            }

        case RouterChanged(poolId, config, routerId) =>
            context.child(poolId.toString) match {
                case Some(child) if routerId == null =>
                    context.stop(child)

                case Some(child) if config.adminStateUp =>
                    context.stop(child)
                    startChildHaproxyMonitor(poolId, config, routerId)

                case None if config.adminStateUp =>
                    startChildHaproxyMonitor(poolId, config, routerId)

                case None => log.info("Request to update config not " +
                        "associated with child: " + poolId.toString)

                case _ => // No other cases matter
            }

        case SetupFailure =>  context.stop(sender)

        case SockReadFailure => context.stop(sender)

    }

    def startChildHaproxyMonitor(poolId: UUID, config: PoolConfig,
                                 routerId: UUID) = {
        context.actorOf(HaproxyHealthMonitor.props(config, self, routerId,
            client, hostId).withDispatcher("actors.pinned-dispatcher"),
                 config.id.toString)
    }

    def cleanupNamespaces() = {
        val namespaces = IP.execGetOutput("ip netns")
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
                                      IP.namespaceExist(ns))
            .foreach { ns =>
                HealthMonitor.killHaproxyIfRunning(ns, namespaceSuffix,
                                                       fileLocation)
            }

        // unlink all links
        namespaces.toSet
            .filter(ns => ns.endsWith(namespaceSuffix) &&
                          IP.namespaceExist(ns))
            .foreach { ns =>
                HealthMonitor.deleteLink(ns)
            }

        // delete all namespaces
        namespaces.toSet
            .filter(ns => ns.endsWith(namespaceSuffix) &&
            IP.namespaceExist(ns))
            .foreach { ns =>
                var count = 0
                while (0 != IP.deleteNS(ns) && count < 10) {
                    Thread.sleep(200)
                    count +=1
                }

            }
    }
}
