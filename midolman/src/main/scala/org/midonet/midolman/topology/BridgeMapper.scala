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
package org.midonet.midolman.topology

import java.lang.{Boolean => JBoolean, Long => JLong}
import java.util.{UUID, ArrayList => JArrayList}

import javax.annotation.Nullable

import scala.collection.JavaConverters._
import scala.collection.concurrent.{TrieMap, Map => CMap}
import scala.collection.mutable
import scala.compat.Platform
import scala.concurrent.duration._
import scala.reflect.ClassTag
import scala.util.control.NonFatal

import rx.Observable
import rx.subjects.{PublishSubject, Subject}

import org.midonet.cluster.VlanPortMapImpl
import org.midonet.cluster.client.{IpMacMap, MacLearningTable}
import org.midonet.cluster.data.storage.StateTable
import org.midonet.cluster.models.Topology.{Network => TopologyBridge}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.simulation.Bridge.{MacFlowCount, RemoveFlowCallbackGenerator, UntaggedVlanId}
import org.midonet.midolman.simulation.{Bridge => SimulationBridge, _}
import org.midonet.midolman.state.ReplicatedMap
import org.midonet.packets.{IPAddr, IPv4Addr, MAC}
import org.midonet.sdn.flows.FlowTagger.{tagForArpRequests, tagForBridgePort, tagForBroadcast, tagForFloodedFlowsByDstMac, tagForVlanPort}
import org.midonet.util.collection.Reducer
import org.midonet.util.concurrent.TimedExpirationMap
import org.midonet.util.functors._
import org.midonet.util.logging.Logger

object BridgeMapper {

