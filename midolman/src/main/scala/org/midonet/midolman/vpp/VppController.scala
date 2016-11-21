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
import java.util.concurrent.{ConcurrentHashMap, TimeUnit, TimeoutException}
import java.util.function.Consumer

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.util.control.NonFatal
import scala.util.{Failure, Success}
import com.google.inject.Inject
import com.typesafe.scalalogging.Logger
import rx.subscriptions.CompositeSubscription
import org.midonet.cluster.data.storage.StateTableEncoder.GatewayHostEncoder
import org.midonet.cluster.services.MidonetBackend
import org.midonet.conf.HostIdGenerator
import org.midonet.midolman.Midolman.MIDOLMAN_ERROR_CODE_VPP_PROCESS_DIED
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.io.UpcallDatapathConnectionManager
import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.midolman.rules.NatTarget
import org.midonet.midolman.simulation.{Port, RouterPort}
import org.midonet.midolman.topology.VirtualToPhysicalMapper.LocalPortActive
import org.midonet.midolman.topology.{VirtualToPhysicalMapper, VirtualTopology}
import org.midonet.midolman.vpp.VppDownlink._
import org.midonet.midolman.{DatapathState, Midolman, Referenceable}
import org.midonet.packets.{IPv4Addr, IPv4Subnet, IPv6Addr, IPv6Subnet, MAC}
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

    private[vpp] val tunnelKeyToPort = new ConcurrentHashMap[Long, UUID]

    private case class BoundPort(setup: VppSetup)
    private type LinksMap = util.Map[UUID, BoundPort]

    private def isIPv6(port: RouterPort) = (port.portAddressV6 ne null) &&
                                           (port.portSubnetV6 ne null)

    /**
      * Returns the virtual port identifier for the specified tunnel key.
      * Packets received from tunnels with the specified tunnel key are handled
      * as ingressing at the specified virtual port.
      */
    def portOfTunnelKey(tunnelKey: Long): UUID = {
        tunnelKeyToPort.get(tunnelKey)
    }
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
    private var downlinkVxlan: Option[VppDownlinkVxlanSetup] = None

    private val vxlanTunnels = new util.HashMap[UUID, VppVxlanTunnelSetup]

    private val gatewayId = HostIdGenerator.getHostId
    private lazy val gatewayTable = vt.backend.stateTableStore
        .getTable[UUID, AnyRef](MidonetBackend.GatewayTable)

    private var vppExiting = false
    private val vppExitAction = new Consumer[Exception] {
        override def accept(t: Exception): Unit = {
            if (!vppExiting) {
                log.debug(t.getMessage)
                Midolman.exitAsync(MIDOLMAN_ERROR_CODE_VPP_PROCESS_DIED)
            }
        }
    }

    private val eventHandlerWithoutVxlan: PartialFunction[Any, Future[_]] = {
        case LocalPortActive(portId, portNumber, true) =>
            attachUplink(portId)
        case LocalPortActive(portId, portNumber, false) =>
            detachUplink(portId)
        case create@CreateDownlink(portId, vrf, vni,
                                   ip4Address, ip6Address, natPool, routerPortMac) =>
            attachDownlink(portId, vrf, create.vppAddress4, ip6Address, natPool)
        case UpdateDownlink(portId, vrf, oldAddress, newAddress) =>
            // TODO
            Future.successful(())
        case DeleteDownlink(portId, vrf, vni) =>
            detachDownlink(portId)
        case AssociateFip(portId, vrf, floatingIp, fixedIp, localIp, natPool) =>
            associateFip(portId, vrf, floatingIp, fixedIp, localIp, natPool)
        case DisassociateFip(portId, vrf, floatingIp, fixedIp, localIp) =>
            disassociateFip(portId, vrf, floatingIp, fixedIp, localIp)
        case OnCompleted =>
            Future.successful(())
        case OnError(e) =>
            log.error("Exception on active ports observable", e)
            Future.failed(e)
    }

    private val eventHandlerWithVxlan: PartialFunction[Any, Future[_]] = {
        case LocalPortActive(portId, portNumber, true) =>
            attachUplink(portId) flatMap {
                _ match {
                    case Some(_) => attachDownlinkVxlan()
                    case None => Future.successful(Unit) // not an IPv6 uplink
                }
            }
        case LocalPortActive(portId, portNumber, false) =>
            detachUplink(portId).flatMap {
                case _ => detachDownlinkVxlan()
            }
        case create@CreateDownlink(portId, vrf, vni,
                                   ip4Address, ip6Address, natPool, routerPortMac) =>
            createDownlinkTunnel(portId, vrf, vni, routerPortMac)

        case UpdateDownlink(portId, vrf, oldAddress, newAddress) =>
            log.error("Updating downlink in FIP64 vxlan downlink mode")
            Future.successful(())
        case DeleteDownlink(portId, vrf, vni) =>
            deleteDownlinkTunnel(portId, vni)
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

    var fip64Vxlan = vt.config.fip64.vxlanDownlink
    var eventHandler = getEventHandler()

    def getEventHandler(): PartialFunction[Any, Future[_]] = {
        if (fip64Vxlan) {
            eventHandlerWithVxlan orElse eventHandlerWithoutVxlan
        } else {
            eventHandlerWithoutVxlan
        }
    }

    def resetEventHandler(): Unit = {
        eventHandler = getEventHandler
    }

    override def receive: Receive = super.receive orElse {
        case m if eventHandler.isDefinedAt(m) =>
            belt.handle(() => eventHandler(m))
        case m =>
            log warn s"Unknown message $m"
    }

    private def attachLink(links: LinksMap, portId: UUID, setup: VppSetup)
    : Future[Option[BoundPort]] = {
        val boundPort = BoundPort(setup)

        {
            links.put(portId, boundPort) match {
                case null => Future.successful(Unit)
                case previousBinding: BoundPort =>
                    previousBinding.setup.rollback()
            }
        } flatMap { _ =>
            setup.execute() map { _ => Some(boundPort) }
        } recoverWith { case e =>
            setup.rollback() map { _ =>
                links.remove(portId, boundPort)
                throw e
            }
        }
    }

    private def detachLink(links: LinksMap, portId: UUID): Future[_] = {
        links remove portId match {
            case boundPort: BoundPort =>
                log debug s"Port $portId detached"
                boundPort.setup.rollback()
            case null => Future.successful(Unit)
        }
    }

    private def attachUplink(portId: UUID): Future[_] = {
        log debug s"Local port $portId active"

        val shouldStartDownlink = uplinks.isEmpty
        VirtualTopology.get(classOf[Port], portId) flatMap {
            case port: RouterPort if isIPv6(port) =>
                log debug s"Attaching IPv6 uplink port $port"

                if (vppProcess eq null) {
                    startVppProcess()
                    vppApi = createApiConnection(VppConnectMaxRetries)
                }

                val dpNumber = datapathState.getDpPortNumberForVport(portId)
                attachLink(uplinks, portId,
                           new VppUplinkSetup(port.id, port.portAddressV6,
                                              dpNumber, vppApi, vppOvs,
                                              Logger(log.underlying)))
            case _ =>
                Future.successful(None)
        } andThen {
            case Success(Some(_)) =>
                if (shouldStartDownlink && !uplinks.isEmpty) {
                    startDownlink()
                }

            case Failure(err) =>
                log warn s"Get port $portId failed: $err"
        }
    }

    private def detachUplink(portId: UUID): Future[_] = {
        log debug s"Local port $portId inactive"

        detachLink(uplinks, portId) andThen { case _ =>
            if (uplinks.isEmpty) {
                stopDownlink()
            }
        }
    }

    private def attachDownlink(portId: UUID, vrf: Int, vppAddress4: IPv4Subnet,
                               portAddress6: IPv6Subnet, natPool: NatTarget)
    : Future[_] = {

        log debug s"Attach downlink port $portId (VRF $vrf): " +
                  s"network=$portAddress6 vppAddress=$vppAddress4 pool=$natPool"

        if (vppProcess eq null) {
            startVppProcess()
            vppApi = createApiConnection(VppConnectMaxRetries)
        }

        attachLink(downlinks, portId,
                   new VppDownlinkSetup(portId, vrf, vppAddress4, portAddress6,
                                        vppApi, vt.backend,
                                        Logger(log.underlying)))
    }

    private def detachDownlink(portId: UUID): Future[_] = {
        log debug s"Detaching downlink port $portId"
        detachLink(downlinks, portId)
    }

    private def attachDownlinkVxlan(): Future[_] = {
        log debug s"Attach downlink VXLAN port"

        val setup = new VppDownlinkVxlanSetup(vt.config.fip64,
                                              vppApi,
                                              Logger(log.underlying));
        {
            downlinkVxlan match {
                case None => Future.successful(Unit)
                case Some(previousSetup: VppDownlinkVxlanSetup) => previousSetup.rollback()
            }
        } flatMap { _ =>
            setup.execute()
        } recoverWith { case e =>
            setup.rollback() map { _ =>
                downlinkVxlan = None
                throw e
            }
        }
    }

    private def detachDownlinkVxlan() : Future[_] = {
        log debug s"Detaching downlink VXLAN port"

        downlinkVxlan match {
            case None => Future.failed(null)
            case Some(previousSetup: VppDownlinkVxlanSetup) => previousSetup.rollback()
        }
    }

    private def createDownlinkTunnel(portId: UUID,
                                     vrf: Int, vni: Int,
                                     routerPortMac: MAC) : Future[_] = {
        log debug s"Creating downlink tunnel for port:$portId, vrf:$vrf, vni:$vni"
        val setup = new VppVxlanTunnelSetup(vt.config.fip64,
                                            vni, vrf, routerPortMac,
                                            vppApi, Logger(log.underlying));

        {
            vxlanTunnels.replace(portId, setup) match {
                case oldSetup: VppVxlanTunnelSetup =>
                    oldSetup.rollback() flatMap { _ =>
                        setup.execute()
                    }
                case null =>
                    setup.execute()
            }
        } andThen { case _ =>
                datapathState.setFip64PortKey(portId, vni)
        } recoverWith { case e =>
            setup.rollback() map {
                vxlanTunnels.remove(portId, setup)
                throw e
            }
        }
    }

    private def deleteDownlinkTunnel(portId: UUID, vni: Int) : Future[_] = {
        log debug s"Deleting downlink tunnel for port:$portId"
        vxlanTunnels.remove(portId) match {
            case setup: VppVxlanTunnelSetup =>
                setup.rollback() andThen { case _ =>
                    datapathState.clearFip64PortKey(portId, vni)
                }
            case null =>
                Future.failed(null)
        }
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
        log debug "Starting VPP process"
        vppProcess = new MonitoredDaemonProcess(
            "/usr/share/midolman/vpp-start", log.underlying, "org.midonet.vpp",
            VppProcessMaximumStarts, VppProcessFailingPeriod, vppExitAction)
        vppProcess.startAsync()
            .awaitRunning(VppProcessFailingPeriod, TimeUnit.MILLISECONDS)

        gatewayTable.start()
        gatewayTable.add(gatewayId, GatewayHostEncoder.DefaultValue)

        log debug "vpp process started"
    }

    private def stopVppProcess(): Unit = {
        log debug "Stopping VPP process"
        // flag vpp exiting so midolman isn't stopped by the exit action
        vppExiting = true
        vppProcess.stopAsync()
            .awaitTerminated(VppProcessFailingPeriod, TimeUnit.MILLISECONDS)
        vppProcess = null

        gatewayTable.remove(gatewayId)
        gatewayTable.stop()
    }

    private def associateFip(portId: UUID, vrf: Int, floatingIp: IPv6Addr,
                             fixedIp: IPv4Addr, localIp: IPv4Subnet,
                             natPool: NatTarget): Future[_] = {
        log debug s"Associating FIP at port $portId (VRF $vrf): " +
                  s"$floatingIp -> $fixedIp"

        exec(s"vppctl ip route table $vrf add $fixedIp/32 " +
             s"via ${localIp.getAddress}") flatMap { _ =>
            exec(s"vppctl fip64 add $floatingIp $fixedIp " +
                 s"pool ${natPool.nwStart} ${natPool.nwStart} table $vrf")
        }
    }

    private def disassociateFip(portId: UUID, vrf: Int, floatingIp: IPv6Addr,
                                fixedIp: IPv4Addr, localIp: IPv4Subnet)
    : Future[_] ={
        log debug s"Disassociating FIP at port $portId (VRF $vrf): " +
                  s"$floatingIp -> $fixedIp"

        exec(s"vppctl fip64 del $floatingIp") flatMap { _ =>
            exec(s"vppctl ip route table $vrf del $fixedIp/32")
        }
    }

    private def exec(command: String): Future[_] = {
        log debug s"Executing command: `$command`"

        val process = ProcessHelper.newProcess(command)
            .logOutput(log.underlying, "vppctl", StdOutput, StdError)
            .run()

        val promise = Promise[Unit]
        Future {
            if (!process.waitFor(VppCtlTimeout.toMillis, MILLISECONDS)) {
                process.destroy()
                log warn s"Command `$command` timed out"
                throw new TimeoutException(s"Command `$command` timed out")
            }
            log debug s"Command `$command` exited with code ${process.exitValue()}"
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


