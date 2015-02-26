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

package org.midonet.cluster.services.vxgw

import java.util.concurrent.Executors._
import java.util.concurrent.{ConcurrentHashMap, ThreadFactory}
import java.util.{Objects, UUID}

import scala.collection.JavaConversions._
import scala.util.{Failure, Success, Try}

import org.slf4j.LoggerFactory
import rx.schedulers.Schedulers
import rx.subjects.PublishSubject
import rx.subscriptions.CompositeSubscription
import rx.{Observable, Observer}

import org.midonet.cluster.data.Bridge
import org.midonet.cluster.data.ports.VxLanPort
import org.midonet.cluster.data.vtep.VtepNotConnectedException
import org.midonet.cluster.data.vtep.model.MacLocation
import org.midonet.cluster.services.vxgw
import org.midonet.cluster.southbound.vtep.VtepConstants.bridgeIdToLogicalSwitchName
import org.midonet.midolman.serialization.SerializationException
import org.midonet.midolman.state.Directory.DefaultTypedWatcher
import org.midonet.midolman.state._
import org.midonet.packets.{IPv4Addr, MAC}
import org.midonet.util.functors._

object VxlanGateway {
    protected[vxgw] val executor = newSingleThreadExecutor(
        new ThreadFactory {
        override def newThread(r: Runnable): Thread = {
            val t = new Thread(r)
            t.setName("vxgw-management-thread")
            t
        }
    })
}

/** Represents a Logical Switch spanning N VTEPs and a Neutron Network. This
  * class embeds a messaging bus that the components in charge of listening and
  * updating all participants (e.g., hardware VTEPs and MidoNet) can use to
  * push updates from their backned, an subscribe to updates from the rest.
  *
  * @param networkId id if the neutron network that is acting as VxLAN Gateway
  *                  by having bindings to port/vlan pairs in hardware VTEPs.
  */
final class VxlanGateway(val networkId: UUID) {

    private val log = LoggerFactory.getLogger(vxgw.vxgwMgmtLog(networkId))
    private val updates = PublishSubject.create[MacLocation]()

    // We learn these as soon as we load the binding configuration from the NSDB
    protected[midonet] var vni = -1
    protected[midonet] var tzId: UUID = _

    /** The name of the Logical Switch associated to this VxGateway */
    val name = bridgeIdToLogicalSwitchName(networkId)

    /** This is the entry point to dump observables */
    def asObserver: Observer[MacLocation] = new Observer[MacLocation] {
        override def onCompleted(): Unit = updates.onCompleted()
        override def onError(e: Throwable): Unit = updates.onError(e)
        override def onNext(ml: MacLocation): Unit = {
            if (ml.logicalSwitchName.equals(name)) {
                log.trace("Learned: {}", ml)
                updates.onNext(ml)
            }
        }
    }

    /** Get the message bus of MacLocation notifications of the Logical
      * Switch.  You're responsible to filter out your own updates. */
    def asObservable: Observable[MacLocation] = updates

    /** This VxGW is no longer relevant for us so stop managing it */
    protected[midonet] def terminate(): Unit = updates.onCompleted()

    override def equals(o: Any): Boolean = {
        if (!o.isInstanceOf[VxlanGateway]) false
        else {
            val that = o.asInstanceOf[VxlanGateway]
            Objects.equals(this.vni, that.vni) &&
            Objects.equals(this.tzId, that.tzId)
        }
    }

    override def hashCode: Int = Objects.hashCode(vni, tzId)
}


/** Manages a VxLAN Gateway that connects a Neutron Network with a set of ports
  * on hardware VTEPs.  Neutron networks are bound to port/vlan pairs on VTEPs
  * in order to form a single L2 segment.  An instance of this class is able
  * to monitor a Neutron network and control the synchronization of MACs
  * among MidoNet and all hardware VTEPs that participate in the VxLAN Gateway.
  *
  * @param networkId the id of the Neutron Network with VTEP bindings to manage
  * @param topology to access the MidoNet backend storage
  * @param vtepPeerPool the pool of VTEPs, we'll extract them from here whenever
  *                     the network needs to interact with a VTEP (e.g., to
  *                     make it join the Logical Switch)
  * @param tzState the tunnel zone publisher, it won't be monitored and simply
  *                pulled on demand whenever we need the flooding proxy IP.
  * @param zkConnWatcher watcher to use to handle ZK connection issues
  * @param onClose callback that will be invoked when we're done monitoring the
  *                network, for any reason. Obviously, don't do anything nasty
  *                in it (blocking, heavy IO, etc.)
  */
