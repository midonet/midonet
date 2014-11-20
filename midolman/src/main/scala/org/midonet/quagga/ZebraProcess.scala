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

import org.slf4j.LoggerFactory

import org.midonet.midolman.config.MidolmanConfig
import org.midonet.util.Waiters.sleepBecause
import org.midonet.util.process.ProcessHelper

class ZebraProcess(networkNamespace: String, zebraFpmPort: Int,
                   fpmServerIp: String, config: MidolmanConfig) {
    private final val log = LoggerFactory.getLogger(this.getClass)
    var zebraProcess: Process = null

    def start(): Boolean = {
        log.debug("Starting zebra process.")

        val zebraCmdLine = "ip netns exec " + networkNamespace +
            " " + config.pathToZebra + "/zebra " +
            " --fpm-port " + zebraFpmPort +
            " --fpm-address " + fpmServerIp +
            " --config_file " + config.pathToZebraConfig() + "/zebra.conf" +
            " --pid_file /var/run/quagga/zebra.pid"

        log.debug("zebra command line: {}", zebraCmdLine)

        val daemonRunConfig =
            ProcessHelper.newDemonProcess(zebraCmdLine, log, "zebra")

        zebraProcess = daemonRunConfig.run()

        sleepBecause("we need zebra to boot up", 5)

        if (zebraProcess != null) {
            log.debug("bgpd process started.")
            true
        } else {
            log.debug("bgpdProcess is null, won't sent BGPD_READY")
            false
        }

    }

    def stop() {
        log.debug("Stopping bgpd process.")

        if (zebraProcess != null)
            zebraProcess.destroy()
        else
            log.warn("Couldn't kill zebra because it wasn't started")

        log.debug("zebra process stopped.")
    }
}

