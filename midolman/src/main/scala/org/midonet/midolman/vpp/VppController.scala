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

package org.midonet.midolman.vpp

import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success}

import com.google.inject.Inject

import rx.subscriptions.CompositeSubscription

import org.midonet.cluster.services.MidonetBackend
import org.midonet.midolman.Midolman.MIDOLMAN_ERROR_CODE_VPP_PROCESS_DIED
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.io.UpcallDatapathConnectionManager
import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.midolman.simulation.{Port, RouterPort}
import org.midonet.midolman.topology.VirtualToPhysicalMapper.LocalPortActive
import org.midonet.midolman.topology.{VirtualToPhysicalMapper, VirtualTopology}
import org.midonet.midolman.{DatapathState, Midolman, Referenceable}
import org.midonet.util.concurrent.ReactiveActor.{OnCompleted, OnError}
import org.midonet.util.concurrent.{ConveyorBelt, ReactiveActor, SingleThreadExecutionContextProvider}
import org.midonet.util.process.MonitoredDaemonProcess

object VppController extends Referenceable {

    override val Name: String = "VppController"
    val VppProcessMaximumStarts = 3
    val VppProcessFailingPeriod = 30000
    val VppRollbackTimeout = 30000

    private val VppConnectionName = "midonet"
    private val VppConnectMaxRetries = 10
    private val VppConnectDelayMs = 1000

    private case class BoundPort(port: RouterPort, setup: VppSetup)

    private def isIPv6(port: RouterPort) = (port.portAddressV6 ne null) &&
                                           (port.portSubnetV6 ne null)
}


class VppController @Inject()(config: MidolmanConfig,
                              upcallConnManager: UpcallDatapathConnectionManager,
                              datapathState: DatapathState,
                              midonetBackend: MidonetBackend)
    extends ReactiveActor[AnyRef]
    with ActorLogWithoutPath
    with SingleThreadExecutionContextProvider {

    import VppController._

    override def logSource = "org.midonet.vpp-controller"

    private implicit val ec: ExecutionContext = singleThreadExecutionContext

    private var vppProcess: MonitoredDaemonProcess = _
    private var vppApi: VppApi = _
    private val vppOvs = new VppOvs(datapathState.datapath)
    private val belt = new ConveyorBelt(t => {
        log.error("Error on conveyor belt", t)
    })

    private val portsSubscription = new CompositeSubscription()
    private val watchedPorts = mutable.Map.empty[UUID, BoundPort]

    private var vppExiting = false
    private val vppExitAction = new Consumer[Exception] {
        override def accept(t: Exception): Unit = {
            if (!vppExiting) {
                log.debug(t.getMessage)
                Midolman.exitAsync(MIDOLMAN_ERROR_CODE_VPP_PROCESS_DIED)
            }
        }
    }

    override def preStart(): Unit = {
        super.preStart()
        portsSubscription add VirtualToPhysicalMapper.portsActive.subscribe(this)
    }

    override def postStop(): Unit = {
        val cleanupFutures = watchedPorts.map(_._2.setup.rollback())
        Await.ready(Future.sequence(cleanupFutures), VppRollbackTimeout millis)
        if ((vppProcess ne null) && vppProcess.isRunning) {
            stopVppProcess()
        }
        portsSubscription.unsubscribe()
        watchedPorts.clear()
        super.postStop()
    }

    override def receive: Receive = super.receive orElse {
        case LocalPortActive(portId, portNumber, true) =>
            handlePortActive(portId)
        case LocalPortActive(portId, portNumber, false) =>
            handlePortInactive(portId)
        case OnCompleted => // ignore
        case OnError(err) => log.error("Exception on active ports observable",
                                       err)
        case m =>
            log warn s"Unknown message $m"
    }

    private def handlePortActive(portId: UUID): Unit = {

        VirtualTopology.get(classOf[Port], portId) onComplete {
            case Success(port: RouterPort) if isIPv6(port) =>
                log debug s"IPv6 router port attached: $port"
                attachPort(portId, port)
            case Success(_) => // ignore
            case Failure(err) =>
                log warn s"Get port $portId failed: $err"
        }
    }

    private def handlePortInactive(portId: UUID) {
        detachPort(portId)
    }

    private def attachPort(portId: UUID, port: RouterPort): Unit = {
        if (vppProcess eq null) {
            startVppProcess()
            vppApi = createApiConnection(VppConnectMaxRetries)
        }

        def setupPort(): Future[_] = {
            val setup =
                new VppUplinkSetup(port.id,
                                   datapathState.getDpPortNumberForVport(portId),
                                   vppApi,
                                   vppOvs)
            val boundPort = BoundPort(port, setup)

            val unbindF = watchedPorts.put(portId, boundPort) match {
                case Some(previousBinding) =>
                    previousBinding.setup.rollback()
                case None => Future.successful(Unit)
            }
            unbindF andThen {
                case _ => setup.execute()
            } andThen {
                case Failure(err) =>
                    setup.rollback()
                    watchedPorts.get(portId) match {
                        case Some(p) if p == boundPort =>
                            watchedPorts.remove(portId)
                        case _ =>
                    }
            }
        }
        belt.handle(setupPort)
    }

    private def detachPort(portId: UUID): Unit = {
        def cleanupPort(): Future[_] = {
            watchedPorts remove portId match {
                case Some(entry) =>
                    log debug s"IPv6 port $portId detached"
                    entry.setup.rollback()
                case None => Future.successful(Unit)
            }
        }
        belt.handle(cleanupPort)
    }
    private def attachDlinkPort(portId: UUID, port: RouterPort): Unit = {

    }

    private def detachDlinkPort(portId: UUID): Unit = {

    }

    private def createApiConnection(retriesLeft: Int): VppApi = {
        try {
            // sleep before trying api, because otherwise it hangs when
            // trying to connect to vpp
            Thread.sleep(VppConnectDelayMs)
            new VppApi(VppConnectionName)
        } catch {
            case err: Throwable if retriesLeft > 0 =>
                Thread.sleep(VppConnectDelayMs)
                createApiConnection(retriesLeft - 1)
            case err: Throwable => throw err
        }
    }

    private def startVppProcess(): Unit = {
        if (vppExiting) return
        log debug "Starting vpp process"
        vppProcess = new MonitoredDaemonProcess(
            "/usr/share/midolman/vpp-start", log.underlying, "org.midonet.vpp",
            VppProcessMaximumStarts, VppProcessFailingPeriod, vppExitAction)
        vppProcess.startAsync()
            .awaitRunning(VppProcessFailingPeriod, TimeUnit.MILLISECONDS)
        log debug "vpp process started"
    }

    private def stopVppProcess(): Unit = {
        log debug "Stopping vpp process"
        // flag vpp exiting so midolman isn't stopped by the exit action
        vppExiting = true
        vppProcess.stopAsync()
            .awaitTerminated(VppProcessFailingPeriod, TimeUnit.MILLISECONDS)
        vppProcess = null
    }
}


