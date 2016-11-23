/*
 * Copyright 2015 Midokura SARL
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
package org.midonet.midolman.routingprotocols

import java.io.File
import java.util.UUID

import scala.collection.breakOut
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Try
import scala.util.control.NonFatal

import akka.actor.ActorRef

import org.apache.zookeeper.KeeperException

import rx.{Observer, Subscription}

import org.midonet.cluster.backend.zookeeper.{StateAccessException, ZkConnectionAwareWatcher}
import org.midonet.midolman._
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.io.{UpcallDatapathConnectionManager, VirtualMachine}
import org.midonet.midolman.layer3.Route
import org.midonet.midolman.layer3.Route.NextHop
import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.midolman.routingprotocols.RoutingManagerActor.RoutingStorage
import org.midonet.midolman.routingprotocols.RoutingWorkflow.RoutingInfo
import org.midonet.midolman.simulation.RouterPort
import org.midonet.midolman.topology.devices.{BgpPort, BgpPortDeleted, BgpRouterDeleted}
import org.midonet.midolman.topology.{PortBgpInfo, RouterBgpMapper, VirtualTopology}
import org.midonet.odp.DpPort
import org.midonet.odp.ports.NetDevPort
import org.midonet.packets._
import org.midonet.quagga.BgpdConfiguration.{BgpRouter, Neighbor, Network}
import org.midonet.quagga.ZebraProtocol.RIBType
import org.midonet.quagga._
import org.midonet.sdn.flows.FlowTagger
import org.midonet.sdn.flows.FlowTagger.FlowTag
import org.midonet.util.concurrent.ReactiveActor.{OnCompleted, OnError}
import org.midonet.util.concurrent.{ConveyorBelt, ReactiveActor, SingleThreadExecutionContextProvider}
import org.midonet.util.eventloop.SelectLoop
import org.midonet.util.{AfUnix, UnixClock}

class LazyZkConnectionMonitor(down: () => Unit,
                              up: () => Unit,
                              connWatcher: ZkConnectionAwareWatcher,
                              downEventDelay: FiniteDuration,
                              clock: UnixClock,
                              schedule: (FiniteDuration, Runnable) => Unit) {

    implicit def f2r(f: () => Unit): Runnable = new Runnable() { def run() = f() }

    private val lock = new Object

    private var lastDisconnection: Long = 0L
    private var connected = true

    private val Now = 0 seconds

    connWatcher.scheduleOnReconnect(onReconnect _)
    connWatcher.scheduleOnDisconnect(onDisconnect _)

    private def disconnectedFor: Long = clock.time - lastDisconnection

    private def onDelayedDisconnect() = {
        lock.synchronized {
            if (!connected && (disconnectedFor >= downEventDelay.toMillis))
                down()
        }
    }

    private def onDisconnect() {
        schedule(Now, () => lock.synchronized {
            lastDisconnection = clock.time
            connected = false
        })
        schedule(downEventDelay, onDelayedDisconnect _)
        connWatcher.scheduleOnDisconnect(onDisconnect _)
    }

    private def onReconnect() {
        schedule(Now, () => lock.synchronized {
            connected = true
            up()
        })
        connWatcher.scheduleOnReconnect(onReconnect _)
    }
}

object RoutingHandler {

    final val BgpTcpPort: Short = 179

    /** A conventional value for Ip prefix of BGP pairs.
      *  172 is the MS byte value and 23 the second byte value.
      *  Last 2 LS bytes are available for assigning BGP pairs. */
    private val BgpIpIntPrefix = 172 * (1 << 24) + 23 * (1 << 16)

    private val NoUplink = -1
    private val NoPort = -1

    // BgpdProcess will notify via these messages
    case object FetchBgpdStatus
    case object SyncPeerRoutes

    case class PeerRoute(destination: IPv4Subnet, gateway: IPv4Addr)

    case class PortActive(active: Boolean)

    case class PortBgpInfos(cidrs: Seq[PortBgpInfo])

    case class ZookeeperConnected(connected: Boolean)

    case class AddPeerRoutes(destination: IPv4Subnet, paths: Set[ZebraPath])

    case class RemovePeerRoute(ribType: RIBType.Value,
                               destination: IPv4Subnet,
                               gateway: IPv4Addr)

    case class Update(config: BgpRouter, peerIds: Set[UUID])


    def apply(routerPort: RouterPort, bgpIdx: Int,
              flowInvalidator: SimulationBackChannel,
              dpState: DatapathState,
              upcallConnManager: UpcallDatapathConnectionManager,
              routingStorage: RoutingStorage,
              config: MidolmanConfig,
              connWatcher: ZkConnectionAwareWatcher,
              selectLoop: SelectLoop,
              vt: VirtualTopology,
              isContainer: Boolean) =
        new RoutingHandler(routerPort, bgpIdx, flowInvalidator.tell,
                           routingStorage, config, connWatcher, isContainer) {

            import context.system

            private var zebra: ActorRef = _

            private final val bgpVtyLocalIp =
                new IPv4Subnet(IPv4Addr.fromInt(BgpIpIntPrefix + 1 + 4 * bgpIdx), 30)
            private final val bgpVtyPeerIp =
                new IPv4Subnet(IPv4Addr.fromInt(BgpIpIntPrefix + 2 + 4 * bgpIdx), 30)

            protected override val bgpd = DefaultBgpdProcess(
                portId = routerPort.id,
                routerId = routerPort.routerId,
                name = name,
                index = bgpIdx,
                localVtyIp = bgpVtyLocalIp,
                remoteVtyIp = bgpVtyPeerIp,
                routerIp = new IPv4Subnet(routerPort.portAddressV4,
                                          routerPort.portSubnetV4.getPrefixLen),
                routerMac = routerPort.portMac,
                vtyPortNumber = bgpVtyPort)

            private val lazyConnWatcher = new LazyZkConnectionMonitor(
                () => self ! ZookeeperConnected(false),
                () => self ! ZookeeperConnected(true),
                connWatcher,
                config.router.bgpZookeeperHoldtime seconds,
                UnixClock.DEFAULT,
                (d, r) => system.scheduler.scheduleOnce(d, r)(system.dispatcher))

            private val zebraHandler = new ZebraProtocolHandler {
                def addRoutes(destination: IPv4Subnet, paths: Set[ZebraPath]) {
                    self ! AddPeerRoutes(destination, paths)
                }

                def removeRoute(ribType: RIBType.Value, destination: IPv4Subnet,
                                gateway: IPv4Addr) {
                    self ! RemovePeerRoute(ribType, destination, gateway)
                }
            }

            private var portBgpSub: Subscription = _

            override def preStart(): Unit = {
                log.debug("Starting routing handler...")
                super.preStart()

                // Subscribe to the BGP port mapper for BGP updates.
                bgpSubscription = VirtualTopology.observable(classOf[BgpPort],
                                                             routerPort.id)
                                                 .subscribe(this)

                if (isContainer) {
                    val portBgpObs = new RouterBgpMapper(routerPort.routerId, vt)
                        .portBgpInfoObservable
                    portBgpSub = portBgpObs.subscribe(new PortBgpObserver)
                }

                system.scheduler.schedule(2 seconds, 5 seconds,
                                          self, FetchBgpdStatus)(context.dispatcher)
                log.debug("Routing handler started")
            }

            override def postStop(): Unit = {
                log.debug("Stopping routing handler...")
                super.postStop()
                if (portBgpSub != null)
                    portBgpSub.unsubscribe()
                currentPortBgps = Seq()
                newPortBgps = Seq()
                log.debug("Routing handler stopped")
            }

            class PortBgpObserver extends Observer[Seq[PortBgpInfo]] {
                override def onCompleted(): Unit = {}

                override def onError(e: Throwable): Unit = {
                    // TODO: Error handling.
                }

                override def onNext(newPortBgpInfos: Seq[PortBgpInfo]): Unit = {
                    self ! PortBgpInfos(newPortBgpInfos)
                }
            }

            override def createDpPort(port: String): Future[(DpPort, Int)] = {
                log.debug(s"Creating port $port")
                upcallConnManager.createAndHookDpPort(
                    dpState.datapath, new NetDevPort(port), VirtualMachine)(
                        singleThreadExecutionContext, system)

            }

            override def deleteDpPort(port: NetDevPort) : Future[_] = {
                log.debug(s"Removing port ${port.getName}")
                upcallConnManager.deleteDpPort(dpState.datapath, port)(
                    singleThreadExecutionContext, system)
            }

            override def startZebra(): Unit = {
                val zebraSocketFile = new File(s"/var/run/quagga/zserv$name.api")
                if (zebraSocketFile.exists()) {
                    log.debug("Deleting socket file at {}", zebraSocketFile.getAbsolutePath)
                    zebraSocketFile.delete()
                }

                log.debug("Starting zebra server")
                val socketAddress = new AfUnix.Address(zebraSocketFile.getAbsolutePath)
                zebra = ZebraServer(socketAddress, zebraHandler, routerPort.portAddressV4,
                                    bgpNetdevPortMirrorName, selectLoop)
            }

            override def stopZebra(): Unit = {
                log.debug("Stopping zebra server")
                if (zebra ne null) {
                    context.stop(zebra)
                }
            }
        }
}

