/*
 * Copyright 2014 Midokura SARL
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
package org.midonet.midolman

import java.lang.{Boolean => JBoolean, Integer => JInteger}
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.{HashMap => JHashMap, Set => JSet, UUID}

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.reflect._

import akka.actor._
import akka.pattern.{after, pipe}

import com.google.inject.Inject
import com.typesafe.scalalogging.Logger

import org.slf4j.LoggerFactory

import rx.{Observer, Subscription}

import org.midonet.cluster.data.TunnelZone.{HostConfig => TZHostConfig, Type => TunnelType}
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.datapath.DatapathPortEntangler
import org.midonet.midolman.host.interfaces.InterfaceDescription
import org.midonet.midolman.host.scanner.InterfaceScanner
import org.midonet.midolman.io._
import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.midolman.services.HostIdProviderService
import org.midonet.midolman.state.FlowStateStorageFactory
import org.midonet.midolman.topology.VirtualToPhysicalMapper.{TunnelZoneRequest, ZoneChanged, ZoneMembers}
import org.midonet.midolman.topology._
import org.midonet.midolman.topology.rcu.ResolvedHost
import org.midonet.netlink._
import org.midonet.odp.flows.FlowActionOutput
import org.midonet.odp.ports._
import org.midonet.odp.{Datapath, DpPort, OvsConnectionOps}
import org.midonet.packets.{IPAddr, IPv4Addr}
import org.midonet.sdn.flows.FlowTagger
import org.midonet.sdn.flows.FlowTagger.FlowTag
import org.midonet.util.concurrent._
import org.midonet.util.process.ProcessHelper

object UnderlayResolver {
    case class Route(srcIp: Int, dstIp: Int, output: FlowActionOutput) {
        override def toString: String = {
            s"Route(src:${IPv4Addr.intToString(srcIp)}, " +
                s"dst:${IPv4Addr.intToString(dstIp)}, $output)"
        }
    }
}

trait UnderlayResolver {

    import UnderlayResolver.Route

    /** Looks up a tunnel route to a remote peer. If there are multiple routes,
      * this method makes no guarantees as to which one is returned.
     */
    def peerTunnelInfo(peer: UUID): Option[Route]

    def vtepTunnellingOutputAction: FlowActionOutput

    def vxlanRecircOutputAction: FlowActionOutput

    def isVtepTunnellingPort(portNumber: Integer): Boolean

    def isVxlanRecircPort(portNumber: Integer): Boolean

    def isOverlayTunnellingPort(portNumber: Integer): Boolean
}

trait VirtualPortsResolver {
    def getDpPortNumberForVport(vportId: UUID): JInteger
    def dpPortForTunnelKey(tunnelKey: Long): DpPort
    def getVportForDpPortNumber(portNum: JInteger): UUID
}

trait DatapathState extends VirtualPortsResolver with UnderlayResolver {
    def datapath: Datapath
}

object DatapathController extends Referenceable {

    val log = LoggerFactory.getLogger(classOf[DatapathController])

    override val Name = "DatapathController"

    /**
     * This will make the Datapath Controller to start the local state
     * initialization process.
     */
    case object Initialize

    /** Java API */
    val initializeMsg = Initialize

    // This value is actually configured in preStart of a DatapathController
    // instance based on the value specified in /etc/midolman/midolman.conf
    // because we can't inject midolmanConfig into Scala's companion object.
    var defaultMtu: Short = MidolmanConfig.DEFAULT_MTU

    /**
     * This message is sent when the separate thread has successfully
     * retrieved all information about the interfaces.
     */
    case class InterfacesUpdate_(interfaces: Set[InterfaceDescription])

    // Signals that the tunnel ports have been created
    case object TunnelPortsCreated_

    private var cachedMinMtu = (defaultMtu - VxLanTunnelPort.TunnelOverhead).toShort

    def minMtu = cachedMinMtu
}


