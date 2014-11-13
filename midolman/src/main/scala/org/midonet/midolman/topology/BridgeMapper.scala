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
package org.midonet.midolman.topology

import java.lang.{Boolean => JBoolean, Long => JLong}
import java.util.UUID
import java.util.concurrent.TimeUnit.MILLISECONDS

import scala.collection.JavaConverters._
import scala.collection.concurrent.{Map => CMap, TrieMap}
import scala.collection.mutable
import scala.compat.Platform
import scala.concurrent.duration._

import akka.actor.ActorSystem

import com.typesafe.scalalogging.Logger

import rx.Observable
import rx.subjects.PublishSubject

import org.midonet.cluster.VlanPortMapImpl
import org.midonet.cluster.client.{IpMacMap, MacLearningTable}
import org.midonet.cluster.models.Topology.{Network => TopologyBridge}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.midolman.simulation.{Bridge => SimulationBridge}
import org.midonet.midolman.simulation.Bridge.UntaggedVlanId
import org.midonet.midolman.state.ReplicatedMap.Watcher
import org.midonet.midolman.state.{ReplicatedMap, StateAccessException}
import org.midonet.midolman.topology.devices.{RouterPort, BridgePort, Port}
import org.midonet.packets.{IPv4Addr, IPAddr, MAC}
import org.midonet.sdn.flows.FlowTagger.{tagForArpRequests, tagForBridgePort, tagForBroadcast, tagForFloodedFlowsByDstMac, tagForVlanPort}
import org.midonet.util.collection.Reducer
import org.midonet.util.concurrent.TimedExpirationMap
import org.midonet.util.functors._

object BridgeMapper {

    /**
     * Stores the state for a bridge port, including its peer port state, if the
     * port is interior, and exposes an [[rx.Observable]] that emits updates for
     * this port. This observable completes either when the port is deleted, or
     * when calling the complete() method, which is used to signal that a port
     * no longer belongs to a bridge.
     */
    private class PortState[D <: Port](val portId: UUID)
                                      (implicit vt: VirtualTopology) {

        def this(id: UUID, peerPortState: LocalPortState)
                (implicit vt: VirtualTopology) = {
            this(id)(vt)
            currentPeer = Some(peerPortState)
        }

        private var currentPort: Option[D] = None
        private var currentPeer: Option[PortState[_ <: Port]] = None
        private val filter = PublishSubject.create[Port]()

        val observable =
            VirtualTopology.observable[Port](portId).takeUntil(filter)
        /** Sets the port for this port state, and returns the previous port,
          * if any. */
        def :=(port: D): Option[D] = {
            val previousPort = currentPort
            currentPort = Some(port)
            previousPort
        }
        /** Sets the peer state for this port state, and returns the previous
          * peer port state, if any. */
        def :==(portState: PortState[_ <: Port]): Option[PortState[_ <: Port]] = {
            val previousPeer = currentPeer
            currentPeer = Some(portState)
            previousPeer
        }
        /** Completes the observable corresponding to this port state */
        def complete() = filter.onCompleted()
        /** Gets the underlying port option for this port state */
        def port: Option[D] = currentPort
        /** Gets the peer port state option */
        def peer: Option[PortState[_ <: Port]] = currentPeer
        /** Indicates whether the port state has received the port data */
        def isReady: Boolean = currentPort.isDefined
    }

