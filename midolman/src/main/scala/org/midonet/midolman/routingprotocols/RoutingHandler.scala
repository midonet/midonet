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

import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Try

import akka.actor.ActorRef

import rx.Subscription

import org.midonet.cluster.Client
import org.midonet.cluster.data.Route
import org.midonet.midolman._
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.io.{UpcallDatapathConnectionManager, VirtualMachine}
import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.midolman.routingprotocols.RoutingManagerActor.RoutingStorage
import org.midonet.midolman.routingprotocols.RoutingWorkflow.RoutingInfo
import org.midonet.midolman.state.{StateAccessException, ZkConnectionAwareWatcher}
import org.midonet.midolman.topology.{VirtualTopology, VirtualTopologyActor}
import org.midonet.midolman.topology.VirtualTopologyActor.PortRequest
import org.midonet.midolman.topology.devices.{BgpRouterDeleted, BgpPortDeleted, BgpPort, RouterPort}
import org.midonet.odp.DpPort
import org.midonet.odp.ports.NetDevPort
import org.midonet.packets._
import org.midonet.quagga.BgpdConfiguration.{BgpRouter, Neighbor, Network}
import org.midonet.quagga.ZebraProtocol.RIBType
import org.midonet.quagga._
import org.midonet.sdn.flows.FlowTagger
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

    connWatcher.scheduleOnReconnect(onReconnect _)
    connWatcher.scheduleOnDisconnect(onDisconnect _)

    private var lastDisconnection: Long = 0L
    private var connected = true

    private val NOW = 0 seconds

    private def disconnectedFor: Long = clock.time - lastDisconnection

    private def onDelayedDisconnect() = {
        lock.synchronized {
            if (!connected && (disconnectedFor >= downEventDelay.toMillis))
                down()
        }
    }

    private def onDisconnect() {
        schedule(NOW, () => lock.synchronized {
            lastDisconnection = clock.time
            connected = false
        })
        schedule(downEventDelay, onDelayedDisconnect _)
        connWatcher.scheduleOnDisconnect(onDisconnect _)
    }

    private def onReconnect() {
        schedule(NOW, () => lock.synchronized {
            connected = true
            up()
        })
        connWatcher.scheduleOnReconnect(onReconnect _)
    }
}

object RoutingHandler {
    val BGP_TCP_PORT: Short = 179

    /** A conventional value for Ip prefix of BGP pairs.
      *  172 is the MS byte value and 23 the second byte value.
      *  Last 2 LS bytes are available for assigning BGP pairs. */
    val BGP_IP_INT_PREFIX = 172 * (1<<24) + 23 * (1<<16)

    val NO_UUID = new UUID(0, 0)

    // BgpdProcess will notify via these messages
    case object FETCH_BGPD_STATUS
    case object SYNC_PEER_ROUTES

    case class PortActive(active: Boolean)

    case class ZookeeperConnected(connected: Boolean)

    case class AddPeerRoute(ribType: RIBType.Value, destination: IPv4Subnet,
                                    gateway: IPv4Addr, distance: Byte)

    case class RemovePeerRoute(ribType: RIBType.Value,
                                       destination: IPv4Subnet,
                                       gateway: IPv4Addr)

    case class Update(config: BgpRouter, peerIds: Set[UUID])