/**
 * The DP (Datapath) Controller is responsible for managing MidoNet's local
 * kernel datapath. It queries the VirtualToPhysicalMapper to discover (and
 * receive updates about) what virtual ports are mapped to this host's
 * interfaces. It uses the Netlink API to query the local datapaths, create the
 * datapath if it does not exist, create datapath ports for the appropriate host
 * interfaces and learn their numeric IDs, locally track the mapping
 * of datapath port ID to MidoNet virtual port ID. When a locally managed vport
 * has been successfully mapped to a local network interface, the DP Controller
 * notifies the VirtualToPhysicalMapper that the vport is ready to receive flows.
 * This allows other Midolman daemons (at other physical hosts) to correctly
 * forward flows that should be emitted from the vport in question.
 *
 * The DP Controller knows when the Datapath is ready to be used and notifies
 * the PacketsEntryPoint so that the latter may register for Netlink PacketIn
 * notifications.
 *
 * The DP Controller is also responsible for managing overlay tunnels.
 */
class DatapathController @Inject() (val driver: DatapathStateDriver,
                                    hostService: HostIdProviderService,
                                    interfaceScanner: InterfaceScanner,
                                    config: MidolmanConfig,
                                    upcallConnManager: UpcallDatapathConnectionManager,
                                    flowInvalidator: SimulationBackChannel,
                                    clock: NanoClock,
                                    storageFactory: FlowStateStorageFactory,
                                    val netlinkChannelFactory: NetlinkChannelFactory)
        extends Actor
        with ActorLogWithoutPath
        with SingleThreadExecutionContextProvider
        with DatapathPortEntangler
        with MtuIncreaser {

    import org.midonet.midolman.DatapathController._
    import org.midonet.midolman.topology.VirtualToPhysicalMapper.TunnelZoneUnsubscribe

    import context.{dispatcher, system}

    override def logSource = "org.midonet.datapath-control"

    var zones = Map[UUID, IPAddr]()

    var portWatcher: Subscription = null

    override def preStart(): Unit = {
        super.preStart()
        defaultMtu = config.dhcpMtu
        cachedMinMtu = defaultMtu
        context become (DatapathInitializationActor orElse {
            case m =>
                log.info(s"Not handling $m (still initializing)")
        })
    }

    override def postStop(): Unit = {
        super.postStop()
    }

    private def subscribeToHost(id: UUID): Unit = {
        val props = Props(classOf[HostRequestProxy],
                          id, storageFactory.create(), self)
                        .withDispatcher(context.props.dispatcher)
        context.actorOf(props, s"HostRequestProxy-$id")
    }

    val DatapathInitializationActor: Receive = {
        case Initialize =>
            // We use the datapath's "local" port to recirculate packets that
            // need encap/decap before futher processing.
            // TODO: add a new/separate internal port for this purpose.
            var cmd = s"ip address add ${config.datapathIfAddr} dev ${config.datapathName}"
            var result = ProcessHelper.executeCommandLine(cmd, false)
            cmd = s"ip link set ${config.datapathName} address 02:00:11:00:11:01"
            result = ProcessHelper.executeCommandLine(cmd, false)
            cmd = s"ip link set ${config.datapathName} up"
            result = ProcessHelper.executeCommandLine(cmd, false)
            cmd = s"ip neigh add ${config.datapathIfPeerAddr} 02:00:11:00:11:02 nud permanent"
            // Ignore the errors seeding the neighbor table.
            result = ProcessHelper.executeCommandLine(cmd, true)
            makeTunnelPort(OverlayTunnel) { () =>
                GreTunnelPort make "tngre-overlay"
            } flatMap { gre =>
                driver.tunnelOverlayGre = gre
                makeTunnelPort(OverlayTunnel) { () =>
                    val overlayUdpPort = config.datapath.vxlanOverlayUdpPort
                    VxLanTunnelPort make("tnvxlan-overlay", overlayUdpPort)
                }
            } flatMap { vxlan =>
                driver.tunnelOverlayVxLan = vxlan
                makeTunnelPort(VtepTunnel) { () =>
                    val vtepUdpPort = config.datapath.vxlanVtepUdpPort
                    VxLanTunnelPort make("tnvxlan-vtep", vtepUdpPort)
               }
            } flatMap { vtep =>
                driver.tunnelVtepVxLan = vtep
                makeTunnelPort(VtepTunnel) { () =>
                    val vlxanRecircPort = config.datapath.vxlanRecirculateUdpPort
                    VxLanTunnelPort make("tnvxlan-recirc", vlxanRecircPort)
                }
            } map { recirc =>
                driver.tunnelRecircVxLan = recirc
                TunnelPortsCreated_
            } pipeTo self

        case TunnelPortsCreated_ =>
            subscribeToHost(hostService.hostId)
            log.info("Initialization complete. Starting to act as a controller.")
            context become receive
            portWatcher = interfaceScanner.subscribe(
                new Observer[Set[InterfaceDescription]] {
                    def onCompleted(): Unit =
                        log.debug("Interface scanner is completed.")

                    def onError(t: Throwable): Unit =
                        log.error(s"Interface scanner got an error: $t")

                    def onNext(data: Set[InterfaceDescription]): Unit =
                        self ! InterfacesUpdate_(data)
                })
    }

    def makeTunnelPort[P <: DpPort](t: ChannelType)(portFact: () => P)
                                   (implicit tag: ClassTag[P]): Future[P] =
        upcallConnManager.createAndHookDpPort(driver.datapath, portFact(), t) map {
            case (p, _) => p.asInstanceOf[P]
        } recoverWith {
            case ex: Throwable =>
                log.warn(tag + " creation failed: => retrying", ex)
                after(1 second, system.scheduler)(makeTunnelPort(t)(portFact))
        }

    private def doDatapathZonesUpdate(
            oldZones: Map[UUID, IPAddr],
            newZones: Map[UUID, IPAddr]): Unit =  {
        val dropped = oldZones.keySet.diff(newZones.keySet)
        for (zone <- dropped) {
            VirtualToPhysicalMapper ! TunnelZoneUnsubscribe(zone)
            for (tag <- driver.removePeersForZone(zone)) {
                flowInvalidator.tell(tag)
            }
        }

        val added = newZones.keySet.diff(oldZones.keySet)
        for (zone <- added) {
            VirtualToPhysicalMapper ! TunnelZoneRequest(zone)
        }
    }

    override def receive: Receive = super.receive orElse {

        // When we get the initialization message we switch into initialization
        // mode and only respond to some messages.
        // When initialization is completed we will revert back to this Actor
        // loop for general message response
        case Initialize =>
            context.become(DatapathInitializationActor)
            // In case there were some scheduled port update checks, cancel them.
            if (portWatcher != null) {
                portWatcher.unsubscribe()
            }
            self ! Initialize

        case host: ResolvedHost =>
            val oldZones = zones
            val newZones = host.zones

            zones = newZones

            updateVPortInterfaceBindings(host.ports)
            doDatapathZonesUpdate(oldZones, newZones)

        case m@ZoneMembers(zone, zoneType, members) =>
            log.debug("ZoneMembers event: {}", m)
            if (zones contains zone) {
                for (m <- members) {
                    handleZoneChange(zone, zoneType, m, HostConfigOperation.Added)
                }
            }

        case m@ZoneChanged(zone, zoneType, hostConfig, op) =>
            log.debug("ZoneChanged event: {}", m)
            if (zones contains zone)
                handleZoneChange(zone, zoneType, hostConfig, op)

        case InterfacesUpdate_(interfaces) =>
            updateInterfaces(interfaces)
            setTunnelMtu(interfaces)
    }

    def handleZoneChange(zone: UUID, t: TunnelType, config: TZHostConfig,
                         op: HostConfigOperation.Value) {

        if (config.getId == hostService.hostId())
            return

        val peerUUID = config.getId

        op match {
            case HostConfigOperation.Added => processAddPeer()
            case HostConfigOperation.Deleted => processDelPeer()
        }

        def processTags(tags: TraversableOnce[FlowTag]): Unit =
            tags foreach flowInvalidator.tell

        def processDelPeer(): Unit =
            processTags(driver.removePeer(peerUUID, zone))

        def processAddPeer() =
            zones.get(zone) match {
                case Some(srcIp: IPv4Addr) =>
                    val dstIp = config.getIp.toInt
                    val tags = driver.addPeer(peerUUID, zone, srcIp.toInt, dstIp, t)
                    processTags(tags)
                case _ =>
                    log.info("Could not find this host's ip for zone {}", zone)
            }

    }

    override def addToDatapath(port: String): Future[(DpPort, Int)] = {
        log.debug(s"Creating port $port")
        upcallConnManager.createAndHookDpPort(
                driver.datapath, new NetDevPort(port), VirtualMachine)
    }

    override def removeFromDatapath(port: DpPort): Future[_] = {
        log.debug(s"Removing port ${port.getName}")
        upcallConnManager.deleteDpPort(driver.datapath, port)
    }

    override def setVportStatus(port: DpPort, vport: UUID, tunnelKey: Long,
                                isActive: Boolean): Unit = {
        log.info(s"Port ${port.getPortNo}/${port.getName}/$vport " +
                 s"became ${if (isActive) "active" else "inactive"}")
        system.eventStream.publish(LocalPortActive(vport, isActive))
        invalidateTunnelKeyFlows(port, tunnelKey, isActive)
        if (isActive)
            increaseMtu(vport)
    }

    private def invalidateTunnelKeyFlows(port: DpPort, tunnelKey: Long,
                                         active: Boolean): Unit = {
        // Trigger invalidation. This is done regardless of whether we are
        // activating or deactivating:
        //   - The case for invalidating on deactivation is obvious.
        //   - On activation we invalidate flows for this dp port number in case
        //     it has been reused by the dp: we want to start with a clean state
        flowInvalidator.tell(FlowTagger.tagForTunnelKey(tunnelKey))
        flowInvalidator.tell(FlowTagger.tagForDpPort(port.getPortNo))
    }

    private def setTunnelMtu(interfaces: JSet[InterfaceDescription]) = {
        var minMtu = Short.MaxValue
        val overhead = VxLanTunnelPort.TunnelOverhead

        for { intf <- interfaces.asScala
              inetAddress <- intf.getInetAddresses.asScala
              zone <- zones
              if InetAddress.getByAddress(zone._2.toBytes) == inetAddress
        } {
            val tunnelMtu = (defaultMtu - overhead).toShort
            minMtu = minMtu.min(tunnelMtu)
        }

        if (minMtu == Short.MaxValue)
            minMtu = defaultMtu

        if (cachedMinMtu != minMtu) {
            log.info(s"Changing MTU from $cachedMinMtu to $minMtu")
            cachedMinMtu = minMtu
        }
    }
}

