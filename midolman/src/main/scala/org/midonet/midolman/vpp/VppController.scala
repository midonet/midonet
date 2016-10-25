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

import java.util
import java.util.UUID
import java.util.concurrent.{TimeUnit, TimeoutException}
import java.util.function.Consumer

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

import com.google.inject.Inject

import rx.subscriptions.CompositeSubscription

import org.midonet.cluster.services.MidonetBackend
import org.midonet.midolman.Midolman.MIDOLMAN_ERROR_CODE_VPP_PROCESS_DIED
import org.midonet.midolman.io.UpcallDatapathConnectionManager
import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.midolman.rules.NatTarget
import org.midonet.midolman.simulation.{Port, RouterPort}
import org.midonet.midolman.topology.VirtualToPhysicalMapper.LocalPortActive
import org.midonet.midolman.topology.{VirtualToPhysicalMapper, VirtualTopology}
import org.midonet.midolman.vpp.VppDownlink._
import org.midonet.midolman.{DatapathState, Midolman, Referenceable}
import org.midonet.packets.{IPv4Addr, IPv4Subnet, IPv6Addr, IPv6Subnet}
import org.midonet.util.concurrent.ReactiveActor.{OnCompleted, OnError}
import org.midonet.util.concurrent.{ConveyorBelt, ReactiveActor, SingleThreadExecutionContextProvider}
import org.midonet.util.process.ProcessHelper.OutputStreams._
import org.midonet.util.process.{MonitoredDaemonProcess, ProcessHelper}

object VppController extends Referenceable {

    override val Name: String = "VppController"
    val VppProcessMaximumStarts = 3
    val VppProcessFailingPeriod = 30000
    val VppRollbackTimeout = 30000
    val VppCtlTimeout = 1 minute

    private val VppConnectionName = "midonet"
    private val VppConnectMaxRetries = 10
    private val VppConnectDelayMs = 1000

    private case class BoundPort(setup: VppSetup)
    private type LinksMap = util.Map[UUID, BoundPort]

    private def isIPv6(port: RouterPort) = (port.portAddressV6 ne null) &&
                                           (port.portSubnetV6 ne null)
}


