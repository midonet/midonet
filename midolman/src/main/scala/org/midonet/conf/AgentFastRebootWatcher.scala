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

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.control.NonFatal

import akka.actor.ActorSystem

import com.google.inject.Injector
import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger

import org.slf4j.LoggerFactory

import rx.Observer

import org.midonet.conf.AgentFastRebootWatcher.{Log, RebootLog}
import org.midonet.midolman.SupervisorActor
import org.midonet.midolman.host.services.HostService
import org.midonet.midolman.management.SimpleHTTPServerService
import org.midonet.midolman.openstack.metadata.MetadataServiceManagerActor
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
                             process: MonitoredDaemonProcess,
                             injector: Injector) extends Observer[Config] {


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
                    doFirstStageFastReboot()
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

    private def doFirstStageFastReboot(): Unit = {
        try {
            process
                .stopAsync().awaitTerminated()
            injector.getInstance(classOf[HostService])
                .stopAsync().awaitTerminated()
            injector.getInstance(classOf[SimpleHTTPServerService])
                .stopAsync().awaitTerminated()
            val system = injector.getInstance(classOf[ActorSystem])
            val path = system / SupervisorActor.Name / MetadataServiceManagerActor.Name
            val metadata = Await.result(
                system.actorSelection(path).resolveOne(), 1 second)
            system.stop(metadata)

        } catch {
            case NonFatal(e) =>
                RebootLog.error("Error executing first stage fast shutdown", e)
        }
    }
}

