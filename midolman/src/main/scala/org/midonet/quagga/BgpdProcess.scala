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

import java.util.UUID

import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.packets.{IPv4Subnet, MAC}
import org.midonet.util.process.ProcessHelper
import org.midonet.util.process.ProcessHelper.ProcessResult

trait BgpdProcess {
    def vty: BgpConnection
    def prepare(): Unit
    def start(): Unit
    def stop(): Boolean
    def isAlive: Boolean
    def addAddress(iface: String, ip: String, mac: String): Unit
    def removeAddress(iface: String, ip: String): Unit
    def addArpEntry(iface: String, ip: String, mac: String, peerIp: String): Unit
    def removeArpEntry(iface: String, ip: String, peerIp: String): Unit
}

case class DefaultBgpdProcess(portId: UUID,
                              routerId: UUID,
                              name: String,
                              index: Int,
                              localVtyIp: IPv4Subnet,
                              remoteVtyIp: IPv4Subnet,
                              routerIp: IPv4Subnet,
                              routerMac: MAC,
                              vtyPortNumber: Int,
                              bgpdHelperScript: String = "/usr/lib/midolman/bgpd-helper",
                              confFile: String = "/etc/midolman/quagga/bgpd.conf")
    extends BgpdProcess with MidolmanLogging {

    override def logSource = "org.midonet.routing.bgp.bgp-daemon"
    override def logMark = s"bgp:$portId:$index"

    override val vty = new BgpVtyConnection(portId, index,
                                            remoteVtyIp.getAddress.toString,
                                            vtyPortNumber)

    private var bgpdProcess: Process = _

    private val logDirectory: String = {
        var logDirectory = "/var/log/midolman"
        try {
            val prop = System.getProperty("midolman.log.dir")
            if (prop ne null)
                logDirectory = prop
        } catch {
            case _: Throwable => // ignored
        }
        logDirectory
    }

    private def logProcOutput(res: ProcessResult, f: (String) => Unit): Unit = {
        val it= res.consoleOutput.iterator()
        while (it.hasNext)
            f(it.next())
    }

    override def prepare(): Unit = {
        val cmd = s"$bgpdHelperScript prepare $name $index $localVtyIp " +
                  s"$remoteVtyIp $routerIp $routerMac"
        log.debug(s"BGP daemon command line: $cmd")
        val result = ProcessHelper.executeCommandLine(cmd, true)
        result.returnValue match {
            case 0 =>
                logProcOutput(result, s => log.debug(s))
                log.debug(s"Successfully prepared BGP daemon environment")
            case err =>
                logProcOutput(result, s => log.info(s))
                throw new Exception("Failed to prepare BGP daemon environment: " +
                                    s"exit status $err")
        }
    }

    override def start(): Unit = {
        val cmd = s"$bgpdHelperScript up $name $index $vtyPortNumber $confFile " +
                  s"$logDirectory ${routerId.toString.substring(0, 7)}"

        log.debug(s"Starting BGP daemon process VTY: $vtyPortNumber")
        log.debug(s"BGP daemon command line: $cmd")
        val daemonRunConfig = ProcessHelper.newDaemonProcess(cmd, log.underlying,
                                                             name)
        bgpdProcess = daemonRunConfig.run()

        if (isAlive) {
            try {
                Thread.sleep(100)
                connectVty()
                log.debug(s"BGP daemon started VTY: $vtyPortNumber")
            } catch {
                case e: Throwable =>
                    log.warn("BGP daemon started but VTY connection failed: " +
                             "aborting")
                    stop()
                    throw e
            }
        } else {
            stop()
            throw new Exception("BGP daemon subprocess failed to start")
        }
    }

    override def stop(): Boolean = {
        vty.close()

        val cmd = s"$bgpdHelperScript down $name $index"
        log.debug(s"BGP daemon command line: $cmd")
        val result = ProcessHelper.executeCommandLine(cmd, true)
        result.returnValue match {
            case 0 =>
                logProcOutput(result, s => log.debug(s))
                log.debug(s"BGP daemon stopped successfully")
                true
            case err =>
                logProcOutput(result, s => log.info(s))
                log.warn(s"BGP daemon failed to stop, exit status $err")
                false
        }
    }

    override def addAddress(iface: String, ip: String, mac: String): Unit = {
        val cmd = s"$bgpdHelperScript add_addr $name $index $iface $ip $mac"
        log.debug(s"BGP daemon command line: $cmd")
        val result = ProcessHelper.executeCommandLine(cmd, true)
        result.returnValue match {
            case 0 =>
                logProcOutput(result, s => log.debug(s))
                log.debug(s"Successfully added address $ip to $iface")
            case err =>
                logProcOutput(result, s => log.info(s))
                log.info(s"Failed to added address $ip to $iface")
        }
    }

    override def removeAddress(iface: String, ip: String): Unit = {
        val cmd = s"$bgpdHelperScript rem_addr $name $index $iface $ip"
        log.debug(s"BGP daemon command line: $cmd")
        val result = ProcessHelper.executeCommandLine(cmd, true)
        result.returnValue match {
            case 0 =>
                logProcOutput(result, s => log.debug(s))
                log.debug(s"Successfully removed address $ip from $iface")
            case err =>
                logProcOutput(result, s => log.info(s))
                log.info(s"Failed to remove address $ip from $iface")
        }
    }

    override def addArpEntry(iface: String, ip: String, mac: String,
                             peerIp: String): Unit = {
        val cmd = s"$bgpdHelperScript add_arp $name $index $iface $ip $mac " +
                  s"$peerIp"
        log.debug(s"bgpd command line: $cmd")
        val result = ProcessHelper.executeCommandLine(cmd, true)
        result.returnValue match {
            case 0 =>
                logProcOutput(result, s => log.debug(s))
                log.debug(s"Successfully added ARP entry $ip -> $mac to $iface")
            case err =>
                logProcOutput(result, s => log.info(s))
                log.info(s"Failed to add ARP entry $ip -> $mac to $iface")
        }
    }

    override def removeArpEntry(iface: String, ip: String, peerIp: String): Unit = {
        val cmd = s"$bgpdHelperScript rem_addr $name $index $iface $ip $peerIp"
        log.debug(s"bgpd command line: $cmd")
        val result = ProcessHelper.executeCommandLine(cmd, true)
        result.returnValue match {
            case 0 =>
                logProcOutput(result, s => log.debug(s))
                log.debug(s"Successfully removed ARP entry $ip from $iface")
            case err =>
                logProcOutput(result, s => log.info(s))
                log.info(s"Failed to remove ARP entry $ip from $iface")
        }
    }

    override def isAlive: Boolean = {
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

}
