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

import java.util.concurrent.{ExecutorService, Executors, TimeUnit}

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.control.NonFatal

import akka.actor.ActorSystem
import akka.pattern.ask
import akka.util.Timeout

import com.google.inject.Injector
import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.recipes.barriers.DistributedBarrier
import org.slf4j.LoggerFactory

import rx.Observer

import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.storage.MidonetBackendConfig
import org.midonet.conf.AgentFastRebootWatcher.{Log, RebootLog}
import org.midonet.midolman.{Midolman, SupervisorActor}
import org.midonet.midolman.host.services.HostService
import org.midonet.midolman.management.SimpleHTTPServerService
import org.midonet.midolman.openstack.metadata.MetadataServiceManagerActor
import org.midonet.midolman.routingprotocols.RoutingManagerActor
import org.midonet.midolman.routingprotocols.RoutingManagerActor.StopBgpHandlers
import org.midonet.util.functors.makeRunnable
import org.midonet.util.process.{MonitoredDaemonProcess, ProcessHelper}


class AgentFastRebootBarriers(zk: CuratorFramework,
                              config: MidonetBackendConfig) {

    private val path = s"${config.rootKey}/fast_reboot/${HostIdGenerator.getHostId}"

    val stage1 = new DistributedBarrier(zk, s"$path/stage1")

    val stage2 = new DistributedBarrier(zk, s"$path/stage2")

    val stage3 = new DistributedBarrier(zk, s"$path/stage3")

    RebootLog.info(s"STAGE1!!! -> $path/stage1")
    RebootLog.info(s"STAGE2!!! -> $path/stage2")
    RebootLog.info(s"STAGE3!!! -> $path/stage3")

    def clear() = {
        RebootLog.info("Clearing fast reboot barriers.")
        Seq(stage1, stage2, stage3) foreach { stage =>
            stage.removeBarrier()
            stage.waitOnBarrier()
        }
        RebootLog.info("Fast reboot barriers cleared succesfully.")
    }

}

object AgentFastRebootConfig {

    def apply(config: Config): Option[Boolean] = {
        try {
            val enabled = config.getBoolean(s"agent.midolman.fast_reboot")
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

class AgentFastRebootWatcher(minionProcess: MonitoredDaemonProcess,
                             injector: Injector,
                             backendConfig: MidonetBackendConfig)
    extends Observer[Config] {

    private[conf] var config: Option[Boolean] = None

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    private val system = injector.getInstance(classOf[ActorSystem])

    private val backend = injector.getInstance(classOf[MidonetBackend])

    private val barriers = new AgentFastRebootBarriers(backend.curator,
                                                       backendConfig)

    implicit private val timeout = new Timeout(1 second)

    override def onNext(rawConfig: Config): Unit = {
        try {
            val newConfig = AgentFastRebootConfig(rawConfig)
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
                    startFastRebootWorkflow()
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

    private def stopRoutingHandlerActors() = {
        RebootLog.info("Stopping BGP handler actors")
        val path = system / SupervisorActor.Name / RoutingManagerActor.Name
        val routingActor = Await.result(system.actorSelection(path).resolveOne(),
                                        1 second)
        val routingActorStopped = routingActor ? StopBgpHandlers()
        RebootLog.debug("Awaiting for the routing manager actor to finish stopping" +
                        "its child routing handler actors.")
        Await.result(routingActorStopped, 1 second)
    }

    private def stopMetadataActor() = {
        val path = system / SupervisorActor.Name / MetadataServiceManagerActor.Name
        val metadata = Await.result(
            system.actorSelection(path).resolveOne(), 1 second)
        system.stop(metadata)
    }

    private def startFastRebootWorkflow() = executor submit makeRunnable {

        barriers.clear()
        // TODO: handle timeouts on barriers and abort reboot
        barriers.stage1.setBarrier()

        minionProcess.stopAsync().awaitTerminated()
        RebootLog.info("Fast reboot (main agent): starting twin agent and " +
                       "waiting for it to finish initialization.")
        ProcessHelper.newDaemonProcess("/usr/share/midolman/midolman-start").run()
        RebootLog.info("Fast reboot (main agent): waiting (10s) on stage1 barrier.")
        barriers.stage1.waitOnBarrier(10, TimeUnit.SECONDS)
        RebootLog.info("Fast reboot (main agent): stage1 barrier removed.")
        RebootLog.info("Fast reboot (main agent): stopping incompatible " +
                       "services.")
        try {
            stopRoutingHandlerActors()
            stopMetadataActor()

            injector.getInstance(classOf[HostService])
                .stopAsync().awaitTerminated()
            injector.getInstance(classOf[SimpleHTTPServerService])
                .stopAsync().awaitTerminated()
        } catch {
            case NonFatal(e) =>
                RebootLog.error("Error executing first stage fast shutdown", e)
        }

        RebootLog.info("Fast reboot (main agent): incompatible services " +
                       "stopped.")

        barriers.stage3.setBarrier()
        barriers.stage2.removeBarrier()
        RebootLog.info("Fast reboot (main agent): remove stage2 barrier, " +
                       "waiting (2s) on stage3 barrier.")
        barriers.stage3.waitOnBarrier(5, TimeUnit.SECONDS)

        RebootLog.info("Fast reboot (main agent): stage3 barrier removed. " +
                       "continue stopping the main agent.")

        Midolman.exitAsync(0)
    }
}