object DatapathStateDriver {
    case class DpTriad(
        ifname: String,
        var isUp: Boolean = false,
        var vport: UUID = null,
        var tunnelKey: Long = 0L,
        var dpPort: DpPort = null,
        var dpPortNo: Integer = null)
}

/** class which manages the state changes triggered by message receive by
 *  the DatapathController. It also exposes the DatapathController managed
 *  data to clients for WilcardFlow translation. */
class DatapathStateDriver(val datapath: Datapath) extends DatapathState  {
    import DatapathStateDriver._
    import UnderlayResolver.Route

    val log = Logger(LoggerFactory.getLogger("org.midonet.datapath-control"))

    var tunnelOverlayGre: GreTunnelPort = _
    var tunnelOverlayVxLan: VxLanTunnelPort = _
    var tunnelVtepVxLan: VxLanTunnelPort = _
    var tunnelRecircVxLan: VxLanTunnelPort = _

    val interfaceToTriad = new ConcurrentHashMap[String, DpTriad]()
    val vportToTriad = new ConcurrentHashMap[UUID, DpTriad]()
    val keyToTriad = new ConcurrentHashMap[Long, DpTriad]()
    val dpPortNumToTriad = new ConcurrentHashMap[Int, DpTriad]

    override def vtepTunnellingOutputAction = tunnelVtepVxLan.toOutputAction