    /**
     * The implementation of a [[MacLearningTable]] for a bridge. During
     * initialization the table creates an underlying [[ReplicatedMap]] for
     * the given bridge and VLAN, and exposes an [[rx.Observable]] with
     * notifications for MAC-port updates. A complete() methods stops watching
     * the underlying [[ReplicatedMap]] and completes the exposed observable
     * when the VLAN is no longer present on the bridge.
     */
    private class BridgeMacLearningTable(vt: VirtualTopology, bridgeId: UUID,
                                         vlanId: Short)
        extends MacLearningTable with MidolmanLogging {

        override def logSource =
            s"org.midonet.devices.bridge.bridge-$bridgeId.mac-learning-table"

        private val subject = PublishSubject.create[MacTableUpdate]
        private val watcher = new Watcher[MAC, UUID] {
            override def processChange(mac: MAC, oldPort: UUID, newPort: UUID)
            : Unit = {
                subject.onNext(MacTableUpdate(vlanId, mac, oldPort, newPort))
            }
        }
        private val map = vt.dataClient.bridgeGetMacTable(bridgeId, vlanId, true)

        // Initialize the replicated map.
        map.setConnectionWatcher(vt.connectionWatcher)
        map.addWatcher(watcher)
        map.start()

        val observable = subject.asObservable

        /** Gets the port for the specified MAC. */
        override def get(mac: MAC): UUID = map.get(mac)
        /** Adds a new MAC-port mapping to the MAC learning table. */
        override def add(mac: MAC, portId: UUID): Unit = try {
            map.put(mac, portId)
            log.info("Added MAC {} VLAN {} to port {}", mac, Short.box(vlanId),
                     portId)
        } catch {
            case e: Throwable =>
                log.error(s"Failed to add MAC {} VLAN {} to port {}",
                          mac, Short.box(vlanId), portId)
        }
        /** Removes a MAC-port mapping from the MAC learning table. */
        override def remove(mac: MAC, portId: UUID): Unit = try {
            map.removeIfOwnerAndValue(mac, portId)
            log.info("Added MAC {} VLAN {} to port {}", mac, Short.box(vlanId),
                     portId)
        } catch {
            case e: Throwable =>
                log.error(s"Failed to remove MAC {} VLAN {} to port {}",
                          mac, Short.box(vlanId), portId)
        }
        /** TODO: Obsolete method. */
        override def notify(cb: Callback3[MAC, UUID, UUID]): Unit = ???
        /** Stops the underlying replicated map and completes the observable. */
        def complete(): Unit = {
            map.stop()
            subject.onCompleted()
        }
    }

    /** Represents a MAC-port mapping */
    private case class MacPortMapping(vlanId: Short, mac: MAC, portId: UUID) {
        override def toString = s"{vlan=$vlanId mac=$mac port=$portId}"
    }
    /** Represents a MAC table update */
    private case class MacTableUpdate(vlanId: Short, mac: MAC, oldPort: UUID,
                                      newPort: UUID) {
        override def toString = s"{vlan=$vlanId mac=$mac oldPort=$oldPort " +
                                s"newPort=$newPort}"
    }

    /**
     * Handles the MAC-port mappings for a bridge. It adds/removes the (MAC,
     * VLAN, port) tuples to/from the underlying replicated map. The callbacks
     * guarantee the required happens-before relationship because all ZooKeeper
     * requests are served by a single threaded reactor.
     */
    private class MacLearning(tables: CMap[Short, BridgeMacLearningTable],
                              log: Logger, ttl: Duration) {
        private val map =
            new TimedExpirationMap[MacPortMapping, AnyRef](log, _ => ttl)
        private val reducer = new Reducer[MacPortMapping, Any, Unit] {
            override def apply(acc: Unit, mapping: MacPortMapping, value: Any)
            : Unit = {
                doOnMap(mapping.vlanId, _.remove(mapping.mac, mapping.portId))
            }
        }

        /** Adds a mapping if does not exist, and increases its reference count */
        def incrementRefCount(mapping: MacPortMapping): Unit = {
            if (map.putIfAbsentAndRef(mapping, mapping) eq null) {
                doOnMap(mapping.vlanId, _.add(mapping.mac, mapping.portId))
            }
        }
        /** Decrements the reference count for a given mapping */
        def decrementRefCount(mapping: MacPortMapping, currentTime: Long): Unit = {
            map.unref(mapping, currentTime)
        }
        /** Expires MAC-port mappings */
        def expireEntries(currentTime: Long): Unit = {
            map.obliterateIdleEntries(currentTime, (), reducer)
        }
        /** Executes the specified operation on the MAC learning table for the
          * given VLAN.*/
        private def doOnMap(vlanId: Short, op: MacLearningTable => Unit): Unit = {
            tables get vlanId match {
                case Some(table) => op(table)
                case None =>
                    log.warn(s"MAC learning table not found for VLAN $vlanId")
            }
        }
    }