    def apply(rport: RouterPort, bgpIdx: Int,
              flowInvalidator: SimulationBackChannel,
              dpState: DatapathState,
              upcallConnManager: UpcallDatapathConnectionManager,
              client: Client, routingStorage: RoutingStorage,
              config: MidolmanConfig,
              connWatcher: ZkConnectionAwareWatcher,
              selectLoop: SelectLoop) =
        new RoutingHandler(rport, bgpIdx, flowInvalidator.tell,
            routingStorage, config, connWatcher) {

            import context.system

            private var zebra: ActorRef = null
            private val modelTranslator = new BgpModelTranslator(rport.id, config, (r, s) => self ! Update(r, s))

            private final val BGP_VTY_LOCAL_IP =
                new IPv4Subnet(IPv4Addr.fromInt(BGP_IP_INT_PREFIX + 1 + 4 * bgpIdx), 30)
            private final val BGP_VTY_MIRROR_IP =
                new IPv4Subnet(IPv4Addr.fromInt(BGP_IP_INT_PREFIX + 2 + 4 * bgpIdx), 30)

            override protected val bgpd: BgpdProcess = new DefaultBgpdProcess(bgpIdx, BGP_VTY_LOCAL_IP,
                BGP_VTY_MIRROR_IP, rport.portSubnet,
                rport.portMac, BGP_VTY_PORT)

            val lazyConnWatcher = new LazyZkConnectionMonitor(
                () => self ! ZookeeperConnected(false),
                () => self ! ZookeeperConnected(true),
                connWatcher,
                config.router.bgpZookeeperHoldtime seconds,
                UnixClock.DEFAULT,
                (d, r) => system.scheduler.scheduleOnce(d, r)(context.dispatcher))

            private val zebraHandler = new ZebraProtocolHandler {
                def addRoute(ribType: RIBType.Value, destination: IPv4Subnet,
                             gateway: IPv4Addr, distance: Byte) {
                    self ! AddPeerRoute(ribType, destination, gateway, distance)
                }

                def removeRoute(ribType: RIBType.Value, destination: IPv4Subnet,
                                gateway: IPv4Addr) {
                    self ! RemovePeerRoute(ribType, destination, gateway)
                }
            }

            override def preStart(): Unit = {
                log.info(s"Starting, port ${rport.id}")
                super.preStart()

                if (config.zookeeper.useNewStack) {
                    // Subscribe to the BGP port mapper for BGP updates.
                    bgpSubscription = VirtualTopology.observable[BgpPort](rport.id)
                                                     .subscribe(this)
                } else {
                    // Watch the BGP session information for this port.
                    // In the future we may also watch for session configurations of
                    // other routing protocols.
                    client.subscribeBgp(rport.id, modelTranslator)

                    // Subscribe to the VTA for updates to the Port configuration.
                    VirtualTopologyActor ! PortRequest(rport.id, update = true)
                }

                system.scheduler.schedule(2 seconds, 5 seconds,
                                          self, FETCH_BGPD_STATUS)(context.dispatcher)
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
                val zebraSocketFile = new File(s"/var/run/quagga/zserv$bgpIdx.api")
                if (zebraSocketFile.exists()) {
                    log.debug("Deleting socket file at {}", zebraSocketFile.getAbsolutePath)
                    zebraSocketFile.delete()
                }

                log.info("Starting zebra server")
                val socketAddress = new AfUnix.Address(zebraSocketFile.getAbsolutePath)
                zebra = ZebraServer(socketAddress, zebraHandler, rport.portIp,
                    BGP_NETDEV_PORT_MIRROR_NAME, selectLoop)
            }

            override def stopZebra(): Unit = {
                log.debug("Stopping zebra server")
                context.stop(zebra)
            }
        }
}

