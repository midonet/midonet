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

case class DefaultQuaggaEnvironment(bgpIndex: Int, localVtyIp: IPv4Subnet,
                                    remoteVtyIp: IPv4Subnet,
                                    routerIp: IPv4Subnet, routerMac: MAC,
                                    vtyPortNumber: Int,
                                    bgpdHelperScript: String = "/usr/lib/midolman/bgpd-helper",
                                    bgpConfFile: String = "/etc/midolman/quagga/bgpd.conf",
                                    zebraConfFile: String = "/etc/midolman/quagga/zebra.conf")
        extends QuaggaEnvironment {

    private final val log = LoggerFactory.getLogger(s"org.midonet.routing.bgpd-helper-$bgpIndex")

    val bgpVty = new BgpVtyConnection(remoteVtyIp.getAddress.toString, vtyPortNumber)

    private var quaggaEnv: Process = null

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

    def prepare(): Unit = {
        val cmd = s"$bgpdHelperScript prepare $bgpIndex $localVtyIp $remoteVtyIp $routerIp $routerMac"
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
        bgpVty.close()

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
        if (quaggaEnv ne null) {
            try {
                quaggaEnv.exitValue()
                false
            } catch {
                case e: IllegalThreadStateException => true
            }
        } else {
            false
        }
    }

    def connectVty(retries: Int = 10): Unit = {
        try {
            bgpVty.open()
        } catch {
            case e: Exception if retries > 0 =>
                Thread.sleep(500)
                connectVty(retries - 1)
        }
    }

    def start(): Unit = {
        val cmd = s"$bgpdHelperScript quagga_up $bgpIndex $vtyPortNumber $localVtyIp $bgpConfFile $zebraConfFile $LOGDIR"

        log.debug(s"Starting quagga environment. bgpVty number: $vtyPortNumber")
        log.debug(s"quagga environment line: $cmd")
        val daemonRunConfig =
            ProcessHelper.newDemonProcess(cmd, log, "quagga-env-" + vtyPortNumber)
        quaggaEnv = daemonRunConfig.run()

        if (isAlive) {
            try {
                Thread.sleep(100)
                connectVty()
                log.info("quagga started. Vty: {}", vtyPortNumber)
            } catch {
                case e: Throwable =>
                    log.warn("quagga started but vty connection failed, aborting")
                    stop()
                    throw e
            }
        } else {
            stop()
            throw new Exception("quagga failed to start")
        }
    }
}