    /**
     * The implementation of an [[IpMacMap]] for a bridge. During initialization
     * the map creates the underlying [[ReplicatedMap]] for the given bridge,
     * which allows the [[SimulationBridge]] to query the IPv4-MAC mappings.
     * A complete() method stops watching the underlying [[ReplicatedMap]].
     */
    private class BridgeIpv4MacMap(vt: VirtualTopology, bridgeId: UUID)
        extends IpMacMap[IPv4Addr] {
        private val map = vt.dataClient.getIp4MacMap(bridgeId)
        map.setConnectionWatcher(vt.connectionWatcher)
        map.start()

        /** Thread-safe query that gets the IPv4-MAC mapping*/
        override def get(ip: IPv4Addr): MAC = map.get(ip)
        /** Stops the underlying [[ReplicatedMap]]*/
        def complete(): Unit = map.stop()
    }

    /**
     * An implementation of the [[MacFlowCount]] trait that allows the
     * [[SimulationBridge]] device to update the MAC learning tables.
     */
    private class BridgeMacFlowCount(macLearning: MacLearning)
        extends MacFlowCount {
        override def increment(mac: MAC, vlanId: Short, portId: UUID): Unit = {
            macLearning.incrementRefCount(MacPortMapping(vlanId, mac, portId))
        }
        override def decrement(mac: MAC, vlanId: Short, portId: UUID): Unit = {
            macLearning.decrementRefCount(MacPortMapping(vlanId, mac, portId),
                                          Platform.currentTime)
        }
    }

    /**
     * An implementation of the [[BridgeRemoveFlowCallbackGenerator]] trait
     * that allows the [[SimulationBridge]] to get a callback function that
     * decrements the reference counter for a MAC-port mapping.
     */
    private class BridgeRemoveFlowCallbackGenerator(macLearning: MacLearning)
        extends RemoveFlowCallbackGenerator {
        override def getCallback(mac: MAC, vlanId: Short, portId: UUID)
        : Callback0 = makeCallback0 {
            macLearning.decrementRefCount(MacPortMapping(vlanId, mac, portId),
                                          Platform.currentTime)
        }
    }

    private type LocalPortState = PortState[BridgePort]
    private type PeerPortState = PortState[Port]
}

/**
 * A class that implements the [[DeviceMapper]] for a [[SimulationBridge]].
 */