    override def vxlanRecircOutputAction = tunnelRecircVxLan.toOutputAction

    def isVtepTunnellingPort(portNumber: Integer) =
        tunnelVtepVxLan.getPortNo == portNumber

    def isVxlanRecircPort(portNumber: Integer) =
        tunnelRecircVxLan.getPortNo == portNumber

    def isOverlayTunnellingPort(portNumber: Integer) =
        tunnelOverlayGre.getPortNo == portNumber ||
        tunnelOverlayVxLan.getPortNo == portNumber

    /** 2D immutable map of peerUUID -> zoneUUID -> (srcIp, dstIp, outputAction)
     *  this map stores all the possible underlay routes from this host
     *  to remote midolman host, with the tunnelling output action.
     */
    var _peersRoutes = Map[UUID,Map[UUID,Route]]()

    override def peerTunnelInfo(peer: UUID) =
        _peersRoutes get peer flatMap { _.values.headOption }

    /** add route info about peer for given zone and retrieve ip for this host
     *  and for this zone from dpState.
     *  @param  peer  remote host UUID
     *  @param  zone  zone UUID the underlay route to add is associated to.
     *  @param  srcIp the underlay ip of this host
     *  @param  dstIp the underlay ip of the remote host
     *  @param  t the tunnelling protocol type
     *  @return possible tags to send to the FlowController for invalidation
     */
    def addPeer(peer: UUID, zone: UUID,
                srcIp: Int, dstIp: Int, t: TunnelType): Seq[FlowTag] = {
        val outputAction = t match {
            case TunnelType.vtep => return Seq.empty[FlowTag]
            case TunnelType.gre => tunnelOverlayGre.toOutputAction
            case TunnelType.vxlan => tunnelOverlayVxLan.toOutputAction
        }

        val newRoute = Route(srcIp, dstIp, outputAction)
        log.info(s"new tunnel route $newRoute to peer $peer")

        val routes = _peersRoutes getOrElse (peer, Map[UUID,Route]())
        // invalidate the old route if overwrite
        val oldRoute = routes get zone

        _peersRoutes += ( peer -> ( routes + (zone -> newRoute) ) )
        val tags = FlowTagger.tagForTunnelRoute(srcIp, dstIp) :: Nil

        oldRoute.fold(tags) { case Route(src, dst, _) =>
            FlowTagger.tagForTunnelRoute(src, dst) :: tags
        }
    }

