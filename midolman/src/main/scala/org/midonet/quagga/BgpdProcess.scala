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

package org.midonet.quagga

import java.util.concurrent.TimeUnit

import org.midonet.packets.{IPv4Subnet, MAC}
import org.slf4j.LoggerFactory

import org.midonet.midolman.config.MidolmanConfig
import org.midonet.util.process.ProcessHelper

case class BgpdProcess(bgpIndex: Int, localVtyIp: IPv4Subnet, remoteVtyIp: IPv4Subnet,
                       routerIp: IPv4Subnet, routerMac: MAC, vtyPortNumber: Int,
                       config: MidolmanConfig) {
    private final val log = LoggerFactory.getLogger(s"org.midonet.routing.bgpd-helper-$bgpIndex")

    val BGPD_HELPER = "/usr/lib/midolman/bgpd-helper"

    var bgpdProcess: Process = null

    def prepare(): Boolean = {
        val cmd = s"$BGPD_HELPER prepare $bgpIndex $localVtyIp $remoteVtyIp $routerIp $routerMac"
        ProcessHelper.executeCommandLine(cmd).returnValue match {
            case 0 =>
                log.info(s"Successfully prepared environment for bgpd-$bgpIndex")
                true
            case err =>
                log.warn(s"Failed to prepare environment for bgpd-$bgpIndex, exit status $err")
                false
        }
    }

    def stop(): Boolean = {
        val cmd = s"$BGPD_HELPER down $bgpIndex"
        ProcessHelper.executeCommandLine(cmd).returnValue match {
            case 0 =>
                log.info(s"Successfully stopped bgpd-$bgpIndex")
                true
            case err =>
                log.warn(s"Failed to stop bgpd-$bgpIndex, exit status $err")
                false
        }
    }

    def start(): Boolean = {
        val cmd = s"$BGPD_HELPER up $bgpIndex $vtyPortNumber ${config.bgpdConfigPath}"

        log.debug(s"Starting bgpd process. vty: $vtyPortNumber")
        log.debug(s"bgpd command line: $cmd")
        val daemonRunConfig =
            ProcessHelper.newDemonProcess(cmd, log, "bgpd-" + vtyPortNumber)
        bgpdProcess = daemonRunConfig.run()

        log.debug("Sleeping 5 seconds because we need bgpd to boot up")
        TimeUnit.SECONDS.sleep(5)
        if ((bgpdProcess ne null) && bgpdProcess.isAlive) {
            log.debug("bgpd process started. Vty: {}", vtyPortNumber)
            true
        } else {
            log.warn("bgpd process is failed to start")
            false
        }
    }
}