class VxlanGatewayManager(networkId: UUID,
                          topology: TopologyApi,
                          vtepPeerPool: VtepPool,
                          tzState: TunnelZoneStatePublisher,
                          zkConnWatcher: ZookeeperConnectionWatcher,
                          onClose: () => Unit) {

    private val log = LoggerFactory.getLogger(vxgwMgmtLog(networkId))

    private val vxgw: VxlanGateway = new VxlanGateway(networkId)

    private val peerEndpoints = new ConcurrentHashMap[IPv4Addr, UUID]
    private val vxlanPorts = new ConcurrentHashMap[UUID, VxLanPort]

    private var macPortMap: MacPortMap = _
    private var arpTable: Ip4ToMacReplicatedMap = _

    private var vxgwBusObserver: BusObserver = _

    private val subscriptions = new CompositeSubscription()

    /** The name of the Logical Switch that is created on all Hardware VTEPs to
      * configure the bindings to this Neutron Network in order to implement a
      * VxLAN Gateway. */
    val lsName = bridgeIdToLogicalSwitchName(networkId)

    class NetworkNotInVxlanGatewayException(m: String)
        extends RuntimeException(m)

    /* A simple Bridge watcher that detects when a bridge is updated and applies
     * the relevant changes in state and syncing processes, or terminates the
     * manager if the bridge itself is removed. */
    private val bridgeWatcher = new DefaultTypedWatcher {
        override def pathDataChanged(path: String): Unit = {
            log.info(s"Network update notification")
            VxlanGateway.executor submit makeRunnable { updateBridge() }
        }
        override def pathDeleted(path: String): Unit = {
            log.info(s"Network deleted, stop monitoring")
            terminate()
        }
    }

    private val macPortWatcher = new Observer[MapNotification[MAC, UUID]] {
        private val log = LoggerFactory.getLogger(vxgwMacSyncingLog(networkId))
        override def onCompleted(): Unit = {
            log.warn("The MAC-Port table was deleted")
        }
        override def onError(e: Throwable): Unit = {
            log.warn("The MAC-Port table update stream failed", e)
        }
        override def onNext(n: MapNotification[MAC, UUID]): Unit = {
            if (log.isDebugEnabled) log.debug(s"MAC {} moves from {} to {}",
                                              n.key, n.oldVal, n.newVal)
            val lastPort: UUID = if (n.newVal == null) n.oldVal else n.newVal
            if (vxgw != null && isPortInMidonet(lastPort)) {
                publishMac(n.key, n.newVal, n.oldVal, onlyMido = true)
            }
        }
    }

    private val arpWatcher = new Observer[MapNotification[IPv4Addr, MAC]] {
        override def onCompleted(): Unit = {
            log.warn("The ARP table was deleted")
        }
        override def onError(e: Throwable): Unit = {
            log.warn("The ARP table update stream failed", e)
        }
        override def onNext(n: MapNotification[IPv4Addr, MAC]): Unit = {
            log.debug(s"IP {} moves from {} to {}", n.key, n.oldVal, n.newVal)
            if (n.oldVal != null) { // The old mac needs to be removed
                macPortMap.get(n.oldVal) match {
                    case portId if isPortInMidonet(portId) =>
                        // If the IP was on a MidoNet port, we remove it,
                        // otherwise it's managed by some VTEP.
                        vxgw.asObserver.onNext(
                            MacLocation(n.oldVal, n.key, lsName, null)
                        )
                    case _ =>
                }
            }
            if (n.newVal != null) {
                macPortMap.get(n.newVal) match {
                    case portId if isPortInMidonet(portId) =>
                        advertiseMacAndIpAt(n.newVal, n.key, portId)
                    case _ =>
                }
            }
        }
    }

    /** Whether the Gateway Manager is actively managing the VxGW */
    private var active = false

    /** Start syncing MACs from the neutron network with the bound VTEPs. */
    def start(): Unit = updateBridge()

    /** Tells whether this port is bound to a part of a MidoNet virtual topology,
      * excluding those ports that represent the bindings to a VTEP.  This is
      * used to decide whether we're responsible to manage some event's side
      * effects or not.  Events that affect a non-MidoNet port are expected
      * to be managed by the VTEPs themselves, or the VxlanPeer implementations
      * for each VTEP. */
    private def isPortInMidonet(portId: UUID): Boolean = {
        portId != null && !vxlanPorts.containsKey(portId)
    }

    /** Get a snapshot of all the known MACs of this Logical Switch */
    private def snapshotMacPorts: Seq[MacLocation] = {
        if (macPortMap == null) {
            log.info("Can't snapshot mac-port table, still not loaded")
            return Seq.empty
        }
        log.debug(s"Taking snapshot of known MACs at $networkId")
        // The reasoning is that we get a SNAPSHOT based *only* on entries in
        // the replicated map because this contains also all entries from the
        // VTEPs, with their respective IPs.
        macPortMap.getMap.entrySet().flatMap { e =>
            val mac = e.getKey
            val port = e.getValue
            toMacLocations(mac, port, null, onlyMido = false).getOrElse {
                Set.empty
            }
        }.toSeq
    }

    /** Gets the MacLocation entry that corresponds to the flooding proxy to
      * which VTEPs should tunnel all broadcast traffic. */
    private def midoFloodLocation: Seq[MacLocation] = {
        val currTzState = tzState.get(vxgw.tzId)
        val fpIp = if (currTzState == null ||
                       currTzState.getFloodingProxy == null) null
                   else currTzState.getFloodingProxy.ipAddr
        if (fpIp == null) {
            log.info("Unable to find flooding proxy")
            Seq.empty
        } else {
            log.info(s"Publish new flooding proxy $fpIp")
            Seq(MacLocation.unknownAt(fpIp, lsName))
        }
    }

    /** Get a snapshot of flood MacLocation entries pointing at all the VTEPs
      * involved in this VxLAN Gateway. */
    private def vtepFloodLocations: Seq[MacLocation] = {
        vxlanPorts.values().foldLeft(Seq.empty[MacLocation])((mls, vxPort) => {
            if (vxPort.getTunnelIp == null) {
                log.info("Unknown tunnel IP for VTEP at {}:{}",
                         vxPort.getMgmtIpAddr, vxPort.getMgmtPort)
                mls
            } else {
                mls :+ MacLocation.unknownAt(vxPort.getTunnelIp, lsName)
            }
        }
    )}

    /** Initialize the various processes required to manage the VxLAN gateway
      * for the given Neutron Network.  This includes setting up watchers on the
      * MAC-Port and ARP tables, as well as preparing the message bus that the
      * manager and VTEP controllers will use to exchange MacLocations as they
      * appear on different points of the topology.
      *
      * Any failure inside this method will just throw, and expect that error
      * handling is performed to retry the initialization. The method is
      * idempotent.
      *
      * We need to receive the newPortIds because the first run will need to
      * populate the vxlanPorts map BEFORE we can actually emit snapshots etc.
      *
      * @return whether the service DID require initialization
      */
    private def ensureInitialized(vxPortIds: Seq[UUID]): Boolean = {

        var initialization = false

        // We will pass this point on every network update, so just watch that
        // we initialize just once
        if (macPortMap == null) {

            vxPortIds foreach { id =>
                val port = topology.vxlanPort(id)    // it might throw
                vxlanPorts.put(id, port)
                if (vxgw.vni == -1) {
                    vxgw.vni = port.getVni
                    log.info(s"VNI ${vxgw.vni}")
                }
                if (vxgw.tzId == null) {
                    vxgw.tzId = port.getTunnelZoneId
                    log.info(s"Tunnel zone ${vxgw.tzId}")
                }
            }

            macPortMap = topology macTable networkId

            log.info(s"Starting to watch MAC-Port table in $networkId")
            vxgwBusObserver = new BusObserver(topology, networkId,
                                              macPortMap, zkConnWatcher,
                                              peerEndpoints)

            subscriptions.add(
                vxgw.asObservable
                    .observeOn(Schedulers.from(VxlanGateway.executor))
                    .subscribe(vxgwBusObserver)
            )

            macPortMap.setConnectionWatcher(zkConnWatcher)
            subscriptions.add(
                Observable.create(new MapObservableOnSubscribe(macPortMap))
                          .doOnUnsubscribe(makeAction0(
                              if (macPortMap != null) macPortMap.stop()))
                          .subscribe(macPortWatcher)
            )
            initialization = true
        }

        if (arpTable == null) {
            arpTable = topology arpTable networkId
            // if we throw before this, the caller will schedule a retry
            log.info(s"Starting to watch ARP table in $networkId")
            arpTable.setConnectionWatcher(zkConnWatcher)
            subscriptions.add(
                Observable.create(new MapObservableOnSubscribe(arpTable))
                          .doOnUnsubscribe(
                              makeAction0(if(arpTable != null) arpTable.stop()))
                          .subscribe(arpWatcher)
            )
            log.info("Network state now monitored")
            initialization = true
        }

        initialization
    }

    /** Clean up and stop monitoring */
    def terminate(): Unit = {
        log.info(s"Stop monitoring network $networkId")
        if (active) {
            active = false
            vxgw.terminate()
        }
        subscriptions.unsubscribe()
        onClose()
    }


    /** Reload the Network state and apply the new configuration */
    private def updateBridge(): Unit = {
        loadNewBridgeState() match {
            case Success(_) =>
                log.info(s"Successfully processed update")
            case Failure(e: NetworkNotInVxlanGatewayException) =>
                log.warn("Not relevant for VxLAN gateway")
                terminate()
            case Failure(e: NoStatePathException) =>
                log.warn("Deletion while loading network config, reload",e)
                updateBridge()
            case Failure(e: SerializationException) =>
                log.error("Failed to deserialize entity", e)
                terminate()
            case Failure(e: StateAccessException) =>
                log.warn("Cannot retrieve network state", e)
                zkConnWatcher.handleError(s"Network update retry: $networkId",
                                          makeRunnable { updateBridge() } , e
                )
            case Failure(t: Throwable) =>
                log.error("Error while processing bridge update", t)
                // TODO: retry, or exponential bla bla
                terminate()
        }
    }

    /** Attempts to load all the information that concerns a single bridge and
      * return the new state without actually making it available. */
    private def loadNewBridgeState() = Try {

        val bridge: Bridge = topology.bridge(networkId, bridgeWatcher)
        val newPortIds: Seq[UUID] = if (bridge == null) Seq()
                                    else bridge.getVxLanPortIds

        // Spot VTEPS no longer bound to this network
        vxlanPorts.keys
            .filterNot(newPortIds.contains)            // all deleted ports
            .map { vxlanPorts.remove }                 // forget them
            .foreach { port => if (port != null) {
                vtepPeerPool.fishIfExists(port.getMgmtIpAddr, port.getMgmtPort)
                            .foreach { _.abandon(vxgw) }
            }}

        if (bridge == null) {
            throw new NetworkNotInVxlanGatewayException(s"$networkId deleted")
        } else if (newPortIds.isEmpty) {
            throw new NetworkNotInVxlanGatewayException(
                "No longer bound to any VTEPs")
        }

        val wasInitialized = ensureInitialized(newPortIds)

        // Spot new VTEPs bound to this network
        newPortIds foreach { portId =>
            if (wasInitialized || !vxlanPorts.containsKey(portId)) {
                bootstrapNewVtep(portId)
            }
        }

    }

    /** A new VTEP appears on the network, which indicates bindings to a new
      * VTEP.  Load the VtepPeer and make it join the Logical Switch of this
      * network. */
    private def bootstrapNewVtep(vxPortId: UUID): Unit = {

        // We *might* have the port already loaded if this port was received
        // during the first initialization of the service.
        var vxPort = vxlanPorts.get(vxPortId)
        if (vxPort == null) {
            vxPort = topology.vxlanPort(vxPortId)
        }

        if (vxPort.getVni != vxgw.vni) {
            log.warn(s"VxLAN port $vxPortId has vni ${vxPort.getVni}, but " +
                     s"expected ${vxgw.vni}. Probable data inconsistency, " +
                     s"further bindings to VTEP at ${vxPort.getMgmtIpAddr}!" +
                     "will be ignored")
            return
        }
        if (vxPort.getTunnelZoneId != vxgw.tzId) {
            log.warn(s"VxLAN port $vxPortId has tunnel zone " +
                     s"${vxPort.getTunnelZoneId}, but expected ${vxgw.vni}. " +
                     "Probable data inconsistency, further bindings to VTEP " +
                     s"at ${vxPort.getMgmtIpAddr}! VTEP will be ignored")
            return
        }

        log.info(s"Bindings to new VTEP at " +
                 vxPort.getMgmtIpAddr + ":" + vxPort.getMgmtPort)

        vxlanPorts.put(vxPort.getId, vxPort)
        peerEndpoints.put(vxPort.getTunnelIp, vxPort.getId)

        try {
            vtepPeerPool.fish(vxPort.getMgmtIpAddr, vxPort.getMgmtPort)
                        .join(vxgw, snapshotMacPorts ++    // all macs
                                    vtepFloodLocations ++  // floods to VTEPs
                                    midoFloodLocation)     // floods to mido
        } catch {
            case e: VtepNotConnectedException =>
                makeRunnable( { bootstrapNewVtep(vxPortId) } )
            case e: Throwable =>
                log.warn("Failed to bootstrap VTEP at " +
                         s"${vxPort.getMgmtIpAddr}:${vxPort.getMgmtPort}", e)
        }
    }

    /** Converts a new MAC to port update in a bunch of MacLocations to be
      * applied on remote VTEPs. When onlyMido is true, only entries concerning
      * a MidoNet port will be translated (either moving between ports in a
      * network, or from VTEP <-> MidoNet. Otherwise also entries that point at
      * VxLAN ports will be translated (in this case using the VTEP's IP from
      * the VxLAN port at which the MAC is located)
      */
    private def toMacLocations(mac: MAC, newPort: UUID, oldPort: UUID,
                               onlyMido: Boolean): Try[Set[MacLocation]] = Try {

        // we only process changes that affect a port in MidoNet
        if (onlyMido && !isPortInMidonet(oldPort) && !isPortInMidonet(newPort)) {
            return Success(Set.empty)
        }

        // the tunnel destination of the MAC, based on the newPort
        val tunnelDst = if (newPort == null) null
                        else vxlanPorts.get(newPort) match {
                            case vxp: VxLanPort =>  // at a VTEP
                                val p = vxlanPorts.get(newPort)
                                if (p == null) null else p.getTunnelIp
                            case _ =>  // in MidoNet
                                topology vxlanTunnelEndpointFor newPort
                        }

        if (tunnelDst == null && newPort != null) {
            // This a typical case when the VM that has the MAC is not at an
            // exterior port in the Network, but elsewhere in the virt. topology
            val currTzState = tzState.get(vxgw.tzId)
            val floodingProxy = if (currTzState == null) null
                                else currTzState.getFloodingProxy
            if (floodingProxy == null) {
                Set.empty
            } else {
                log.info(s"MAC at port $newPort but tunnel IP not found, " +
                         s"I will use the flooding proxy ($currTzState)")
                macLocationsForArpSupression(mac, floodingProxy.ipAddr) +
                    MacLocation (mac, lsName, floodingProxy.ipAddr)
            }
        } else if (tunnelDst == null) {
            log.info(s"MAC $mac removed from $lsName")
            Set(MacLocation(mac, lsName, null))
        } else {
            // TODO: review this, not sure if we want to do the ARP supression
            //       bit for MACs that are not in MidoNet
            macLocationsForArpSupression(mac, tunnelDst) +
                MacLocation(mac, lsName, tunnelDst) // default with no IP

        }
    }

    private def macLocationsForArpSupression(mac: MAC, endpointIp: IPv4Addr)
    : Set[MacLocation] = {
        if (arpTable == null) Set.empty
        else (arpTable.getByValue (mac) map {
            ip => MacLocation (mac, ip, lsName, endpointIp)
        }).toSet
    }

    /** Publish the given location of a MAC to the given subscriber. */
    private def publishMac(mac: MAC, newPort: UUID, oldPort: UUID,
                           onlyMido: Boolean): Unit = {
        toMacLocations(mac, newPort, oldPort, onlyMido) match {
            case Success(mls) =>
                mls foreach vxgw.asObserver.onNext
            case Failure(e: NoStatePathException) =>
                log.debug(s"Node not in ZK, probably a race: ${e.getMessage}")
            case Failure(e: StateAccessException) =>
                log.warn(s"Cannot retrieve network state: ${e.getMessage}")
                if (zkConnWatcher != null) {
                    zkConnWatcher.handleError(
                        s"Retry load network: $networkId",
                        makeRunnable { newPort match {
                            case _ if !active =>
                                log.warn("Retry failed, the manager is down")
                            case null =>
                                publishMac(mac, null, oldPort, onlyMido)
                            case _ =>
                                val currPort = macPortMap.get(mac) // reload
                                publishMac(mac, currPort, oldPort, onlyMido)
                            }
                        }, e)
                }
            case Failure(t: Throwable) =>
                log.warn(s"Failure while publishing $mac on port $newPort", t)
        }
    }

    /** Reliably publish the association of MAC and IP as long as the MAC
      * remains at expectPortId. */
    private def advertiseMacAndIpAt(mac: MAC, ip: IPv4Addr, expectPortId: UUID)
    : Unit = {
        try {
            // Yes, we'll be querying this map twice on the first attempt, this
            // is not horrible, its an O(1) lookup in a local cache
            macPortMap.get(mac) match {
                case currPortId if currPortId eq expectPortId =>
                    val tunIp = topology.vxlanTunnelEndpointFor(currPortId)
                    vxgw.asObserver.onNext(MacLocation(mac, ip, lsName, tunIp))
                case _ =>
            }
        } catch {
            case e: StateAccessException =>
                zkConnWatcher.handleError(
                    s"Retry removing IPs from MAC $mac",
                    makeRunnable { advertiseMacAndIpAt(mac, ip, expectPortId) },
                    e)
            case t: Throwable =>
                log.error(s"Failed to remove MAC $mac from port $expectPortId")
        }
    }

}
