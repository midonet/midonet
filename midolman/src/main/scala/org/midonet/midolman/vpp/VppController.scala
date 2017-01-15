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
import scala.collection.{immutable, mutable}
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

import com.typesafe.scalalogging.Logger

import rx.{Observer, Subscription}

import org.midonet.cluster.data.storage.StateTableEncoder.GatewayHostEncoder
import org.midonet.cluster.models.Commons.IPVersion
import org.midonet.cluster.models.Neutron.NeutronNetwork
import org.midonet.cluster.models.Topology.Route
import org.midonet.cluster.services.MidonetBackend
import org.midonet.midolman.Midolman.MIDOLMAN_ERROR_CODE_VPP_PROCESS_DIED
import org.midonet.midolman.simulation.RouterPort
import org.midonet.midolman.topology.VirtualTopology
import org.midonet.midolman.vpp.VppDownlink._
import org.midonet.midolman.vpp.VppExecutor.Receive
import org.midonet.midolman.vpp.VppExternalNetwork.{AddExternalNetwork, RemoveExternalNetwork}
import org.midonet.midolman.vpp.VppProviderRouter.Gateways
import org.midonet.midolman.vpp.VppUplink.{AddUplink, DeleteUplink}
import org.midonet.midolman.{DatapathState, Midolman}
import org.midonet.packets.{IPv4Addr, IPv4Subnet, IPv6Addr, IPv6Subnet, MAC, TunnelKeys}
import org.midonet.util.process.MonitoredDaemonProcess

object VppController {

    val VppProcessMaximumStarts = 3
    val VppProcessTimeout = 2 seconds
    val VppRollbackTimeout = 5 seconds

    private val VppConnectionName = "midonet"
    private val VppConnectMaxRetries = 10
    private val VppConnectDelayMs = 1000

    val VppFlowStateCfg = VppFlowStateConfig(
        vniOut  = TunnelKeys.Fip64FlowStateSendKey,
        vniIn   = TunnelKeys.Fip64FlowStateReceiveKey,
        vrfOut  = VppVrfs.FlowStateOutput.id,
        vrfIn   = VppVrfs.FlowStateInput.id)

    private object Cleanup

    /**
      * Maintains an open gateways state table for the specified external
      * Neutron network.
      */
    private class ExternalNetwork(networkId: UUID, hostId: UUID,
                                       vt: VirtualTopology) {
        private val table =
            vt.stateTables.getTable[UUID, AnyRef](classOf[NeutronNetwork],
                                                  networkId,
                                                  MidonetBackend.GatewayTable)
        table.start()
        table.add(hostId, GatewayHostEncoder.DefaultValue)

        /**
          * Removes this host from the gateways state table and stops it.
          */
        def complete(): Unit = {
            table.remove(hostId)
            table.stop()
        }
    }

}