abstract class RoutingHandler(var rport: RouterPort, val bgpIdx: Int,
                              val flowInvalidator: (BackChannelMessage) => Unit,
                              val routingStorage: RoutingStorage,
                              val config: MidolmanConfig,
                              val connWatcher: ZkConnectionAwareWatcher)
    extends ReactiveActor[BgpPort] with ActorLogWithoutPath
            with SingleThreadExecutionContextProvider {

    import RoutingHandler._

    override def logSource = s"org.midonet.routing.bgp.bgp-$bgpIdx"

    protected final val BGP_NETDEV_PORT_NAME = s"mbgp$bgpIdx"
    protected final val BGP_NETDEV_PORT_MIRROR_NAME = s"mbgp${bgpIdx}_m"

    protected final val BGP_VTY_PORT = 2605 + bgpIdx

    private val peerRoutes = mutable.Map[Route, Route]()
    private var bgpConfig: BgpRouter = BgpRouter(-1)
    private var bgpPeerIds: Set[UUID] = Set.empty

    val NO_UPLINK = -1
    val NO_PORT = -1

    private val routingInfo: RoutingInfo = RoutingInfo(rport.id, NO_UPLINK, NO_PORT)

    private var theDatapathPort: Option[NetDevPort] = None

    protected val belt = new ConveyorBelt(_ => {})

    private var zookeeperConnected = true
    private var portActive = true
    private var updatesActive = true

    @volatile protected[this] var bgpSubscription: Subscription = null

    def bgpdShouldRun = {
        zookeeperConnected && portActive && updatesActive &&
        bgpConfig.neighbors.nonEmpty
    }

    protected def createDpPort(port: String): Future[(DpPort, Int)]
    protected def deleteDpPort(port: NetDevPort): Future[_]
    protected def startZebra(): Unit
    protected def stopZebra(): Unit
    protected val bgpd: BgpdProcess

    override def postStop() {
        super.postStop()
        if (bgpSubscription ne null) {
            bgpSubscription.unsubscribe()
            bgpSubscription = null
        }
        stopBgpd()
        log.debug(s"Stopped on port ${rport.id}")
    }

    private val eventHandlerBase: PartialFunction[Any, Future[_]] = {
        case PortActive(status) =>
            log.info("Port became: {}", if (status) "active" else "inactive")
            portActive = status
            startOrStopBgpd()

        case ZookeeperConnected(status) =>
            log.info("Zookeeper session became: {}", if (status) "connected"
                                                     else "disconnected")
            zookeeperConnected = status
            if (status)
                self ! FETCH_BGPD_STATUS
            startOrStopBgpd()

        case FETCH_BGPD_STATUS => updateBgpdStatus()

        case SYNC_PEER_ROUTES =>
            syncPeerRoutes()
            Future.successful(true)

        case AddPeerRoute(ribType, destination, gateway, distance) =>
            if (peerRoutes.size < config.router.maxBgpPeerRoutes) {
                log.info(s"Learning route: $ribType, $destination, $gateway, $distance")
                val route = new Route()
                route.setRouterId(rport.deviceId)
                route.setDstNetworkAddr(destination.getAddress.toString)
                route.setDstNetworkLength(destination.getPrefixLen)
                route.setNextHopGateway(gateway.toString)
                route.setNextHop(org.midonet.midolman.layer3.Route.NextHop.PORT)
                route.setNextHopPort(rport.id)
                route.setWeight(distance)
                route.setLearned(true)
                handleLearnedRouteError {
                    publishLearnedRoute(route)
                }
            } else {
                log.warn(s"Max number of peer routes reached " +
                         s"(${config.router.maxBgpPeerRoutes}), please check " +
                         s"the max_bgp_peer_routes config option.")
            }
            Future.successful(true)

        case RemovePeerRoute(ribType, destination, gateway) =>
            log.info(s"Forgetting route: $ribType, $destination, $gateway")
            val route = new Route()
            route.setRouterId(rport.deviceId)
            route.setDstNetworkAddr(destination.getAddress.toString)
            route.setDstNetworkLength(destination.getPrefixLen)
            route.setNextHopGateway(gateway.toString)
            route.setNextHop(org.midonet.midolman.layer3.Route.NextHop.PORT)
            route.setNextHopPort(rport.id)
            route.setLearned(true)
            peerRoutes.remove(route) match {
                case None => // route missing
                case Some(null) => // route not published
                case Some(r) => handleLearnedRouteError(forgetLearnedRoute(r))
            }
            Future.successful(true)
    }

    private val eventHandlerV1: PartialFunction[Any, Future[_]] = eventHandlerBase orElse {
        case port: RouterPort if bgpd.isAlive =>
            if (port.portIp != rport.portIp ||
                port.portSubnet != rport.portSubnet ||
                port.portMac != rport.portMac) {
                log.info("Port addresses changed, restarting bgpd")
                rport = port
                restartBgpd()
            } else {
                log.info("Port addresses did not change")
                rport = port
                Future.successful(true)
            }

        case port: RouterPort =>
            log.info("new port but bgpd is down")
            rport = port
            Future.successful(true)

        case Update(newConf, peers) =>
            log.info("BGP configuration changed")
            Try(update(newConf.copy(id = rport.portIp), peers))
                .getOrElse(checkBgpdHealth())

    }

    private val eventHandlerV2: PartialFunction[Any, Future[_]] = eventHandlerBase orElse {
        case BgpPort(port, router) =>
            log.info("BGP port and configuration changed")
            Future.successful(true)
            /*
            Try(update(router.copy(id = rport.portIp), router.neighborIds))
                .getOrElse(checkBgpdHealth())

            if (port.portIp != rport.portIp ||
                port.portSubnet != rport.portSubnet ||
                port.portMac != rport.portMac) {
                log.info("Port addresses changed, restarting bgpd")
                rport = port
                restartBgpd()
            } else {
                log.info("Port addresses did not change")
                rport = port
                Future.successful(true)
            }.flatMap[Any] {
                case _ => Try(update(router, router.neighborIds))
                    .getOrElse(checkBgpdHealth())
            }(singleThreadExecutionContext)*/

        case OnError(BgpPortDeleted(portId)) =>
            log.warn("Port {} deleted: stopping bgpd", portId)
            updatesActive = false
            startOrStopBgpd()

        case OnError(BgpRouterDeleted(portId, routerId)) =>
            log.warn("Router {} for port {} deleted: stopping bgpd",
                     routerId, portId)
            updatesActive = false
            startOrStopBgpd()

        case OnError(e) =>
            log.error("Error on BGP port observable: stopping bgpd", e)
            updatesActive = false
            startOrStopBgpd()

        case OnCompleted =>
            log.error("Unexpected completion of the BGP observable: stopping " +
                      "bgpd")
            updatesActive = false
            startOrStopBgpd()
    }

    private def eventHandler = {
        if (config.zookeeper.useNewStack) eventHandlerV2
        else eventHandlerV1
    }

    override def receive = super.receive orElse {
        case m if eventHandler.isDefinedAt(m) =>
            belt.handle(() => eventHandler(m))
        case m => log.warn(s"$m: ignoring unrecognized message")
    }

    private def forgetLearnedRoute(route: Route): Unit = {
        log.info(s"Forgetting learned route: " +
            s"${route.getDstNetworkAddr}/${route.getDstNetworkLength} " +
            s"via ${route.getNextHopGateway}")
        routingStorage.removeRoute(route)
    }

    private def publishLearnedRoute(route: Route): Unit = {
        log.info(s"Publishing learned route: " +
            s"${route.getDstNetworkAddr}/${route.getDstNetworkLength} " +
            s"via ${route.getNextHopGateway}")

        peerRoutes.put(route, null)
        val routeId = routingStorage.addRoute(route)
        if (routeId ne NO_UUID) {
            route.setId(routeId)
            peerRoutes.put(route, route)
        }
    }

    private def syncPeerRoutes(): Unit = {
        val learnedRoutes = routingStorage.learnedRoutes(rport.deviceId,
                                                         rport.id)

        handleLearnedRouteError {
            // Delete routes we don't have anymore
            for (route <- learnedRoutes if !peerRoutes.contains(route)) {
                forgetLearnedRoute(route)
            }
            // Add routes that were not published
            for ((routeKey, routeValue) <- peerRoutes if routeValue eq null) {
                publishLearnedRoute(routeKey)
            }
        }
    }

    private def clearRoutingWorkflow(): Unit = {
        RoutingWorkflow.routerPortToDatapathInfo.remove(rport.id)
        RoutingWorkflow.inputPortToDatapathInfo.remove(routingInfo.dpPortNo)
        routingInfo.dpPortNo = NO_PORT
        routingInfo.uplinkPid = NO_UPLINK
    }

    private def handleLearnedRouteError(op: => Unit): Unit = {
        try op catch {
            case e: StateAccessException =>
                val cb = new Runnable {
                    override def run(): Unit = { self ! SYNC_PEER_ROUTES }
                }
                connWatcher.handleError(s"BGP learned routes: ${rport.id}", cb, e)
        }
    }

    private def updateBgpdStatus(): Future[_] = {
        var status = "bgpd daemon is not ready"
        checkBgpdHealth().andThen { case _ =>
            try {
                if (bgpd.isAlive) {
                    status = bgpd.vty.showGeneric("show ip bgp nei").
                        filterNot(_.startsWith("bgpd#")).
                        filterNot(_.startsWith("show ip bgp nei")).
                        foldRight("")((a, b) => s"$a\n$b")
                }
            } catch {
                case e: Exception => log.warn("Failed to check bgpd status", e)
            }

            try {
                for (id <- bgpPeerIds) {
                    routingStorage.setStatus(id, status)
                }
            } catch {
                case e: StateAccessException => log.warn("Failed to set bgpd status", e)
            }
        }(singleThreadExecutionContext)
    }

    private def update(newConfig: BgpRouter, newPeers: Set[UUID]): Future[_] = {
        (bgpd.isAlive match {
            case true if newConfig.as != bgpConfig.as =>
                log.info("BGP AS number changed, restarting bgpd")
                stopBgpd()
            case true =>
                updateLocalNetworks(bgpConfig.networks, newConfig.networks)
                updatePeers(bgpConfig.neighbors.values.toSet, newConfig.neighbors.values.toSet)
                Future.successful(true)
            case false => // ignored
                Future.successful(true)
        }).flatMap {
            case _ =>
                bgpConfig = newConfig
                bgpPeerIds = newPeers
                startOrStopBgpd()
        }(singleThreadExecutionContext)
    }

    private def updatePeers(currentPeers: Set[Neighbor], newPeers: Set[Neighbor]): Unit = {
        val lost = currentPeers.diff(newPeers)
        val gained = newPeers.diff(currentPeers)

        for (peer <- lost) {
            log.info(s"Forgetting BGP neighbor ${peer.as} at ${peer.address}")
            bgpd.vty.deletePeer(bgpConfig.as, peer.address)
            routingInfo.peers.remove(peer.address)
        }

        for (peer <- gained) {
            bgpd.vty.addPeer(bgpConfig.as, peer.address, peer.as,
                peer.keepalive.getOrElse(config.bgpKeepAlive),
                peer.holdtime.getOrElse(config.bgpHoldTime),
                peer.connect.getOrElse(config.bgpConnectRetry))
            routingInfo.peers.add(peer.address)
            log.info(s"Set up BGP session with AS ${peer.as} at ${peer.address}")
        }

        if (newPeers.isEmpty) {
            clearRoutingWorkflow()
            bgpd.stop()
        }

        if (lost.nonEmpty || gained.nonEmpty)
            invalidateFlows()
    }

    private def bootstrapBgpdConfig() {
        log.debug(s"Configuring bgpd")

        bgpd.vty.setAs(bgpConfig.as)
        bgpd.vty.setRouterId(bgpConfig.as, bgpConfig.id)

        for (neigh <- bgpConfig.neighbors.values) {
            bgpd.vty.addPeer(bgpConfig.as, neigh.address, neigh.as,
                neigh.keepalive.getOrElse(config.bgpKeepAlive),
                neigh.holdtime.getOrElse(config.bgpHoldTime),
                neigh.connect.getOrElse(config.bgpConnectRetry))
            routingInfo.peers.add(neigh.address)
            log.info(s"Set up BGP session with AS ${neigh.as} at ${neigh.address}")
        }

        for (net <- bgpConfig.networks) {
            log.info(s"Announcing route to peers: ${net.cidr}")
            bgpd.vty.addNetwork(bgpConfig.as, net.cidr)
        }

        if (log.underlying.isDebugEnabled)
            bgpd.vty.setDebug(enabled = true)
    }

    private def updateLocalNetworks(currentNetworks: Set[Network], newNetworks: Set[Network]): Unit = {
        val lost = currentNetworks.diff(newNetworks)
        val gained = newNetworks.diff(currentNetworks)

        for (net <- lost) {
            log.info(s"Withdrawing route announcement: ${net.cidr}")
            bgpd.vty.deleteNetwork(bgpConfig.as, net.cidr)
        }

        for (net <- gained) {
            log.info(s"Announcing route to peers: ${net.cidr}")
            bgpd.vty.addNetwork(bgpConfig.as, net.cidr)
        }
    }

    private def bgpdPrepare(): Future[(DpPort, Int)] = {
        log.debug("preparing environment for bgpd")

        try {
            startZebra()
            bgpd.prepare()
            createDpPort(BGP_NETDEV_PORT_NAME)
        } catch {
            case e: Exception =>
                log.warn("Could not prepare bgpd environment", e)
                Future.failed(e)
        }
    }

    private def startBgpd(): Future[_] = {
        bgpdPrepare().map {
            case (dpPort, pid) =>
                log.debug("datapath port is ready, starting bgpd")
                theDatapathPort = Some(dpPort.asInstanceOf[NetDevPort])

                routingInfo.uplinkPid = pid
                routingInfo.dpPortNo = dpPort.getPortNo
                for (peer <- bgpConfig.neighbors.values) {
                    routingInfo.peers.add(peer.address)
                }

                RoutingWorkflow.inputPortToDatapathInfo.put(dpPort.getPortNo, routingInfo)
                RoutingWorkflow.routerPortToDatapathInfo.put(rport.id, routingInfo)
                invalidateFlows()

                bgpd.start()
                bootstrapBgpdConfig()
                true
        }(singleThreadExecutionContext).recoverWith {
            case e =>
                log.warn("Could not initialize bgpd", e)
                stopBgpd()
        }(singleThreadExecutionContext)
    }

    private def stopBgpd(): Future[_] = {
        log.info(s"Disabling BGP on port: ${rport.id}")
        clearRoutingWorkflow()
        stopZebra()

        log.debug("stopping bgpd")
        bgpd.stop()
        invalidateFlows()
        handleLearnedRouteError {
            for (route <- peerRoutes.values) {
                forgetLearnedRoute(route)
            }
        }
        peerRoutes.clear()
        removeDpPort()
    }

    private def restartBgpd(): Future[_] = {
        stopBgpd().flatMap{ case _ => startBgpd() }(singleThreadExecutionContext)
    }

    private def checkBgpdHealth(): Future[_] = {
        if (bgpdShouldRun && !bgpd.isAlive) {
            log.warn("bgpd died, restarting")
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
        val f = theDatapathPort map deleteDpPort getOrElse Future.successful(true)
        theDatapathPort = None
        f
    }

    private def invalidateFlows(): Unit = {
        log.info("Invalidating BGP flows")
        val tag = FlowTagger.tagForDpPort(routingInfo.dpPortNo)
        flowInvalidator(tag)
    }
}
