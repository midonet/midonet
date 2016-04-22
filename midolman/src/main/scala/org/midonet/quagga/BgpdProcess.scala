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

package org.midonet.quagga

import org.slf4j.LoggerFactory

import org.midonet.packets.{IPv4Subnet, MAC}
import org.midonet.util.process.ProcessHelper
import org.midonet.util.process.ProcessHelper.ProcessResult

trait BgpdProcess {
    def vty: BgpConnection
    def prepare(iface: Option[String] = None): Unit
    def stop(): Boolean
    def isAlive: Boolean
    def start(): Unit
    def assignAddr(iface: String, ip: String): Unit
    def remAddr(iface: String, ip: String): Unit
    def addArpEntry(iface: String, ip: String, mac: String): Unit
    def remArpEntry(iface: String, ip: String): Unit
}

case class DefaultBgpdProcess(bgpIndex: Int, localVtyIp: IPv4Subnet, remoteVtyIp: IPv4Subnet,
                              routerIp: IPv4Subnet, routerMac: MAC, vtyPortNumber: Int,
                              bgpdHelperScript: String = "/usr/lib/midolman/bgpd-helper",
                              confFile: String = "/etc/midolman/quagga/bgpd.conf") extends BgpdProcess {

    private final val log = LoggerFactory.getLogger(s"org.midonet.routing.bgpd-helper-$bgpIndex")

    val vty = new BgpVtyConnection(remoteVtyIp.getAddress.toString, vtyPortNumber)

    private var bgpdProcess: Process = null

    private val LOGDIR: String = {
        var logdir = "/var/log/midolman"
        try {
            val prop = System.getProperty("midolman.log.dir")
            if (prop ne null)
                logdir = prop
        } catch {
            case _: Throwable => // ignored
        }
        logdir
    }

    private def logProcOutput(res: ProcessResult, f: (String) => Unit): Unit = {
        val it= res.consoleOutput.iterator()
        while (it.hasNext)
            f(it.next())
    }

    def assignAddr(iface: String, ip: String): Unit = {
        val cmd = s"$bgpdHelperScript add_addr $bgpIndex $iface $ip"
        val result = ProcessHelper.executeCommandLine(cmd, true)
        result.returnValue match {
            case 0 =>
                logProcOutput(result, log.debug)
                log.info(s"Successfully added address $ip to $iface bgpd-$bgpIndex")
            case err =>
                logProcOutput(result, log.info)
                throw new Exception(s"Failed to added address $ip to $iface bgpd-$bgpIndex")
        }
    }

    def remAddr(iface: String, ip: String): Unit = {
        val cmd = s"$bgpdHelperScript rem_addr $bgpIndex $iface $ip"
        val result = ProcessHelper.executeCommandLine(cmd, true)
        result.returnValue match {
            case 0 =>
                logProcOutput(result, log.debug)
                log.info(s"Successfully removed address $ip from $iface bgpd-$bgpIndex")
            case err =>
                logProcOutput(result, log.info)
                throw new Exception(s"Failed to remove address $ip from $iface bgpd-$bgpIndex")
        }
    }

    def addArpEntry(iface: String, ip: String, mac: String): Unit = {
        val cmd = s"$bgpdHelperScript add_arp $bgpIndex $iface $ip $mac"
        val result = ProcessHelper.executeCommandLine(cmd, true)
        result.returnValue match {
            case 0 =>
                logProcOutput(result, log.debug)
                log.info(s"Successfully added arp entry $ip -> $mac to $iface bgpd-$bgpIndex")
            case err =>
                logProcOutput(result, log.info)
                throw new Exception(s"Failed to add arp entry $ip -> $mac to $iface bgpd-$bgpIndex")
        }
    }

    def remArpEntry(iface: String, ip: String): Unit = {
        val cmd = s"$bgpdHelperScript rem_addr $bgpIndex $iface $ip"
        val result = ProcessHelper.executeCommandLine(cmd, true)
        result.returnValue match {
            case 0 =>
                logProcOutput(result, log.debug)
                log.info(s"Successfully removed arp entry $ip from $iface bgpd-$bgpIndex")
            case err =>
                logProcOutput(result, log.info)
                throw new Exception(s"Failed to remove arp entry $ip from $iface bgpd-$bgpIndex")
        }
    }

    def prepare(iface: Option[String] = None): Unit = {
        val ifaceOpt = iface.getOrElse("")
        val cmd = s"$bgpdHelperScript prepare $bgpIndex $localVtyIp " +
                  s"$remoteVtyIp $routerIp $routerMac $ifaceOpt"
        val result = ProcessHelper.executeCommandLine(cmd, true)
        result.returnValue match {
            case 0 =>
                logProcOutput(result, log.debug)
                log.info(s"Successfully prepared environment for bgpd-$bgpIndex")
            case err =>
                logProcOutput(result, log.info)
                throw new Exception(s"Failed to prepare environment for bgpd-$bgpIndex, exit status $err")
        }
    }

    def stop(): Boolean = {
        vty.close()

        val cmd = s"$bgpdHelperScript down $bgpIndex"
        val result = ProcessHelper.executeCommandLine(cmd, true)
        result.returnValue match {
            case 0 =>
                logProcOutput(result, log.debug)
                log.info(s"Successfully stopped bgpd-$bgpIndex")
                true
            case err =>
                logProcOutput(result, log.info)
                log.warn(s"Failed to stop bgpd-$bgpIndex, exit status $err")
                false
        }
    }

    def isAlive: Boolean = {
        if (bgpdProcess ne null) {
            try {
                bgpdProcess.exitValue()
                false
            } catch {
                case e: IllegalThreadStateException => true
            }
        } else {
            false
        }
    }

    private def connectVty(retries: Int = 10): Unit = {
        try {
            vty.open()
        } catch {
            case e: Exception if retries > 0 =>
                Thread.sleep(500)
                connectVty(retries - 1)
        }
    }

    def start(): Unit = {
        val cmd = s"$bgpdHelperScript up $bgpIndex $vtyPortNumber $confFile $LOGDIR"

        log.debug(s"Starting bgpd process. vty: $vtyPortNumber")
        log.debug(s"bgpd command line: $cmd")
        val daemonRunConfig =
            ProcessHelper.newDemonProcess(cmd, log, "bgpd-" + vtyPortNumber)
        bgpdProcess = daemonRunConfig.run()

        if (isAlive) {
            try {
                Thread.sleep(100)
                connectVty()
                log.info("bgpd started. Vty: {}", vtyPortNumber)
            } catch {
                case e: Throwable =>
                    log.warn("bgpd started but vty connection failed, aborting")
                    stop()
                    throw e
            }
        } else {
            stop()
            throw new Exception("bgpd subprocess failed to start")
        }
    }
}