class VppController @Inject()(upcallConnManager: UpcallDatapathConnectionManager,
                              datapathState: DatapathState,
                              protected override val vt: VirtualTopology)
    extends ReactiveActor[AnyRef]
    with ActorLogWithoutPath
    with SingleThreadExecutionContextProvider
    with VppDownlink {

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
    private val uplinks: LinksMap = new util.HashMap[UUID, BoundPort]
    private val downlinks: LinksMap = new util.HashMap[UUID, BoundPort]

    private var vppExiting = false
    private val vppExitAction = new Consumer[Exception] {
        override def accept(t: Exception): Unit = {
            if (!vppExiting) {
                log.debug(t.getMessage)
                Midolman.exitAsync(MIDOLMAN_ERROR_CODE_VPP_PROCESS_DIED)
            }
        }
    }

    private val eventHandler: PartialFunction[Any, Future[_]] = {
        case LocalPortActive(portId, portNumber, true) =>
            attachUplink(portId)
        case LocalPortActive(portId, portNumber, false) =>
            detachUplink(portId)
        case CreateDownlink(portId, vrf, ip4Address, ip6Address, natPool) =>
            attachDownlink(portId, vrf, ip4Address, ip6Address, natPool)
        case UpdateDownlink(portId, vrf, oldAddress, newAddress) =>
            // TODO
            Future.successful(())
        case DeleteDownlink(portId, vrf) =>
            detachDownlink(portId)
        case AssociateFip(portId, vrf, floatingIp, fixedIp) =>
            associateFip(portId, vrf, floatingIp, fixedIp)
            Future.successful(())
        case DisassociateFip(portId, vrf, floatingIp, fixedIp) =>
            disassociateFip(portId, vrf, floatingIp, fixedIp)
            Future.successful(())
        case OnCompleted =>
            Future.successful(())
        case OnError(e) =>
            log.error("Exception on active ports observable", e)
            Future.failed(e)
    }

    override def preStart(): Unit = {
        super.preStart()
        log debug s"Starting VPP controller"
        portsSubscription add VirtualToPhysicalMapper.portsActive.subscribe(this)
    }

    override def postStop(): Unit = {
        log debug s"Stopping VPP controller"
        val cleanupFutures =
            uplinks.values().asScala.map(_.setup.rollback()) ++
            downlinks.values().asScala.map(_.setup.rollback())

        Await.ready(Future.sequence(cleanupFutures), VppRollbackTimeout millis)
        if ((vppProcess ne null) && vppProcess.isRunning) {
            stopVppProcess()
        }
        portsSubscription.unsubscribe()
        uplinks.clear()
        downlinks.clear()
        super.postStop()
    }

    override def receive: Receive = super.receive orElse {
        case m if eventHandler.isDefinedAt(m) =>
            belt.handle(() => eventHandler(m))
        case m =>
            log warn s"Unknown message $m"
    }

    private def attachLink(links: LinksMap, portId: UUID, setup: VppSetup)
    : Future[_] = {
        val boundPort = BoundPort(setup)

        {
            links.put(portId, boundPort) match {
                case null => Future.successful(Unit)
                case previousBinding: BoundPort =>
                    previousBinding.setup.rollback()
            }
        } andThen {
            case _ => setup.execute()
        } andThen {
            case Failure(err) =>
                setup.rollback()
                links.remove(portId, boundPort)
        }
    }

    private def detachLink(links: LinksMap, portId: UUID): Future[_] = {
        links remove portId match {
            case boundPort: BoundPort =>
                log debug s"FIP64 port $portId detached"
                boundPort.setup.rollback()
            case null => Future.successful(Unit)
        }
    }

    private def attachUplink(portId: UUID): Future[_] = {
        log debug s"Attaching uplink port $portId"

        val shouldStartDownlink = uplinks.isEmpty
        VirtualTopology.get(classOf[Port], portId) andThen {
            case Success(port: RouterPort) if isIPv6(port) =>
                log debug s"Port $port is an IPv6 port"

                if (vppProcess eq null) {
                    startVppProcess()
                    vppApi = createApiConnection(VppConnectMaxRetries)
                }

                val dpNumber = datapathState.getDpPortNumberForVport(portId)
                attachLink(uplinks, portId,
                           new VppUplinkSetup(port.id, dpNumber, vppApi, vppOvs))

            case Success(_) =>
                Future.successful(())

            case Failure(err) =>
                log warn s"Get port $portId failed: $err"
                Future.failed(err)
        } andThen { case _ =>
            if (shouldStartDownlink && !uplinks.isEmpty) {
                startDownlink()
            }
            Future.successful(())
        }
    }

    private def detachUplink(portId: UUID): Future[_] = {
        log debug s"Detaching uplink port $portId"

        detachLink(uplinks, portId) andThen { case _ =>
            if (uplinks.isEmpty) {
                stopDownlink()
            }
            Future.successful(())
        }
    }

    private def attachDownlink(portId: UUID, vrf: Int, ip4Address: IPv4Subnet,
                               ip6Address: IPv6Subnet, natPool: NatTarget)
    : Future[_] = {
        log debug s"Attach downlink port $portId (VRF $vrf): network=$ip6Address " +
                  s"link-local=$ip4Address pool=$natPool"

        if (vppProcess eq null) {
            startVppProcess()
            vppApi = createApiConnection(VppConnectMaxRetries)
        }

        attachLink(downlinks, portId,
                   new VppDownlinkSetup(portId, vrf, ip4Address, ip6Address,
                                        vppApi, vt.backend))
    }

    private def detachDownlink(portId: UUID): Future[_] = {
        log debug s"Detaching downlink port $portId"
        detachLink(downlinks, portId)
    }

    @tailrec
    private def createApiConnection(retriesLeft: Int): VppApi = {
        try {
            // sleep before trying api, because otherwise it hangs when
            // trying to connect to vpp
            Thread.sleep(VppConnectDelayMs)
            return new VppApi(VppConnectionName)
        } catch {
            case NonFatal(e) if retriesLeft > 0 =>
            case NonFatal(e) => throw e
        }
        createApiConnection(retriesLeft - 1)
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

    private def associateFip(portId: UUID, vrf: Int, floatingIp: IPv6Addr,
                             fixedIp: IPv4Addr): Future[_] = {
        log debug s"Associating FIP at port $portId (VRF $vrf): " +
                  s"$floatingIp -> $fixedIp"

        // TODO: Remove hardcoded addresses.
        val srcIp6 = "2001::1"
        val srcIp4 = "20.0.0.1"
        executeCommandWithTimeout(s"sudo vppctl fip64 add $srcIp6 $floatingIp " +
                                  s"$srcIp4 $fixedIp $vrf")
    }

    private def disassociateFip(portId: UUID, vrf: Int, floatingIp: IPv6Addr,
                                fixedIp: IPv4Addr): Future[_] ={
        log debug s"Disassociating FIP at port $portId (VRF $vrf): " +
                  s"$floatingIp -> $fixedIp"

        // TODO: Remove hardcoded addresses.
        val srcIp6 = "2001::1"
        executeCommandWithTimeout(s"sudo vppctl fip64 del $srcIp6 $floatingIp")
    }

    private def executeCommandWithTimeout(cmdLine: String): Future[_] = {
        val process = ProcessHelper.newProcess(cmdLine)
            .logOutput(log.underlying, "vppctl", StdOutput, StdError)
            .run()

        val promise = Promise[Unit]
        Future {
            if (!process.waitFor(VppCtlTimeout.toMillis, MILLISECONDS)) {
                process.destroy()
                throw new TimeoutException("Command execution timed out")
            }
            if (process.exitValue == 0) {
                promise.trySuccess(())
            } else {
                promise.tryFailure(new Exception(
                    s"Command failed with result ${process.exitValue}"))
            }
        }
        promise.future
    }
}