final class BridgeMapper(bridgeId: UUID, implicit val vt: VirtualTopology)
                        (implicit actorSystem: ActorSystem)
        extends DeviceMapper[SimulationBridge](bridgeId, vt) {

    import BridgeMapper._

    override def logSource = s"org.midonet.devices.bridge.bridge-$bridgeId"

    private var bridge: TopologyBridge = null
    private val localPorts = new mutable.HashMap[UUID, LocalPortState]
    private val peerPorts = new mutable.HashMap[UUID, PeerPortState]
    private val exteriorPorts = new mutable.HashSet[UUID]
    private var oldExteriorPorts = Set.empty[UUID]
    private var oldRouterMacPortMap = Map.empty[MAC, UUID]
    private val macLearningTables = new TrieMap[Short, BridgeMacLearningTable]
    private val macLearning =
        new MacLearning(macLearningTables, log,
                        vt.config.getMacPortMappingExpireMillis millis)
    private val flowCount = new BridgeMacFlowCount(macLearning)
    private val flowCallbackGenerator =
        new BridgeRemoveFlowCallbackGenerator(macLearning)
    private var ipv4MacMap: BridgeIpv4MacMap = null


    // A subject that emits a port observable for evert port added to the
    // bridge.
    private lazy val portsSubject = PublishSubject.create[Observable[Port]]
    // A subject that emits a MAC updates observable for evert MAC learning
    // table added to this bridge. There is one MAC learning table for each
    // VLAN.
    private lazy val macUpdatesSubject =
        PublishSubject.create[Observable[MacTableUpdate]]
    // A subject that emits updates when a storage connection was
    // re-established.
    private lazy val connectionSubject = PublishSubject.create[TopologyBridge]
    private lazy val connectionRetryHandler = makeRunnable {
        connectionSubject.onNext(bridge)
    }

    // The output device observable for the bridge mapper.
    //
    //                on VT scheduler
    //                +-----------------------------+  +----------------+
    // store[Bridge]->| onCompleted(completeBridge) |->| map(mapBridge) |
    //                +-----------------------------+  +----------------+
    //       onNext (local ports, peer ports changed)     |           |
    //       +--------------------------------------------+           |
    //       |         +--------------+                               |
    // Obs[Obs[Port]]->| map(mapPort) |---+                           |
    //                 +--------------+   |                           |
    //                           +-------------------------+  +---------------+
    // Obs[Obs[MacTableUpdate]]->| filter(filterMacUpdate) |->| map(mapThrow) |
    //       |                   +-------------------------+  +---------------+
    //       |    on VT scheduler                   |           |     |
    //       |    +----------------------------------+  +---------------+
    // Obs.timer->| filter(filterMacExpirationTimer) |->| map(mapThrow) |
    //       |    +----------------------------------+  +---------------+
    //       |                                      |     |     |     |
    //       +----------------------+         merge +-----+--+--+-----+
    //       onNext (MAC maps)      |                        |
    //                       +----------------+  +-----------------------+
    //    SimulationBridge <-| map(mapDevice) |<-| filter(isBridgeReady) |
    //                       +----------------+  +-----------------------+
    private lazy val deviceObservable = Observable.merge[TopologyBridge](
        vt.store.observable(classOf[TopologyBridge], bridgeId)
            .observeOn(vt.scheduler)
            .doOnCompleted(makeAction0(completeBridge()))
            .map[TopologyBridge](makeFunc1(mapBridge)),
        Observable.merge(portsSubject)
            .map[TopologyBridge](makeFunc1(mapPort)),
        Observable.merge(macUpdatesSubject)
            .filter(makeFunc1(filterMacUpdate))
            .map[TopologyBridge](mapThrow("MAC updates should be filtered")),
        Observable.timer(
            vt.config.getMacPortMappingExpireMillis, 2000L, MILLISECONDS,
            vt.scheduler)
            .filter(makeFunc1(filterMacExpirationTimer))
            .map[TopologyBridge](mapThrow("MAC expiration should be filtered")),
        connectionSubject)
        .filter(makeFunc1(isBridgeReady))
        .map[SimulationBridge](makeFunc1(mapDevice))

    protected override def observable: Observable[SimulationBridge] =
        deviceObservable

    /**
     * Indicates the bridge device is ready, when all local and peer ports were
     * received.
     */
    private def isBridgeReady(bridge: TopologyBridge): JBoolean = {
        localPorts.count(!_._2.isReady) == 0 &&
        peerPorts.count(!_._2.isReady) == 0
    }

    /**
     * A map function that throws a mapper exception.
     */
    private def mapThrow(msg: String) = {
        makeFunc1[AnyRef, TopologyBridge](
            _ => throw new DeviceMapperException(classOf[SimulationBridge],
                                                 bridgeId,msg))
    }

    /**
     * This method is called when the bridge observable completes. It triggers
     * a completion of the device observable, by completing all ports subjects
     * (local and peer), mac updates subjects, and connection subject.
     */
    private def completeBridge(): Unit = {
        for (portState <- localPorts.values) {
            portState.complete()
        }
        for (portState <- peerPorts.values) {
            portState.complete()
        }
        for (macLearningTable <- macLearningTables.values) {
            macLearningTable.complete()
        }
        portsSubject.onCompleted()
        macUpdatesSubject.onCompleted()
        connectionSubject.onCompleted()
    }

    /**
     * A map function that processes updates from the topology bridge
     * observable, and returns a bridge state object. This action examines any
     * changes in the bridge ports, and adds/removes the corresponding port
     * observables.
     *
     * +---------+    +-----------+
     * | Storage |--->| bridgeMap |---> Observable[TopologyBridge]
     * +---------+    +-----------+
     *                      |
     *          Add: portsSubject onNext portObservable
     *          Remove: portObservable complete()
     */
    private def mapBridge(br: TopologyBridge): TopologyBridge = {
        bridge = br

        val portIds = Set(
            bridge.getPortIdsList.asScala.map(id => id.asJava).toArray: _*)

        // Complete the observables for the ports no longer part of this bridge.
        for ((portId, portState) <- localPorts.toList
             if !portIds.contains(portId)) {
            portState.complete()
            localPorts -= portId
            exteriorPorts -= portId
        }

        // Create observables for the new ports of this bridge, and notify them
        // on the ports observable.
        for (portId <- portIds if !localPorts.contains(portId)) {
            val portState = new LocalPortState(portId)
            localPorts += portId -> portState
            portsSubject onNext portState.observable
        }

        bridge
    }

    /**
     * A method observable that combines notifications from a dynamic set of
     * port observables. New observables are added to the funnel when emitted
     * by the ports subject, and removed from the funnel when the observables
     * complete.
     *
     *                                    +------------+
     * Observable[Observable[Port] ] ---> | portsMerge | ---> Observable[Port]
     *                                    +------------+
     */
    private def mapPort(port: Port): TopologyBridge = {
        // Check whether the received port is a local port or a peer port.
        if (localPorts.contains(port.id)) {
            log.debug("Received update for local port {}", port.id)
            updateLocalPort(port)
        } else if(peerPorts.contains(port.id)) {
            log.debug("Received update for peer port {}", port.id)
            updatePeerPort(port)
        } else {
            log.warn("Received update for unknown port {}, ignoring", port.id)
        }
        bridge
    }

    /**
     * An [[rx.Observable]] filter method that receives updates from the
     * observable of all MAC learning tables of this bridge. This method
     * processes these updates and always returns false in order not to
     * propagate any updates to the device observable
     *
     * All MAC learning tables
     *                        +-----------------+
     * Obs[MacTableUpdate] -> | filterMacUpdate | -> false
     *                        +-----------------+
     */
    private def filterMacUpdate(update: MacTableUpdate): JBoolean = {
        log.debug("MAC-port mapping for VLAN {} MAC {} was updated from port " +
                  "{} to {}", Short.box(update.vlanId), update.mac, update.oldPort,
                  update.newPort)

        if (null == update.newPort && null != update.oldPort) {
            log.debug("MAC {} VLAN {} removed from port {}", update.mac,
                      Short.box(update.vlanId), update.oldPort)
            vt.invalidate(tagForVlanPort(bridgeId, update.mac, update.vlanId,
                                         update.oldPort))
        }
        if (null != update.newPort && null != update.oldPort &&
            update.newPort != update.oldPort) {
            log.debug("MAC {} VLAN {} moved from port {} to {}", update.mac,
                      Short.box(update.vlanId), update.oldPort, update.newPort)
            vt.invalidate(tagForVlanPort(bridgeId, update.mac, update.vlanId,
                                         update.oldPort))
        }
        if (null != update.newPort && null == update.oldPort) {
            log.debug("MAC {} VLAN {} added to port {}", update.mac,
                      Short.box(update.vlanId), update.newPort)
            // Now we have the MAC entry in the table so we can deliver it to
            // the proper port instead of flooding it. As regards broadcast or
            // ARP requests:
            // 1. If this port was just added to the bridge, the invalidation
            //    will occur by the update to the bridge's list of ports.
            // 2. If we just forgot the MAC port association, no need of
            //    invalidating, broadcast and ARP requests were correctly
            //    delivered.
            vt.invalidate(tagForFloodedFlowsByDstMac(bridgeId, update.vlanId,
                                                     update.mac))
        }
        // Return false: filter all MAC-port updates.
        false
    }

    /**
     * Processes updates for local ports.
     */
    private def updateLocalPort(port: Port): Unit = {
        if (!port.isInstanceOf[BridgePort]) {
            log.error("Update for local port {} is not a bridge port", port.id)
            return
        }

        val portState = localPorts(port.id)

        // Update the port state.
        localPorts(port.id) := port.asInstanceOf[BridgePort]

        // Update the port membership to the exterior ports.
        if (port.isExterior) {
            exteriorPorts += port.id
        } else {
            exteriorPorts -= port.id
        }

        // Verify if a distinct previous port already exists: if yes, complete
        // the observable of that port.
        portState.peer match {
            case Some(peerPortState) if peerPortState.portId != port.peerId =>
                log.debug("Peer port for local port {} changed from {} to {}",
                          port.id, peerPortState.portId, port.peerId)
                peerPorts -= peerPortState.portId
                peerPortState.complete()
            case _ =>
        }

        // Create the state for the peer port.
        if (null != port.peerId && !peerPorts.contains(port.peerId)) {
            log.debug("New peer port {} for local port {}", port.peerId,
                      port.id)
            val peerPortState = new PeerPortState(port.peerId, portState)
            peerPorts += port.peerId -> peerPortState
            portState :== peerPortState match {
                case Some(previousPeer) if !peerPorts.contains(previousPeer.portId) =>
                    // The old peer port state is not longer used, complete its
                    // observable.
                    previousPeer.complete()
                case _ => // There was no previous peer port state.
            }
            portsSubject onNext portState.observable
        }
    }

    /**
     * Processes updates for peer ports.
     */
    private def updatePeerPort(port: Port): Unit = {
        // Update the peer port state.
        peerPorts(port.id) := port
    }

    /**
     * Processes MAC expiration timer notifications.
     */
    private def filterMacExpirationTimer(count: JLong): Boolean = {
        macLearning.expireEntries(Platform.currentTime)
        false
    }

    private def mapDevice(br: TopologyBridge): SimulationBridge = {

        val vlanPortMap = new VlanPortMapImpl
        val vlanSet = new mutable.HashSet[Short]
        var vlanBridgePeerPortId: Option[UUID] = None
        val routerMacToPortMap = new mutable.HashMap[MAC, UUID]
        val routerIpToMacMap = new mutable.HashMap[IPAddr, MAC]

        vlanSet += UntaggedVlanId

        // Compute the VLAN bridge peer port ID.
        for (portState <- localPorts.values; localPort = portState.port.get
             if localPort.isInterior) {
            val peerState = portState.peer.get
            val peerPort = peerState.port.get

            peerPort match {
                case bridgePort: BridgePort =>
                    log.debug("Bridge peer port {} for local port {}",
                              peerState.portId, localPort.id)
                    if (UntaggedVlanId != localPort.vlanId) {
                        // This is the VLAN aware bridge.
                        log.debug("Local port {} mapped to VLAN ID {}",
                                  localPort.id, Short.box(localPort.vlanId))
                        vlanPortMap.add(localPort.vlanId, localPort.id)
                        vlanSet += localPort.vlanId
                    } else if (UntaggedVlanId != peerPort.vlanId) {
                        // The peer is the VLAN aware bridge.
                        log.debug("Peer port {} mapped to VLAN ID {}",
                                  peerPort.id, Short.box(peerPort.vlanId))
                        vlanBridgePeerPortId = Some(peerPort.id)
                    } else {
                        log.warn("Peer port {} has no VLAN ID", peerPort.id)
                    }
                case routerPort: RouterPort =>
                    log.debug("Router peer port {} for local port {}",
                              peerState.portId, localPort.id)
                    // Learn the router MAC and IP.
                    routerMacToPortMap += routerPort.portMac -> localPort.id
                    routerIpToMacMap += routerPort.portIp -> routerPort.portMac
                    log.debug("Add bridge port {} linked to router port MAC: " +
                              "{} IP: {}", localPort.id, routerPort.portMac,
                              routerPort.portIp)
                case _ =>
                    log.warn("Unknown peer port for local port {}",
                             localPort.id)
            }
        }

        // Create MAC learning tables for new VLANs.
        for (vlanId <- vlanSet -- macLearningTables.keySet) {
            createMacLearningTable(vlanId)
        }

        // Remove MAC learning tables for deleted VLANs.
        for (vlanId <- macLearningTables.keySet -- vlanSet) {
            removeMacLearningTable(vlanId)
        }

        // If the bridge is ARP-enabled initialize the IPv4-MAC map.
        if (vt.config.getMidolmanBridgeArpEnabled && (ipv4MacMap eq null)) {
            ipv4MacMap = new BridgeIpv4MacMap(vt, bridgeId)
        }

        val addedMacPortMappings =
            routerMacToPortMap -- oldRouterMacPortMap.keys
        val deletedMacPortMappings =
            oldRouterMacPortMap -- routerMacToPortMap.keys
        // Invalidate the flows for the deleted MAC-port mappings.
        for ((mac, portId) <- deletedMacPortMappings) {
            vt.invalidate(tagForBridgePort(bridgeId, portId))
        }
        // Invalidated all ARP requests.
        if (addedMacPortMappings.nonEmpty) {
            vt.invalidate(tagForArpRequests(bridgeId))
        }
        // Invalidate all flooded flows to the router port's specific MAC. We
        // don't expect MAC migration in this case, otherwise we'd be
        // invalidating unicast flows to device port's MAC.
        for ((mac, portId) <- addedMacPortMappings) {
            vt.invalidate(tagForFloodedFlowsByDstMac(bridgeId, UntaggedVlanId,
                                                     mac))
        }
        oldRouterMacPortMap = routerMacToPortMap.toMap

        // Invalidate the flows if the exterior ports have changed.
        if (exteriorPorts != oldExteriorPorts) {
            vt.invalidate(tagForBroadcast(bridgeId))
            oldExteriorPorts = exteriorPorts.toSet
        }

        // Create the simulation bridge.
        new SimulationBridge(
            bridge.getId,
            bridge.getAdminStateUp,
            bridge.getTunnelKey,
            macLearningTables.readOnlySnapshot(),
            ipv4MacMap,
            flowCount,
            Option(bridge.getInboundFilterId),
            Option(bridge.getOutboundFilterId),
            vlanBridgePeerPortId,
            bridge.getVxlanPortIdsList.asScala.map(_.asJava),
            flowCallbackGenerator,
            oldRouterMacPortMap,
            routerIpToMacMap.toMap,
            vlanPortMap,
            exteriorPorts.toList)
    }

    /**
     * Create a new MAC learning table for this VLAN, add it to the MAC learning
     * tables map, and emit its observable on the MAC updates subject.
     */
    private def createMacLearningTable(vlanId: Short): Unit = {
        log.debug("Create MAC learning table for VLAN {}", Short.box(vlanId))
        try {
            val table = new BridgeMacLearningTable(vt, bridgeId, vlanId)
            macLearningTables += vlanId -> table
            macUpdatesSubject onNext table.observable
        } catch {
            case e: StateAccessException =>
                log.warn("Error retrieving MAC-port table for VLAN {}",
                         Short.box(vlanId), e)
                vt.connectionWatcher.handleError(
                    bridgeId.toString, connectionRetryHandler, e)
        }
    }

    /**
     * Removes the table and call its complete method to stop its underlying
     * replicated map, and complete the MAC updates observable.
     */
    private def removeMacLearningTable(vlanId: Short): Unit = {
        log.debug("Remove MAC learning table for VLAN {}", Short.box(vlanId))
        macLearningTables remove vlanId match {
            case Some(table) => table.complete()
            case None =>
                log.warn("No MAC learning table for VLAN {}", Short.box(vlanId))
        }
    }

}