    /** delete a tunnel route info about peer for given zone.
     *  @param  peer  remote host UUID
     *  @param  zone  zone UUID the underlay route to remove is associated to.
     *  @return possible tag to send to the FlowController for invalidation
     */
    def removePeer(peer: UUID, zone: UUID): Option[FlowTag] =
        (_peersRoutes get peer) flatMap { _ get zone } map {
            case r@Route(srcIp,dstIp,_) =>
                log.info(s"removing tunnel route $r to peer $peer")
                    // TODO(hugo): remove nested map if becomes empty (mem leak)
                    _peersRoutes += (peer -> (_peersRoutes(peer) - zone))
                    FlowTagger.tagForTunnelRoute(srcIp,dstIp)
                }

    /** delete all tunnel routes associated with a given zone
     *  @param  zone zone uuid
     *  @return sequence of tags to send to the FlowController for invalidation
     */
    def removePeersForZone(zone: UUID): Seq[FlowTag] =
        _peersRoutes.keys.toSeq.flatMap{ removePeer(_, zone) }

    override def getDpPortNumberForVport(vportId: UUID): JInteger = {
        val triad = vportToTriad.get(vportId)
        if (triad ne null)
            triad.dpPortNo
        else
            null
    }

    override def dpPortForTunnelKey(tunnelKey: Long): DpPort = {
        val triad = keyToTriad.get(tunnelKey)
        if (triad ne null)
            triad.dpPort
        else
            null
    }

    override def getVportForDpPortNumber(portNum: JInteger): UUID = {
        val triad = dpPortNumToTriad.get(portNum)
        if (triad ne null)
            triad.vport
        else
            null
    }
}