class VppController(protected override val hostId: UUID,
                    datapathState: DatapathState,
                    vppOvs: VppOvs,
                    protected override val vt: VirtualTopology)
    extends VppExecutor with VppFip64 with VppState with VppCtl {

    import VppController._

    override def logSource = "org.midonet.vpp-controller"

    private implicit def executionContext = ec

    private var vppProcess: MonitoredDaemonProcess = _
    private var vppApi: VppApi = _

    private var routerPortSubscribers = mutable.Map[UUID, Subscription]()
    private val uplinks = new util.HashMap[UUID, VppUplinkSetup]
    private val externalNetworks = new util.HashMap[UUID, ExternalNetwork]()
    private var downlink: Option[VppDownlinkSetup] = None

    private val vxlanTunnels = new util.HashMap[UUID, VppVxlanTunnelSetup]
    private var uplinkFlowStateFlows: Option[Fip64FlowStateFlows] = None

    @volatile private var vppExiting = false
    private val vppExitAction = new Consumer[Exception] {
        override def accept(t: Exception): Unit = {
            if (!vppExiting) {
                log.debug(t.getMessage)
                Midolman.exitAsync(MIDOLMAN_ERROR_CODE_VPP_PROCESS_DIED)
            }
        }
    }

    protected override val receive: Receive = {
        case port: RouterPort =>
            log debug s"Received router port update for port ${port.id}"
            handleExternalRoutesUpdate(port)

        case AddUplink(portId, routerId, portAddress, uplinkPortIds) =>
            addUplink(portId, routerId, portAddress, uplinkPortIds)
        case DeleteUplink(portId) =>
            deleteUplink(portId)
        case Gateways(_, hosts) =>
            updateFlowStateFlows(hosts)
        case AddExternalNetwork(networkId) =>
            addExternalNetwork(networkId)
        case RemoveExternalNetwork(networkId) =>
            removeExternalNetwork(networkId)
        case CreateTunnel(portId, vrf, vni, routerPortMac) =>
            createTunnel(portId, vrf, vni, routerPortMac)
        case DeleteTunnel(portId, vrf, vni) =>
            deleteTunnel(portId, vni)
        case AssociateFip(portId, vrf, vni, floatingIp, fixedIp, localIp, natPool) =>
            associateFip(portId, vrf, vni, floatingIp, fixedIp, localIp, natPool)
        case DisassociateFip(portId, vrf, floatingIp, fixedIp, localIp) =>
            disassociateFip(portId, vrf, floatingIp, fixedIp, localIp)
        case Cleanup =>
            cleanup()
    }

    private val observer = new Observer[Any] {
        override def onNext(message: Any): Unit = {
            send(message)
        }

        override def onError(e: Throwable): Unit = { /* ignore */ }

        override def onCompleted(): Unit = { /* ignore */ }
    }

    protected override def doStart(): Unit = {
        startFip64()
        notifyStarted()
    }

    protected override def doStop(): Unit = {
        // Unsubscribe from all current subscriptions and execute the cleanup
        // on the conveyor belt.
        stopFip64()
        try Await.ready(send(Cleanup), VppRollbackTimeout)
        catch {
            case NonFatal(e) => log warn s"Cleanup failed ${e.getMessage}"
        }

        stopVppProcess()
        super.doStop()
        notifyStopped()
    }

    private def cleanup(): Future[Any] = {
        val cleanupFutures = (uplinkFlowStateFlows.toSeq ++
                                  uplinks.values().asScala).map(_.rollback())
        uplinks.clear()
        uplinkFlowStateFlows = None
        Future.sequence(cleanupFutures)
    }

    private def addUplink(portId: UUID, routerId: UUID, portAddress: IPv6Subnet,
                          uplinkPortIds: util.List[UUID]): Future[Any] = {
        attachUplink(portId, routerId, portAddress, uplinkPortIds) flatMap {
            _ => attachDownlink()
        }
    }

    private def deleteUplink(portId: UUID): Future[Any] = {
        detachUplink(portId).flatMap {
            _ => detachDownlink()
        }
    }

    private def attachUplink(portId: UUID, routerId: UUID,
                             portAddress: IPv6Subnet,
                             uplinkPortIds: util.List[UUID])
    : Future[VppUplinkSetup] = {
        log debug s"Attaching IPv6 uplink port $portId"

        val dpNumber = datapathState.getDpPortNumberForVport(portId)
        if (dpNumber eq null) {
            return Future.failed(new IllegalArgumentException(
                s"No datapath port for uplink port $portId"))
        }

        if (startVppProcess()) {
            vppApi = createApiConnection(VppConnectMaxRetries)
        }

        val uplinkSetup = new VppUplinkSetup(portId,
                                             portAddress.getAddress,
                                             dpNumber.intValue(),
                                             vt.config.fip64,
                                             VppFlowStateCfg,
                                             vppApi,
                                             vppOvs,
                                             this,
                                             Logger(log.underlying))

        val result = {
            uplinks.put(portId, uplinkSetup) match {
                case null => Future.successful(Unit)
                case previousSetup => previousSetup.rollback()
            }
        } flatMap { _ =>
            uplinkSetup.execute()  map { _ => uplinkSetup }
        } recoverWith { case e =>
            uplinkSetup.rollback() map { _ =>
                uplinks.remove(portId, uplinkSetup)
                throw e
            }
        } andThen {
            case Success(_) =>
                routerPortSubscribers +=
                    portId -> VirtualTopology.observable(classOf[RouterPort],
                                                         portId)
                                             .subscribe(observer)
            case Failure(e) =>
                log warn s"Attaching uplink port $portId failed: $e"
        }

        result
    }

    private def detachUplink(portId: UUID): Future[Option[VppUplinkSetup]] = {
        log debug s"Local port $portId inactive"
        routerPortSubscribers.remove(portId) match {
            case None =>
                log debug s"No subscriber for router port $portId"
            case Some(s) =>
                log debug s"Stop monitoring router port $portId"
                s.unsubscribe()
        }

        def deleteRoutes(portId: UUID): Future[Option[VppUplinkSetup]] = {
            uplinks.get(portId) match {
                case setup: VppUplinkSetup =>
                    setup.deleteAllExternalRoutes() map { _ => Some(setup) }
                case null =>
                    Future.successful(None)
            }
        }

        deleteRoutes(portId) flatMap { _ =>
            uplinks remove portId match {
                case setup: VppUplinkSetup =>
                    log debug s"Port $portId detached"
                    setup.rollback() map { _ => Some(setup) }
                case null => Future.successful(None)
            }
        }
    }

    private def attachDownlink(): Future[_] = {
        log debug s"Attach downlink VXLAN port"

        val setup = new VppDownlinkSetup(vt.config.fip64,
                                         vppApi,
                                         Logger(log.underlying));
        {
            downlink match {
                case None => Future.successful(Unit)
                case Some(previousSetup) => previousSetup.rollback()
            }
        } flatMap { _ =>
            downlink = Some(setup)
            setup.execute()
        } recoverWith { case e =>
            setup.rollback() map { _ =>
                downlink = None
                throw e
            }
        }
    }

    private def detachDownlink() : Future[_] = {
        log debug s"Detaching downlink VXLAN port"
        downlink match {
            case None => Future.failed(null)
            case Some(previousSetup) =>
                downlink = None
                previousSetup.rollback()
        }
    }

    private def createTunnel(portId: UUID,
                             vrf: Int, vni: Int,
                             routerPortMac: MAC) : Future[_] = {
        log debug s"Creating downlink tunnel for port:$portId VRF:$vrf VNI:$vni"
        val setup = new VppVxlanTunnelSetup(vt.config.fip64,
                                            vni, vrf, routerPortMac,
                                            vppApi, Logger(log.underlying))

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
                vxlanTunnels.put(portId, setup)
        } recoverWith { case e =>
            setup.rollback() map {
                vxlanTunnels.remove(portId, setup)
                throw e
            }
        }
    }

    private def deleteTunnel(portId: UUID, vni: Int) : Future[_] = {
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

    private def addExternalNetwork(networkId: UUID): Future[_] = {
        if (!externalNetworks.containsKey(networkId)) {
            externalNetworks.put(networkId,
                                 new ExternalNetwork(networkId, hostId, vt))
        }
        Future.successful(Unit)
    }

    private def removeExternalNetwork(networkId: UUID): Future[_] = {
        val state = externalNetworks.remove(networkId)
        if (state ne null) {
            state.complete()
        }
        Future.successful(Unit)
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

    @throws[TimeoutException]
    private def startVppProcess(): Boolean = synchronized {
        if (vppExiting)
            return false
        if (vppProcess ne null)
            return false

        log debug "Starting VPP process"
        vppProcess = new MonitoredDaemonProcess(
            "/usr/share/midolman/vpp-start", log.underlying, "org.midonet.vpp",
            VppProcessMaximumStarts, VppProcessTimeout.toMillis,
            vppExitAction)
        vppProcess.startAsync().awaitRunning(VppProcessTimeout.toMillis,
                                             TimeUnit.MILLISECONDS)

        log debug "VPP process started"
        true
    }

    private def stopVppProcess(): Unit = synchronized {
        log debug "Stopping VPP process"

        // flag vpp exiting so midolman isn't stopped by the exit action
        vppExiting = true
        if ((vppProcess ne null) && vppProcess.isRunning) {
            try vppProcess.stopAsync().awaitTerminated(VppProcessTimeout.toMillis,
                                                       TimeUnit.MILLISECONDS)
            catch {
                case e: TimeoutException =>
                    log info "Stopping VPP process timed out after " +
                             s"$VppProcessTimeout"
            }
            vppProcess = null
        }
    }

    private def associateFip(portId: UUID, vrf: Int, vni: Int,
                             floatingIp: IPv6Addr, fixedIp: IPv4Addr,
                             localIp: IPv4Subnet, natPool: IPv4Subnet)
    : Future[_] = {
        log debug s"Associating FIP at port $portId (VRF $vrf, VNI $vni): " +
                  s"$floatingIp -> $fixedIp"

        poolFor(portId, natPool) flatMap { pool =>
            if (pool.nonEmpty) {
                log debug s"Allocated NAT pool at port $portId is ${pool.get}"
                vppCtl(s"ip route table $vrf add $fixedIp/32 " +
                       s"via ${vt.config.fip64.vppInternalGateway}") flatMap { _ =>
                    vppCtl(s"fip64 add $floatingIp $fixedIp " +
                           s"pool ${pool.get.start} ${pool.get.end}" +
                           s" table $vrf vni $vni")
                }
            } else {
                // We complete the future successfully, since there is nothing
                // to rollback.
                Future.successful(Unit)
            }
        }
    }

    private def disassociateFip(portId: UUID, vrf: Int, floatingIp: IPv6Addr,
                                fixedIp: IPv4Addr, localIp: IPv4Subnet)
    : Future[_] ={
        log debug s"Disassociating FIP at port $portId (VRF $vrf): " +
                  s"$floatingIp -> $fixedIp"

        vppCtl(s"fip64 del $floatingIp") flatMap { _ =>
            vppCtl(s"ip route table $vrf del $fixedIp/32")
        }
    }

    private def handleExternalRoutesUpdate(port: RouterPort): Future[_] = {

        vt.store.getAll(classOf[Route], port.routeIds.toSeq) flatMap { routes =>
            val vppUplinkSetup = uplinks.get(port.id)
            val oldRoutes = vppUplinkSetup.getExternalRoutes
            val newRoutes = routes.filter { route =>
                route.hasNextHopGateway &&
                route.getDstSubnet.getVersion == IPVersion.V6
            }.toSet

            val addedFutures =
                for (route <- newRoutes if !oldRoutes.contains(route)) yield {
                    vppUplinkSetup.addExternalRoute(route)
                }
            val removedFutures =
                for (route <- oldRoutes if !newRoutes.contains(route)) yield {
                    vppUplinkSetup.deleteExternalRoute(route)
                }

            Future.sequence(addedFutures ++ removedFutures)
        }
    }

    private def updateFlowStateFlows(hosts:immutable.Set[UUID]) : Future[Any] = {
        if (hosts.isEmpty) {
            removeFlowStateFlows()
        } else {
            val tunnelRoutes = hosts.flatMap(datapathState.peerTunnelInfo)
            val vppPort = datapathState.tunnelFip64VxLanPort.getPortNo
            val underlayPorts = Seq(
                datapathState.tunnelOverlayGrePort.getPortNo.toInt,
                datapathState.tunnelOverlayVxLanPort.getPortNo.toInt)

            val newSetup = new Fip64FlowStateFlows(vppOvs, vppPort,
                                                   underlayPorts,
                                                   tunnelRoutes.toSeq,
                                                   Logger(log.underlying))
            val previous = uplinkFlowStateFlows
            uplinkFlowStateFlows = Some(newSetup)

            previous match {
                case Some(flows) =>
                    flows.rollback() flatMap { _ =>
                        newSetup.execute()
                    }
                case None =>
                    newSetup.execute()
            }
        }
    }

    private def removeFlowStateFlows(): Future[Any] = {
        uplinkFlowStateFlows match {
            case Some(setup) =>
                uplinkFlowStateFlows = None
                setup.rollback()
            case _ => Future.successful(Unit)
        }
    }
}