    /**
     * Stores the state for a bridge port, including its peer port state, if the
     * port is interior, and exposes an [[rx.Observable]] that emits updates for
     * this port. This observable completes either when the port is deleted, or
     * when calling the complete() method, which is used to signal that a port
     * no longer belongs to a bridge.
     */
    private class PortState[D >: Null <: Port](val portId: UUID)
                                              (implicit tag: ClassTag[D]){

        def this(id: UUID, peerPortState: LocalPortState)
                (implicit tag: ClassTag[D]) = {
            this(id)(tag)
            currentPeer = peerPortState
        }

        private var currentPort: Port = null
        private var currentPeer: PortState[_ <: Port] = null
        private val mark = PublishSubject.create[Port]()

        val observable = VirtualTopology.observable(classOf[Port], portId)
            .doOnNext(makeAction1(port => currentPort = port))
            .takeUntil(mark)

        /** Sets the peer state for this port state, and returns the previous
          * peer port state, if any. */
        def setPeer(portState: PortState[_ <: Port]): PortState[_ <: Port] = {
            val previousPeer = currentPeer
            currentPeer = portState
            previousPeer
        }
        /** Completes the observable corresponding to this port state */
        def complete() = mark.onCompleted()
        /** Gets the underlying port for this port state */
        @Nullable def port: D = currentPort.asInstanceOf[D]
        /** Gets the peer port state */
        @Nullable def peer: PortState[_ <: Port] = currentPeer
        /** Indicates whether the port state has received the port data */
        @inline def isReady: Boolean = currentPort ne null
        /** Indicates whether the port is of the expected type [[D]]. */
        @inline def isValidPortType: Boolean = {
            tag.runtimeClass.isAssignableFrom(currentPort.getClass)
        }
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
                                         vlanId: Short, log: Logger)
        extends MacLearningTable {

        private val mark = PublishSubject.create[MacTableUpdate]
        private val table = vt.stateTables.bridgeMacTable(bridgeId, vlanId)
        table.start()

        val observable = table.observable
            .map[MacTableUpdate](makeFunc1(update => {
                MacTableUpdate(vlanId, update.key, update.oldValue,
                               update.newValue)
            }))
            .takeUntil(mark)

        /** Gets the port for the specified MAC. */
        override def get(mac: MAC): UUID = table.getLocal(mac)
        /** Adds a new MAC-port mapping to the MAC learning table. */
        override def add(mac: MAC, portId: UUID): Unit = {
            try {
                log.debug("Mapping MAC {}, VLAN {} to port {}",
                          mac, Short.box(vlanId), portId)
                table.add(mac, portId)
            } catch {
                case NonFatal(e) =>
                    log.warn("Failed to map MAC {}, VLAN {} to port {}",
                             mac, Short.box(vlanId), portId)
            }
        }
        /** Removes a MAC-port mapping from the MAC learning table. */
        override def remove(mac: MAC, portId: UUID): Unit = {
            val vlanIdObj = Short.box(vlanId)
            log.debug("Removing mapping from MAC {}, VLAN {} to port {}",
                      mac, vlanIdObj, portId)
            try {
                if (!table.remove(mac, portId))
                    log.debug("No mapping from MAC {}, VLAN {} to port {} " +
                              "owned by this node.", mac, vlanIdObj, portId)
            } catch {
                case NonFatal(t) =>
                    log.warn("Failed to remove mapping from MAC {}, VLAN {} " +
                             "to port {}", mac, vlanIdObj, portId, t)
            }
        }
        /** Stops the underlying replicated map and completes the observable. */
        def complete(): Unit = {
            table.stop()
            mark.onCompleted()
        }

        def ready: Observable[StateTable.Key] = table.ready

        def isReady: Boolean = table.isReady
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

        /** Adds a mapping if it does not exist, and increases its reference count */
        def incrementRefCount(mapping: MacPortMapping): Unit = {
            if (map.putIfAbsentAndRef(mapping, mapping) == 1) {
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
        private val table = vt.stateTables.bridgeArpTable(bridgeId)
        table.start()

        /** Thread-safe query that gets the IPv4-MAC mapping*/
        override def get(ip: IPv4Addr): MAC = table.getLocal(ip)

        override def put(ip: IPv4Addr, mac: MAC): Unit = {
            table.remove(ip)
            table.add(ip, mac)
        }

        /** Stops the underlying [[ReplicatedMap]]*/
        def complete(): Unit = table.stop()

        def ready: Observable[StateTable.Key] = table.ready

        def isReady: Boolean = table.isReady
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
     * An implementation of the [[RemoveFlowCallbackGenerator]] trait that
     * allows the [[SimulationBridge]] to get a callback function that
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

    private type LocalPortState = PortState[Port]
    private type PeerPortState = PortState[Port]
}

/**
 * A class that implements the [[DeviceMapper]] for a [[SimulationBridge]].
 */
final class BridgeMapper(bridgeId: UUID, override val vt: VirtualTopology,
                         val traceChainMap: mutable.Map[UUID,Subject[Chain,Chain]])
        extends VirtualDeviceMapper(classOf[SimulationBridge], bridgeId, vt)
        with TraceRequestChainMapper[SimulationBridge] {

    import BridgeMapper._

    override def logSource = "org.midonet.devices.bridge"
    override def logMark = s"bridge:$bridgeId"

    private val mirrorsTracker = new ObjectReferenceTracker(vt, classOf[Mirror], log)
    private val chainsTracker = new ObjectReferenceTracker(vt, classOf[Chain], log)
    private var bridge: TopologyBridge = null
    private val localPorts = new mutable.HashMap[UUID, LocalPortState]
    private val peerPorts = new mutable.HashMap[UUID, PeerPortState]
    private val exteriorPorts = new mutable.HashSet[UUID]
    private val routerIpToMacMap = new mutable.HashMap[IPAddr, MAC]

    private var oldExteriorPorts = Set.empty[UUID]
    private var oldRouterMacPortMap = Map.empty[MAC, UUID]
    private var vlanPortMap: VlanPortMapImpl = null
    private var vlanPeerBridgePortId: Option[UUID] = None

    private var traceChain: Option[UUID] = None

    private val macLearningTables = new TrieMap[Short, BridgeMacLearningTable]
    private val macLearning =
        new MacLearning(macLearningTables, log,
                        vt.config.bridge.macPortMappingExpiry millis)
    private val flowCount = new BridgeMacFlowCount(macLearning)
    private val flowCallbackGenerator =
        new BridgeRemoveFlowCallbackGenerator(macLearning)
    private var ipv4MacMap: BridgeIpv4MacMap = null

    private var deviceReady = false


    // A subject that emits a port observable for every port added to the
    // bridge.
    private lazy val portsSubject = PublishSubject.create[Observable[Port]]
    // A subject that emits a MAC updates observable for every MAC learning
    // table added to this bridge. There is one MAC learning table for each
    // VLAN.
    private val macUpdatesSubject =
        PublishSubject.create[Observable[MacTableUpdate]]
    private val macUpdatesSubscription = Observable
        .merge(macUpdatesSubject)
        .subscribe(makeAction1(macUpdated), makeAction1(onThrow))
    // A subscription for the timer action, which expires entries in the MAC
    // learning tables.
    //            on VT scheduler
    //            +---------------------------------+
    // Obs.timer->| subscribe(onMacExpirationTimer) |
    //            +---------------------------------+
    private val timerSubscription = Observable.interval(
            vt.config.bridge.macPortMappingExpiry, // Initial delay
            2000L, // Update interval
            MILLISECONDS, // Time unit
            vt.vtScheduler)
        .subscribe(makeAction1(onMacExpirationTimer), makeAction1(onThrow))

    // A subject that emits updates when the bridge state table have loaded.
    private lazy val stateTableSubject =
        PublishSubject.create[Observable[StateTable.Key]]

    // The output device observable for the bridge mapper.
    //
    //                on VT scheduler
    //                +----------------------------+  +-----------------------+
    // store[Bridge]->| onCompleted(bridgeDeleted) |->| onNext(bridgeUpdated) |
    //                +----------------------------+  +-----+-----------+-----+
    //   onNext(VT.observable[Port])                        |           |
    //   +--------------------------------------------------+           |
    //   |             +------------------+                             |
    // Obs[Obs[Port]]->| map(portUpdated) |-----------------------------+ merge
    //                 +------------------+                             |
    //   +--------------------------------------------------------------+
    //   |  +-----------------------+  +--------------------+
    //   +->| filter(isBridgeReady) |->| map(deviceUpdated) |-> SimulationBridge
    //      +-----------------------+  +-------+------------+
    //   +-------------------------------------+
    //   |                       +-----------------------+
    // Obs[Obs[MacTableUpdate]]->| subscribe(macUpdated) |
    //                           +-----------------------+
    private lazy val stateTableObservable = Observable
        .merge(stateTableSubject)
        .observeOn(vt.vtScheduler)
        .map[TopologyBridge](makeFunc1(stateTableReady))
    private lazy val portsObservable = Observable
        .merge(portsSubject)
        .filter(makeFunc1(isPortKnown))
        .map[TopologyBridge](makeFunc1(portUpdated))
    private lazy val bridgeObservable = vt.store
        .observable(classOf[TopologyBridge], bridgeId)
        .observeOn(vt.vtScheduler)
        .doOnCompleted(makeAction0(bridgeDeleted()))
        .doOnNext(makeAction1(bridgeUpdated))
    private lazy val deviceObservable = Observable
        .merge[TopologyBridge](portsObservable,
                               traceChainObservable.map[TopologyBridge](
                                   makeFunc1(traceChainUpdated)),
                               mirrorsTracker.refsObservable
                                   .map[TopologyBridge](makeFunc1(mirrorUpdated)),
                               chainsTracker.refsObservable
                                   .map[TopologyBridge](makeFunc1(chainUpdated)),
                               bridgeObservable)
        .doOnError(makeAction1(bridgeError))
        .filter(makeFunc1(isBridgeReady))
        .doOnNext(makeAction1(deviceUpdated))

    protected override lazy val observable = Observable
        .merge[TopologyBridge](stateTableObservable, deviceObservable)
        .filter(makeFunc1(isDeviceReady))
        .map[SimulationBridge](makeFunc1(buildDevice))

    /**
      * Indicates the bridge device data is ready, when the states for all local
      * and peer ports are ready, and all the chains are ready. This does not
      * include the bridge's state table.
      */
    private def isBridgeReady(br: TopologyBridge): JBoolean = {
        assertThread()
        val ready: JBoolean =
            localPorts.forall(_._2.isReady) && peerPorts.forall(_._2.isReady) &&
            isTracingReady && chainsTracker.areRefsReady &&
            mirrorsTracker.areRefsReady

        log.debug("Topology bridge ready: {}", ready)
        ready
    }

    /**
      * Indicates whether the bridge device is ready, including the bridge's
      * state tables.
      */
    private def isDeviceReady(br: TopologyBridge): JBoolean = {
        assertThread()
        val ready: JBoolean =
            (bridge ne null) && deviceReady &&
            ((ipv4MacMap eq null) || ipv4MacMap.isReady) &&
            macLearningTables.values.forall(_.isReady)

        log.debug("Simulation bridge ready: {}", ready)
        ready
    }

    /**
     * This method is called when the bridge is deleted. It triggers a
     * completion of the device observable, by completing all ports subjects
     * (local and peer), mac updates subjects, and connection subject.
     */
    private def bridgeDeleted(): Unit = {
        assertThread()
        log.debug("Bridge deleted")

        for (portState <- localPorts.values) {
            portState.complete()
        }
        for (portState <- peerPorts.values) {
            portState.complete()
        }
        for (macLearningTable <- macLearningTables.values) {
            macLearningTable.complete()
        }
        if (ipv4MacMap ne null) {
            ipv4MacMap.complete()
        }
        portsSubject.onCompleted()
        completeTraceChain()
        chainsTracker.completeRefs()
        mirrorsTracker.completeRefs()
        macUpdatesSubject.onCompleted()
        stateTableSubject.onCompleted()
        macUpdatesSubscription.unsubscribe()
        timerSubscription.unsubscribe()
    }

    /**
     * This error is called when an error occurs in the device observable. Its
     * purpose is to complete the subjects that are not merged into the device
     * observable.
     */
    private def bridgeError(e: Throwable): Unit = {
        macUpdatesSubscription.unsubscribe()
        timerSubscription.unsubscribe()
    }

    /**
     * Processes updates from the topology bridge observable. This examines the
     * addition/removal of the bridge ports, and adds/removes the corresponding
     * port observables.
     *                  +-----------------------+
     * store[Bridge]--->| onNext(bridgeUpdated) |---> Observable[TopologyBridge]
     *                  +-----------------------+
     *                              |
     *          Add: portsSubject onNext portObservable
     *          Remove: portObservable complete()
     */
    private def bridgeUpdated(br: TopologyBridge): Unit = {
        assertThread()
        assert(!macUpdatesSubscription.isUnsubscribed)
        assert(!timerSubscription.isUnsubscribed)

        deviceReady = false
        bridge = br

        val portIds = bridge.getPortIdsList.asScala.map(_.asJava).toSet
        log.debug("Update for bridge with ports {}", portIds)

        // Complete the observables for the ports no longer part of this bridge.
        for ((portId, portState) <- localPorts.toList
             if !portIds.contains(portId)) {
            portState.complete()
            if (portState.peer ne null) {
                peerPorts -= portState.peer.portId
                portState.peer.complete()
            }
            localPorts -= portId
            exteriorPorts -= portId
        }

        // Create observables for the new ports of this bridge, and notify them
        // on the ports observable.
        val addedPorts = new mutable.MutableList[PortState[_]]
        for (portId <- portIds if !localPorts.contains(portId)) {
            val portState = new LocalPortState(portId)
            localPorts += portId -> portState
            addedPorts += portState
        }

        // Publish observable for added ports.
        for (portState <- addedPorts) {
            portsSubject onNext portState.observable
        }

        // If the bridge is ARP-enabled initialize the IPv4-MAC map and
        // subscribe the feedback subject to its ready observable.
        if (vt.config.bridgeArpEnabled && (ipv4MacMap eq null)) {
            ipv4MacMap = new BridgeIpv4MacMap(vt, bridgeId)
            stateTableSubject onNext ipv4MacMap.ready
        }

        // Request trace chain be built if necessary
        requestTraceChain(bridge.getTraceRequestIdsList
                              .asScala.map(_.asJava).toList)

        // Request the chains for this bridge.
        chainsTracker.requestRefs(
            if (bridge.hasInboundFilterId) bridge.getInboundFilterId else null,
            if (bridge.hasOutboundFilterId) bridge.getOutboundFilterId else null)

        // Request the mirrors for this bridge.
        mirrorsTracker.requestRefs(
            bridge.getInboundMirrorIdsList.asScala.map(_.asJava) ++
            bridge.getOutboundMirrorIdsList.asScala.map(_.asJava) :_*)
    }

    /**
     * Indicates whether the port is either a local port or a peer port.
     *                      +------------+
     * Observable[Port]---> | portFilter | ---> Observable[Port]
     *                      +------------+
     */
    private def isPortKnown(port: Port): Boolean = {
        assertThread()
        if (localPorts.contains(port.id) || peerPorts.contains(port.id)) true
        else {
            log.warn("Update for unknown port {}, ignoring", port.id)
            false
        }
    }

    /**
      * Indicates that a state table is ready.
      */
    private def stateTableReady(key: StateTable.Key): TopologyBridge = {
        log debug s"State table $key ready"
        bridge
    }

    /**
     * Handles updates for ports, either local ports or peer ports. All ports
     * are automatically updated within the corresponding [[PortState]]. Updates
     * for local ports trigger an update of the corresponding peer port state.
     * The method returns the current bridge, such that the observable can be
     * included in the merge operation.
     *                      +-------------+
     * Observable[Port]---> | portUpdated | ---> Observable[TopologyBridge]
     *                      +-------------+
     */
    private def portUpdated(port: Port): TopologyBridge = {
        assertThread()
        // Check whether the received port is a local port or a peer port.
        if (localPorts.contains(port.id)) {
            log.debug("Update for local port {}", port.id)
            localPortUpdated(port)
        } else {
            log.debug("Update for peer port {}", port.id)
        }
        bridge
    }

    /** Handles updates for the chains. */
    private def chainUpdated(chain: Chain): TopologyBridge = {
        assertThread()
        log.debug("Bridge chain updated {}", chain)
        bridge
    }

    private def traceChainUpdated(chainId: Option[UUID]): TopologyBridge = {
        log.debug(s"Trace chain updated $chainId")
        traceChain = chainId
        bridge
    }

    /** Handles updates for the chains. */
    private def mirrorUpdated(mirror: Mirror): TopologyBridge = {
        assertThread()
        log.debug("Bridge mirror updated {}", mirror)
        bridge
    }

    /**
     * Emits an error on the connection observable to complete the device
     * notifications.
     */
    private def onThrow(e: Throwable): Unit = {
        stateTableSubject.onError(e)
    }

    /**
     * An [[rx.Observable]] observer method that receives updates from the
     * observable of all MAC learning tables of this bridge. This method
     * processes these updates and always returns false in order not to
     * propagate any updates to the device observable
     *
     * All MAC learning tables
     *                        +------------+
     * Obs[MacTableUpdate] -> | macUpdated |
     *                        +------------+
     */
    private def macUpdated(update: MacTableUpdate): Unit = {
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
    }

    /**
     * Processes updates for local ports.
     */
    private def localPortUpdated(port: Port): Unit = {
        port match {
            case bridgePort: BridgePort => // Process bridge port
            case vxlanPort: VxLanPort =>
                if (!exteriorPorts.contains(port.id)) {
                    log.debug("Add VXLAN port {} to exterior ports", port.id)
                    exteriorPorts += port.id
                }
                return
            case _ =>
                log.warn("Local port {} is not a bridge or VXLAN port", port.id)
                exteriorPorts -= port.id
                return
        }

        val portState = localPorts(port.id)

        // Update the port membership to the exterior ports.
        if (port.isExterior) {
            exteriorPorts += port.id
        } else {
            exteriorPorts -= port.id
        }

        // Verify that a peer port has changed: if yes, complete the observable
        // of that port.
        if ((portState.peer ne null) && portState.peer.portId != port.peerId) {
            log.debug("Peer port for local port {} changed from {} to {}",
                      port.id, portState.peer.portId, port.peerId)
            peerPorts -= portState.peer.portId
            portState.peer.complete()
        }

        // Create the state for the peer port.
        if (null != port.peerId && !peerPorts.contains(port.peerId)) {
            log.debug("New peer port {} for local port {}", port.peerId,
                      port.id)
            val peerPortState = new PeerPortState(port.peerId, portState)
            peerPorts += port.peerId -> peerPortState
            portState setPeer peerPortState
            portsSubject onNext peerPortState.observable
        }
    }

    /**
     * Processes MAC expiration timer notifications.
     */
    private def onMacExpirationTimer(count: JLong): Unit = {
        log.trace("MAC expiration timer {}", count)
        macLearning.expireEntries(Platform.currentTime)
    }

    /**
     * Maps the [[TopologyBridge]] to a [[SimulationBridge]] device. In
     * addition, the method processes bridge updates the following way:
     * - for all interior ports of the bridge, it computes the set of bridge
     *   VLANs, the VLAN-port mappings, the ID of a peer port of a VLAN-aware
     *   bridge, the MAC-port mappings for peer router ports, and the IP-MAC
     *   mappings for peer router ports
     * - creates/deletes the MAC learning tables for created/deleted VLANs,
     *   and begins emitting updates from those tables on the
     *   [[macUpdatesSubject]]
     * - updates the set of exterior ports for this bridge
     * - performs flow invalidation
     */
    private def deviceUpdated(br: TopologyBridge): Unit = {
        log.debug("Refreshing bridge state")
        assertThread()

        val vlanSet = new mutable.HashSet[Short]
        val routerMacToPortMap = new mutable.HashMap[MAC, UUID]

        routerIpToMacMap.clear()
        vlanPortMap = new VlanPortMapImpl
        vlanPeerBridgePortId = None
        vlanSet += UntaggedVlanId

        // Compute the VLAN bridge peer port ID.
        for (portState <- localPorts.values if portState.isValidPortType;
             localPort = portState.port if localPort.isInterior) {
            val peerState = portState.peer
            val peerPort = peerState.port

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
                        log.debug("Local port {} to peer port {} mapped to " +
                                  "VLAN ID {}", localPort.id, peerPort.id,
                                  Short.box(peerPort.vlanId))
                        vlanPeerBridgePortId = Some(localPort.id)
                    } else {
                        log.warn("Peer port {} has no VLAN ID", peerPort.id)
                    }
                case routerPort: RouterPort =>
                    log.debug("Router peer port {} for local port {}",
                              peerState.portId, localPort.id)
                    // Learn the router MAC and IP.
                    routerMacToPortMap += routerPort.portMac -> localPort.id
                    if (routerPort.portAddress4 ne null) {
                        routerIpToMacMap +=
                           routerPort.portAddress4.getAddress -> routerPort.portMac
                    }
                    log.debug("Add bridge port {} linked to router port MAC: " +
                              "{} IP: {}", localPort.id, routerPort.portMac,
                              routerPort.portAddress4)
                case _ =>
                    log.warn("Unsupported peer port for local port {}",
                             localPort.id)
            }
        }

        // Create MAC learning tables for new VLANs.
        val readyObservables =
            for (vlanId <- vlanSet if !macLearningTables.contains(vlanId)) yield {
                createMacLearningTable(vlanId)
            }

        // Remove MAC learning tables for deleted VLANs.
        for (vlanId <- macLearningTables.keys if !vlanSet.contains(vlanId)) {
            removeMacLearningTable(vlanId)
        }

        // Publish the ready observables for the new tables.
        for (readyObservable <- readyObservables) {
            //stateTableSubject onNext readyObservable
        }

        val addedMacPortMappings = routerMacToPortMap -- oldRouterMacPortMap.keys
        val deletedMacPortMappings = oldRouterMacPortMap -- routerMacToPortMap.keys
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

        deviceReady = true
    }

    /**
      * Builds the bridge device after the bridge and the bridge's state tables
      * are ready.
      */
    private def buildDevice(br: TopologyBridge): SimulationBridge = {

        val inFilters = new JArrayList[UUID]()
        val outFilters = new JArrayList[UUID]()
        traceChain.foreach(inFilters.add)
        if (bridge.hasInboundFilterId) {
            inFilters.add(bridge.getInboundFilterId.asJava)
        }
        if (bridge.hasOutboundFilterId) {
            outFilters.add(bridge.getOutboundFilterId.asJava)
        }

        val preInFilterMirrors = new JArrayList[UUID]()
        preInFilterMirrors.addAll(bridge.getInboundMirrorIdsList.asScala.map(_.asJava).asJava)
        val postOutFilterMirrors = new JArrayList[UUID]()
        postOutFilterMirrors.addAll(bridge.getOutboundMirrorIdsList.asScala.map(_.asJava).asJava)

        // Create the simulation bridge.
        val device = new SimulationBridge(
            bridge.getId,
            bridge.getAdminStateUp,
            bridge.getTunnelKey,
            macLearningTables.readOnlySnapshot(),
            ipv4MacMap,
            flowCount,
            inFilters,
            outFilters,
            vlanPeerBridgePortId,
            flowCallbackGenerator,
            oldRouterMacPortMap,
            routerIpToMacMap.toMap,
            vlanPortMap,
            exteriorPorts.toList,
            bridge.getDhcpIdsList.asScala.map(_.asJava).toList,
            preInFilterMirrors,
            postOutFilterMirrors
        )

        log.debug("Build bridge: {}", device)

        deviceReady = false
        device
    }

    /**
     * Create a new MAC learning table for this VLAN, add it to the MAC learning
     * tables map, and emit its observable on the MAC updates subject.
     */
    private def createMacLearningTable(vlanId: Short): Observable[StateTable.Key] = {
        log.debug("Create MAC learning table for VLAN {}", Short.box(vlanId))
        val table = new BridgeMacLearningTable(vt, bridgeId, vlanId, log)
        macLearningTables += vlanId -> table
        macUpdatesSubject onNext table.observable
        stateTableSubject onNext table.ready
        table.ready
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
