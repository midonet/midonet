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
import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{Await, Future, Promise}
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

import com.typesafe.scalalogging.Logger

import rx.subscriptions.CompositeSubscription
import rx.{Observer, Subscription}

import org.midonet.cluster.data.storage.StateTableEncoder.GatewayHostEncoder
import org.midonet.cluster.models.Commons.IPVersion
import org.midonet.cluster.models.Topology.Route
import org.midonet.cluster.services.MidonetBackend
import org.midonet.midolman.Midolman.MIDOLMAN_ERROR_CODE_VPP_PROCESS_DIED
import org.midonet.midolman.rules.NatTarget
import org.midonet.midolman.simulation.RouterPort
import org.midonet.midolman.topology.VirtualTopology
import org.midonet.midolman.vpp.VppDownlink._
import org.midonet.midolman.vpp.VppExecutor.Receive
import org.midonet.midolman.vpp.VppUplink.{AddUplink, DeleteUplink}
import org.midonet.midolman.{DatapathState, Midolman}
import org.midonet.packets.{IPv4Addr, IPv4Subnet, IPv6Addr, IPv6Subnet, MAC}
import org.midonet.util.process.ProcessHelper.OutputStreams._
import org.midonet.util.process.{MonitoredDaemonProcess, ProcessHelper}

object VppController {

    val VppProcessMaximumStarts = 3
    val VppProcessTimeout = 2 seconds
    val VppRollbackTimeout = 5 seconds
    val VppCtlTimeout = 1 minute

    private val VppConnectionName = "midonet"
    private val VppConnectMaxRetries = 10
    private val VppConnectDelayMs = 1000

    private case class BoundPort(setup: VppSetup)
    private type LinksMap = util.Map[UUID, BoundPort]
    private object Cleanup

}


