/*
 * Copyright 2017 Midokura SARL
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
package org.midonet.conf

import scala.util.control.NonFatal

import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger

import org.slf4j.LoggerFactory

import rx.Observer

import org.midonet.conf.AgentFastRebootWatcher.{Log, RebootLog}
import org.midonet.util.process.MonitoredDaemonProcess

object AgentFastRebootConfig {

    def apply(config: Config, prefix: String): Option[Boolean] = {
        try {
            val enabled = config.getBoolean(s"$prefix.midolman.fast_reboot")
            Option(enabled)
        } catch {
            case NonFatal(_) => None
        }
    }

}

object AgentFastRebootWatcher {

    val Log = Logger(LoggerFactory.getLogger("org.midonet.config"))

    val RebootLog = LoggerFactory.getLogger("org.midonet.fast_reboot")

}

class AgentFastRebootWatcher(prefix: String,
                             process: MonitoredDaemonProcess) extends Observer[Config] {

    private[conf] var config: Option[Boolean] = None

    override def onNext(rawConfig: Config): Unit = {
        try {
            val newConfig = AgentFastRebootConfig(rawConfig, prefix)
            (config, newConfig) match {
                case (None, Some(reboot)) =>
                    if (reboot) {
                        throw new Exception(
                            "Unexpected fast reboot config on startup. " +
                            "Set it to false before starting the agent.")
                    }
                    config = newConfig
                case (Some(previousReboot), Some(reboot)) if !previousReboot && reboot =>
                    RebootLog info "Fast reboot started."
                    doFirstStageFastReboot(process)
                    config = newConfig
                case (Some(previousReboot), Some(reboot)) if previousReboot == reboot =>
                    RebootLog debug "Fast reboot config did not change: do nothing."
                case _ =>
                    RebootLog debug "Unexpected fast reboot transition, ignoring."
            }
        } catch {
            case NonFatal(e) => // ignore
                Log.debug("Error in logger metrics watcher on next", e)
        }
    }

    override def onError(t: Throwable) {
        Log.debug("Error in logger metrics watcher", t)
    }

    override def onCompleted() { }

    private def doFirstStageFastReboot(process: MonitoredDaemonProcess): Unit = {
        try {
            process.stopAsync().awaitTerminated()
        } catch {
            case NonFatal(e) =>
                RebootLog.error("Error executing first stage fast shutdown", e)
        }
    }
}