abstract class RoutingHandler(var routerPort: RouterPort,
                              val bgpIdx: Int,
                              val flowInvalidator: FlowTag => Unit,
                              val routingStorage: RoutingStorage,
                              val config: MidolmanConfig,
                              val connWatcher: ZkConnectionAwareWatcher,
                              val isContainer: Boolean)
    extends ReactiveActor[BgpPort] with ActorLogWithoutPath
            with SingleThreadExecutionContextProvider {

    import RoutingHandler._

    override def logSource = "org.midonet.routing.bgp"
    override def logMark = s"bgp:${routerPort.id}:$bgpIdx"

    protected final val name =
        if (isContainer) routerPort.interfaceName
        else s"bgp-${routerPort.id.toString.substring(0, 7)}"

    protected final val bgpNetdevPortName = name
    protected final val bgpNetdevPortMirrorName = s"${name}_m"
    protected final val bgpVtyPort = 2605 + bgpIdx

    private val peerRoutes = mutable.Map[Route, Route]()
    private var bgpConfig: BgpRouter = BgpRouter(-1)
    private var bgpPeerIds: Set[UUID] = Set.empty

    /** IP addresses bgpd currently has configured. */
    protected var currentPortBgps: Seq[PortBgpInfo] = Seq()

    /** IP addresses to update bgpd with on startup/restart. */
    protected var newPortBgps: Seq[PortBgpInfo] = Seq()
    protected val peerRouteToPort = mutable.Map[PeerRoute, UUID]()

    /* IP address to use as the router-id.
     * Only used with BGP on Interior ports
     */
    private val routerBgpId = IPv4Addr.randomPrivate
    private val routingInfo: RoutingInfo = RoutingInfo(routerPort.id, NoUplink,
                                                       NoPort)
    private var datapathPort: Option[NetDevPort] = None

    protected val belt = new ConveyorBelt(e => {
        log.error("Error while processing message ", e)
    })
    private val learnedRoutesHandler = new Runnable {
        override def run(): Unit = {self ! SyncPeerRoutes }
    }

    private var zookeeperConnected = true
    private var portActive = true

    protected[this] var bgpSubscription: Subscription = _

    private def bgpdShouldRun = {
        zookeeperConnected &&
        portActive &&
        bgpConfig.as > 0 &&
        (isContainer || bgpConfig.neighbors.nonEmpty)
    }

    protected def createDpPort(port: String): Future[(DpPort, Int)]
    protected def deleteDpPort(port: NetDevPort): Future[_]
    protected def startZebra(): Unit
    protected def stopZebra(): Unit
    protected def bgpd: BgpdProcess

    override def postStop(): Unit = {
        super.postStop()
        if (bgpSubscription ne null) {
            bgpSubscription.unsubscribe()
            bgpSubscription = null
        }
        stopBgpd()
    }

    private val eventHandlerBase: PartialFunction[Any, Future[_]] = {
        case PortActive(status) =>
            log.debug("Port became: {}", if (status) "active" else "inactive")
            portActive = status
            startOrStopBgpd()

        case PortBgpInfos(portBgpInfos) =>
            try {
                log.debug("Received CIDRs from observable: {}", portBgpInfos)
                newPortBgps = portBgpInfos
                if (bgpd.isAlive) {
                    bootstrapBgpdConfig()
                }
                Future.successful(true)
            } catch {
                case e: Exception =>
                log.warn("Could not update CIDRs in Quagga namespace", e)
                Future.failed(e)
            }

        case ZookeeperConnected(status) =>
            log.debug("Zookeeper session became: {}", if (status) "connected"
                                                      else "disconnected")
            zookeeperConnected = status
            if (status)
                self ! FetchBgpdStatus
            startOrStopBgpd()

        case FetchBgpdStatus => updateBgpdStatus()

        case SyncPeerRoutes =>
            syncPeerRoutes()
            Future.successful(true)

        case AddPeerRoutes(destination, paths) =>
            publishLearnedRoutes(destination, paths)
            Future.successful(true)

        case RemovePeerRoute(ribType, destination, gateway) =>
            log.debug(s"Forgetting route: $ribType, $destination, $gateway")

            val portId = peerRouteToPort.remove(PeerRoute(destination, gateway))
                .getOrElse(routerPort.id)
            val route = makeRoute(destination, gateway.toString, portId)
            peerRoutes.remove(route) match {
                case None => // route missing
                case Some(null) => // route not published
                case Some(r) => handleLearnedRouteError(forgetLearnedRoute(r))
            }
            Future.successful(true)
    }

    private val eventHandler: PartialFunction[Any, Future[_]] = eventHandlerBase orElse {
        case BgpPort(port, router, neighborIds) =>
            log.debug("BGP port and configuration changed {} {}", port, router)
            portUpdated(port).flatMap[Any] { _ =>
                configurationUpdated(router, neighborIds)
            }(singleThreadExecutionContext)

        case OnError(BgpPortDeleted(portId)) =>
            log.warn("Port {} deleted: stopping BGP daemon", portId)
            stopBgpd()

        case OnError(BgpRouterDeleted(portId, routerId)) =>
            log.warn("Router {} for port {} deleted: stopping BGP daemon",
                     routerId, portId)
            stopBgpd()

        case OnError(e) =>
            log.error("Error on BGP port observable: stopping BGP daemon", e)
            stopBgpd()

        case OnCompleted =>
            log.error("Unexpected completion of the BGP observable: stopping " +
                      "BGP daemon")
            stopBgpd()
    }

    override def receive = super.receive orElse {
        case m if eventHandler.isDefinedAt(m) =>
            belt.handle(() => eventHandler(m))
        case m => log.warn(s"$m: ignoring unrecognized message")
    }

    private def portUpdated(port: RouterPort): Future[_] = {
        if (bgpd.isAlive) {
            if (port.portAddressV4 != routerPort.portAddressV4 ||
                port.portSubnetV4 != routerPort.portSubnetV4 ||
                port.portMac != routerPort.portMac) {
                log.debug("Port addresses changed, restarting BGP daemon")
                routerPort = port
                restartBgpd()
            } else {
                log.debug("Port addresses did not change")
                routerPort = port
                Future.successful(true)
            }
        } else {
            log.debug("Port changed but BGP daemon is down")
            routerPort = port
            Future.successful(true)
        }
    }

    private def configurationUpdated(router: BgpRouter, peers: Set[UUID]): Future[_] = {
        log.debug("BGP configuration changed")
        Try(update(router, peers))
            .getOrElse(checkBgpdHealth())
    }

    private def forgetLearnedRoute(route: Route): Future[Route] = {
        log.debug(s"Forgetting learned route: " +
                  s"${route.getDstNetworkAddr}/${route.dstNetworkLength} " +
                  s"via ${route.getNextHopGateway}")
        routingStorage.removeRoute(route, routerPort.id)
    }

    private def makeRoute(destination: IPv4Subnet, path: ZebraPath): Route = {
        val portId = currentPortBgps.find(_.bgpPeerIp == path.gateway.toString)
            .map(_.portId)
            .getOrElse(routerPort.id)
        peerRouteToPort.put(PeerRoute(destination, path.gateway), portId)

        val route = makeRoute(destination, path.gateway.toString, portId)
        route.weight = path.distance
        route
    }

    private def makeRoute(destination: IPv4Subnet, gwIp: String,
                          portId: UUID): Route = {
        val route = new Route()
        route.routerId = routerPort.deviceId
        route.dstNetworkAddr = destination.getAddress.toInt
        route.dstNetworkLength = destination.getPrefixLen
        route.nextHopGateway = IPv4Addr.stringToInt(gwIp)
        route.nextHop = NextHop.PORT
        route.nextHopPort = portId
        route.learned = true
        route
    }


    /*
     * Publishes routes to a prefix.
     *
     * Note that what we get from bgpd via ZebraConnection is the set
     * of currently active routes to the particular prefix. This means
     * that the actual event being handled is a path being deleted.
     *
     * In other words, here we get the set of currently active paths and
     * that set could differ with the previously active set in any ways.
     *
     * An additional requirement here is to handle zookeeper disconnections
     * gracefully. Thus, here's what this method needs to do:
     *
     *   * Calculate which paths are new, by comparing the received set of
     *     paths with the authoritative 'peerRoutes' variable.
     *   * Likewise, calculate which routes were previously known hand have
     *     to be forgotten.
     *   * Save the new set of routes to this prefix in 'peerRoutes'. At this
     *     point it's safe to try to commit the changes to storage. A failure
     *     will be handled by resynchronizing storage with 'peerRoutes'.
     *   * Commit the new paths to storage, delete the forgotten paths from
     *     storage.
     */
    private def publishLearnedRoutes(destination: IPv4Subnet, paths: Set[ZebraPath]): Unit = {
        val newRoutes = paths map (makeRoute(destination, _))

        val lostRoutes = mutable.Buffer[Route]()
        for (route <- peerRoutes.keys
             if route.dstNetworkAddr == destination.getAddress.toInt &&
                 route.dstNetworkLength == destination.getPrefixLen &&
                 !newRoutes.contains(route) &&
                 (peerRoutes(route) ne null)) {
            lostRoutes.append(peerRoutes(route))
            peerRoutes.remove(route)
        }

        val gainedRoutes = mutable.Buffer[Route]()
        for (gained <- newRoutes if !peerRoutes.contains(gained)) {
            if (peerRoutes.size < config.router.maxBgpPeerRoutes) {
                gainedRoutes.append(gained)
                peerRoutes.put(gained, null)
            } else {
                log.warn("Max number of peer routes reached " +
                         s"(${config.router.maxBgpPeerRoutes}), please check " +
                         "the max_bgp_peer_routes config option.")
            }
        }

        handleLearnedRouteError {
            val futures = new ArrayBuffer[Future[Route]]()
            for (gained <- gainedRoutes) {
                futures += publishLearnedRoute(gained)
            }
            for (lost <- lostRoutes) {
                futures += forgetLearnedRoute(lost)
            }
            Future.sequence(futures)(breakOut, singleThreadExecutionContext)
        }


    }

    private def publishLearnedRoute(route: Route): Future[Route] = {
        log.debug(s"Publishing learned route: " +
                  s"${route.getDstNetworkAddr}/${route.dstNetworkLength} " +
                  s"via ${route.getNextHopGateway}")

        peerRoutes.put(route, null)
        routingStorage.addRoute(route, routerPort.id).map { _ =>
            peerRoutes.put(route, route)
            route
        }(singleThreadExecutionContext)
    }

    private def syncPeerRoutes(): Unit = {
        handleLearnedRouteError {
            routingStorage.learnedRoutes(routerPort.deviceId, routerPort.id, routerPort.hostId)
                          .flatMap {
                learnedRoutes =>
                val futures = new ArrayBuffer[Future[Route]]()
                // Delete routes we don't have anymore
                for (route <- learnedRoutes if !peerRoutes.contains(route)) {
                    futures += forgetLearnedRoute(route)
                }
                // Add routes that were not published
                for ((routeKey, routeValue) <- peerRoutes
                     if routeValue eq null) {
                    futures += publishLearnedRoute(routeKey)
                }
                Future.sequence(futures)(breakOut, singleThreadExecutionContext)
            }(singleThreadExecutionContext)
        }
    }

    private def clearRoutingWorkflow(): Unit = {
        RoutingWorkflow.routerPortToDatapathInfo.remove(routerPort.id)
        RoutingWorkflow.inputPortToDatapathInfo.remove(routingInfo.dpPortNo)
        routingInfo.dpPortNo = NoPort
        routingInfo.uplinkPid = NoUplink
    }

    private def handleLearnedRouteError(op: => Future[_]): Unit = {
        op.onFailure {
            case e: StateAccessException =>
                connWatcher.handleError(s"BGP learned routes: ${routerPort.id}",
                                        learnedRoutesHandler, e)
            case e: KeeperException =>
                connWatcher.handleError(s"BGP learned routes: ${routerPort.id}",
                                        learnedRoutesHandler, e)
            case NonFatal(e) =>
                log.error("BGP learned routes storage operation failed", e)
        }(singleThreadExecutionContext)
    }

    private def updateBgpdStatus(): Future[_] = {
        var status = "BGP daemon is not ready"
        checkBgpdHealth().andThen { case _ =>
            try {
                if (bgpd.isAlive) {
                    status = bgpd.vty.showGeneric("show ip bgp nei").
                        filterNot(_.startsWith("bgpd#")).
                        filterNot(_.startsWith("show ip bgp nei")).
                        foldRight("")((a, b) => s"$a\n$b")
                }
            } catch {
                case NonFatal(e) =>
                    log.warn("Failed to check BGP daemon status", e)
            }

            routingStorage.setStatus(routerPort.id, status).onFailure { case e =>
                log.warn("Failed to set BGP daemon status", e)
            }(singleThreadExecutionContext)
        }(singleThreadExecutionContext)
    }

    private def update(newConfig: BgpRouter, newPeers: Set[UUID]): Future[_] = {
        (bgpd.isAlive match {
            case true if newConfig.as != bgpConfig.as =>
                log.debug("BGP AS number changed, restarting BGP daemon")
                stopBgpd()
            case true =>
                updateLocalNetworks(bgpConfig.networks, newConfig.networks)
                updatePeers(bgpConfig.neighbors.values.toSet, newConfig.neighbors.values.toSet)
                Future.successful(true)
            case false => // ignored
                Future.successful(true)
        }).flatMap { _ =>
            bgpConfig = newConfig
            bgpPeerIds = newPeers
            startOrStopBgpd()
        }(singleThreadExecutionContext)
    }

    private def updatePeers(currentPeers: Set[Neighbor], newPeers: Set[Neighbor]): Unit = {
        val lost = currentPeers.diff(newPeers)
        val gained = newPeers.diff(currentPeers)

        for (peer <- lost) {
            log.debug(s"Forgetting BGP neighbor ${peer.as} at ${peer.address}")
            bgpd.vty.deletePeer(bgpConfig.as, peer.address)
            routingInfo.peers.remove(peer.address)
        }

        for (peer <- gained) {
            bgpd.vty.addPeer(bgpConfig.as, peer)
            routingInfo.peers.add(peer.address)
            if (isContainer) {
                bgpd.vty.addPeerBgpIpOpts(bgpConfig.as, peer.address)
            } else {
                bgpd.vty.addPeerExteriorBgpOpts(bgpConfig.as, peer.address)
            }
            log.debug(s"Set up BGP session with AS ${peer.as} at ${peer.address}")
        }

        if (!isContainer && newPeers.isEmpty)
            stopBgpd()
        else
            bgpd.vty.setMaximumPaths(bgpConfig.as, newPeers.size)

        if (lost.nonEmpty || gained.nonEmpty)
            invalidateFlows()
    }

    private def bootstrapBgpdConfig(): Unit = {
        log.debug(s"Configuring BGP daemon")

        bgpd.vty.setAs(bgpConfig.as)
        if (isContainer) {
            bgpd.vty.setRouterId(bgpConfig.as, routerBgpId)
        } else {
            bgpd.vty.setRouterId(bgpConfig.as, bgpConfig.id)
        }

        if (bgpConfig.neighbors.nonEmpty)
            bgpd.vty.setMaximumPaths(bgpConfig.as, bgpConfig.neighbors.size)

        log.debug("Updating local CIDRs from {} to {}", currentPortBgps, newPortBgps)
        for (pbi <- currentPortBgps diff newPortBgps) {
            bgpd.removeArpEntry(routerPort.interfaceName, pbi.bgpPeerIp, pbi.cidr)
            bgpd.removeAddress(routerPort.interfaceName, pbi.cidr)
        }
        for (pbi <- newPortBgps diff currentPortBgps) {
            bgpd.addAddress(routerPort.interfaceName, pbi.cidr, pbi.mac)
            bgpd.addArpEntry(routerPort.interfaceName, pbi.bgpPeerIp,
                             routerPort.portMac.toString, pbi.cidr)
        }
        currentPortBgps = newPortBgps

        for (neigh <- bgpConfig.neighbors.values) {
            bgpd.vty.addPeer(bgpConfig.as, neigh)
            routingInfo.peers.add(neigh.address)
            if (isContainer && newPortBgps.nonEmpty) {
                bgpd.vty.addPeerBgpIpOpts(bgpConfig.as, neigh.address)
            } else if (!isContainer) {
                bgpd.vty.addPeerExteriorBgpOpts(bgpConfig.as, neigh.address)
            }
            log.debug(s"Set up BGP session with AS ${neigh.as} at ${neigh.address}")
        }

        for (net <- bgpConfig.networks) {
            log.debug(s"Announcing route to peers: ${net.cidr}")
            bgpd.vty.addNetwork(bgpConfig.as, net.cidr)
        }

        if (log.underlying.isDebugEnabled)
            bgpd.vty.setDebug(enabled = true)
    }

    private def updateLocalNetworks(currentNetworks: Set[Network],
                                    newNetworks: Set[Network]): Unit = {
        val lost = currentNetworks.diff(newNetworks)
        val gained = newNetworks.diff(currentNetworks)

        for (net <- lost) {
            log.debug(s"Withdrawing route announcement: ${net.cidr}")
            bgpd.vty.deleteNetwork(bgpConfig.as, net.cidr)
        }

        for (net <- gained) {
            log.debug(s"Announcing route to peers: ${net.cidr}")
            bgpd.vty.addNetwork(bgpConfig.as, net.cidr)
        }
    }

    private def bgpdPrepare(): Future[(DpPort, Int)] = {
        log.debug("Preparing environment for the BGP daemon")

        try {
            startZebra()
            if (isContainer) {
                bgpd.prepare()
                Future.successful((null, -1))
            } else {
                bgpd.prepare()
                createDpPort(bgpNetdevPortName)
            }
        } catch {
            case e: Exception =>
                log.warn("Could not prepare BGP environment", e)
                Future.failed(e)
        }
    }

    private def startBgpd(): Future[_] = {
        bgpdPrepare().map {
            case (dpPort, pid) =>
                log.debug("Datapath port is ready, starting BGP")

                for (peer <- bgpConfig.neighbors.values) {
                    routingInfo.peers.add(peer.address)
                }

                if (dpPort != null) {
                    routingInfo.uplinkPid = pid
                    datapathPort = Some(dpPort.asInstanceOf[NetDevPort])
                    routingInfo.dpPortNo = dpPort.getPortNo
                    RoutingWorkflow.inputPortToDatapathInfo
                        .put(dpPort.getPortNo, routingInfo)
                    RoutingWorkflow.routerPortToDatapathInfo
                        .put(routerPort.id, routingInfo)
                    invalidateFlows()
                }
                bgpd.start()
                bootstrapBgpdConfig()
                true
        }(singleThreadExecutionContext).recoverWith {
            case e =>
                log.warn("Could not initialize the BGP daemon", e)
                stopBgpd()
        }(singleThreadExecutionContext)
    }

    private def stopBgpd(): Future[_] = {
        log.debug("Disabling BGP")
        clearRoutingWorkflow()
        stopZebra()

        log.debug("Stopping BGP daemon")
        bgpd.stop()
        invalidateFlows()
        handleLearnedRouteError {
            val futures = new ArrayBuffer[Future[Route]]()
            for (route <- peerRoutes.values) {
                futures += forgetLearnedRoute(route)
            }
            Future.sequence(futures)(breakOut, singleThreadExecutionContext)
        }
        peerRoutes.clear()
        removeDpPort()
    }

    protected def restartBgpd(): Future[_] = {
        stopBgpd().flatMap { _ => startBgpd() } (singleThreadExecutionContext)
    }

    private def checkBgpdHealth(): Future[_] = {
        if (bgpdShouldRun && !bgpd.isAlive) {
            log.warn("BGP daemon died, restarting")
            restartBgpd()
        } else {
            Future.successful(true)
        }
    }

    private def startOrStopBgpd(): Future[_] = {
        if (bgpdShouldRun && !bgpd.isAlive)
            startBgpd()
        else if (bgpd.isAlive && !bgpdShouldRun)
            stopBgpd()
        else
            Future.successful(true)
    }

    private def removeDpPort(): Future[_] = {
        val f = datapathPort map deleteDpPort getOrElse Future.successful(true)
        datapathPort = None
        f
    }

    private def invalidateFlows(): Unit = {
        log.debug("Invalidating BGP flows")
        val tag = FlowTagger.tagForDpPort(routingInfo.dpPortNo)
        flowInvalidator(tag)
    }
}