class VppController(hostId: UUID,
                    datapathState: DatapathState,
                    vppOvs: VppOvs,
                    protected override val vt: VirtualTopology)
    extends VppExecutor with VppUplink with VppDownlink {

    import VppController._

    override def logSource = "org.midonet.vpp-controller"

    private implicit def executionContext = ec

    private var vppProcess: MonitoredDaemonProcess = _
    private var vppApi: VppApi = _

    private var routerPortSubscribers = mutable.Map[UUID, Subscription]()
    private val uplinks: LinksMap = new util.HashMap[UUID, BoundPort]
    private val downlinks: LinksMap = new util.HashMap[UUID, BoundPort]
    private var downlinkVxlan: Option[VppDownlinkVxlanSetup] = None

    private val vxlanTunnels = new util.HashMap[UUID, VppVxlanTunnelSetup]

    private lazy val gatewayTable = vt.backend.stateTableStore
        .getTable[UUID, AnyRef](MidonetBackend.GatewayTable)

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

        case AddUplink(portId, portAddress, uplinkPortIds) =>
            addUplink(portId, portAddress)
        case DeleteUplink(portId) =>
            deleteUplink(portId)
        case CreateTunnel(portId, vrf, vni, routerPortMac) =>
            createTunnel(portId, vrf, vni, routerPortMac)
        case DeleteTunnel(portId, vrf, vni) =>
            deleteTunnel(portId, vni)
        case AssociateFip(portId, vrf, floatingIp, fixedIp, localIp, natPool) =>
            associateFip(portId, vrf, floatingIp, fixedIp, localIp, natPool)
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
        startUplink()
        notifyStarted()
    }

    protected override def doStop(): Unit = {
        // Unsubscribe from all current subscriptions and execute the cleanup
        // on the conveyor belt.
        stopUplink()
        try Await.ready(send(Cleanup), VppRollbackTimeout)
        catch {
            case NonFatal(e) => log warn s"Cleanup failed ${e.getMessage}"
        }

        stopVppProcess()
        super.doStop()
        notifyStopped()
    }

    private def cleanup(): Future[Any] = {
        val cleanupFutures =
            uplinks.values().asScala.map(_.setup.rollback()) ++
            downlinks.values().asScala.map(_.setup.rollback())

        Future.sequence(cleanupFutures) andThen { case _ =>
            uplinks.clear()
            downlinks.clear()
        }
    }

    private def attachLink(links: LinksMap, portId: UUID, setup: VppSetup)
    : Future[BoundPort] = {
        val boundPort = BoundPort(setup)

        {
            links.put(portId, boundPort) match {
                case null => Future.successful(Unit)
                case previousBinding: BoundPort =>
                    previousBinding.setup.rollback()
            }
        } flatMap { _ =>
            setup.execute() map { _ => boundPort }
        } recoverWith { case e =>
            setup.rollback() map { _ =>
                links.remove(portId, boundPort)
                throw e
            }
        }
    }

    private def detachLink(links: LinksMap, portId: UUID): Future[Option[BoundPort]] = {
        links remove portId match {
            case boundPort: BoundPort =>
                log debug s"Port $portId detached"
                boundPort.setup.rollback() map { _ => Some(boundPort) }
            case null => Future.successful(None)
        }
    }

    private def addUplink(portId: UUID, portAddress: IPv6Subnet): Future[Any] = {
        attachUplink(portId, portAddress) flatMap {
            _ => attachDownlinkVxlan()
        }
    }

    private def deleteUplink(portId: UUID): Future[Any] = {
        detachUplink(portId).flatMap {
            _ => detachDownlinkVxlan()
        }
    }

    private def attachUplink(portId: UUID, portAddress: IPv6Subnet)
    : Future[BoundPort] = {
        log debug s"Attaching IPv6 uplink port $portId"

        val shouldStartDownlink = uplinks.isEmpty
        if (startVppProcess()) {
            vppApi = createApiConnection(VppConnectMaxRetries)
        }

        val dpNumber = datapathState.getDpPortNumberForVport(portId)
        val uplinkSetup = attachLink(uplinks, portId,
                                     new VppUplinkSetup(portId,
                                                        portAddress.getAddress,
                                                        dpNumber, vppApi, vppOvs,
                                                        Logger(log.underlying)))
        routerPortSubscribers +=
            portId -> VirtualTopology.observable(classOf[RouterPort],
                                                 portId)
                                     .subscribe(observer)
        uplinkSetup andThen {
            case Success(_) =>
                if (shouldStartDownlink && !uplinks.isEmpty) {
                    startDownlink()
                }
            case Failure(e) =>
                log warn s"Attaching uplink port $portId failed: $e"
        }
    }

    private def detachUplink(portId: UUID): Future[Option[BoundPort]] = {
        log debug s"Local port $portId inactive"
        routerPortSubscribers.remove(portId) match {
            case None =>
                log debug s"No subscriber for router port $portId"
            case Some(s) =>
                log debug s"Stop monitoring router port $portId"
                s.unsubscribe()
        }

        def deleteRoutes(portId: UUID): Future[Option[BoundPort]] = {
            uplinks.get(portId) match {
                case boundPort: BoundPort =>
                    boundPort.setup.asInstanceOf[VppUplinkSetup].
                        deleteAllExternalRoutes() map { _ => Some(boundPort) }
                case null =>
                    Future.successful(None)
            }
        }

        deleteRoutes(portId) flatMap { _ =>
            detachLink(uplinks, portId)
        } andThen { case _ =>
            if (uplinks.isEmpty) {
                stopDownlink()
            }
        }
    }

    private def attachDownlinkVxlan(): Future[_] = {
        log debug s"Attach downlink VXLAN port"

        val setup = new VppDownlinkVxlanSetup(vt.config.fip64,
                                              vppApi,
                                              Logger(log.underlying));
        {
            downlinkVxlan match {
                case None => Future.successful(Unit)
                case Some(previousSetup) => previousSetup.rollback()
            }
        } flatMap { _ =>
            downlinkVxlan = Some(setup)
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
        val oldDownlinkSetup = downlinkVxlan
        downlinkVxlan = None
        oldDownlinkSetup match {
            case None => Future.failed(null)
            case Some(previousSetup) => previousSetup.rollback()
        }
    }

    private def createTunnel(portId: UUID,
                             vrf: Int, vni: Int,
                             routerPortMac: MAC) : Future[_] = {
        log debug s"Creating downlink tunnel for port:$portId, vrf:$vrf, vni:$vni"
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

        gatewayTable.start()
        gatewayTable.add(hostId, GatewayHostEncoder.DefaultValue)

        log debug "VPP process started"
        true
    }

    private def stopVppProcess(): Unit = synchronized {
        log debug "Stopping VPP process"

        gatewayTable.remove(hostId)
        gatewayTable.stop()

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

    private def associateFip(portId: UUID, vrf: Int, floatingIp: IPv6Addr,
                             fixedIp: IPv4Addr, localIp: IPv4Subnet,
                             natPool: NatTarget): Future[_] = {
        log debug s"Associating FIP at port $portId (VRF $vrf): " +
                  s"$floatingIp -> $fixedIp"

        exec(s"vppctl ip route table $vrf add $fixedIp/32 " +
             s"via ${localIp.getAddress}") flatMap { _ =>
            exec(s"vppctl fip64 add $floatingIp $fixedIp " +
                 s"pool ${natPool.nwStart} ${natPool.nwEnd} table $vrf")
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

    private def handleExternalRoutesUpdate(port: RouterPort): Future[_] = {

        vt.store.getAll(classOf[Route], port.routeIds.toSeq) flatMap { routes =>
            val vppUplinkSetup = uplinks.get(port.id).setup
                .asInstanceOf[VppUplinkSetup]
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
}


