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

import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.annotation.Nullable

import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.collection.mutable

import rx.subjects.{BehaviorSubject, PublishSubject}
import rx.{Observable, Observer}

import org.midonet.cluster.models.Topology.{Port, Network, Vtep}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.packets.IPv4Addr
import org.midonet.util.functors._

object VxLanPortMapper {

    type TunnelIpAndVni = (IPv4Addr, Int)

    private val ref = new AtomicReference[VxLanPortMapper](null)

    private[topology] def start(vt: VirtualTopology)
    : Observable[Map[TunnelIpAndVni, UUID]] = {
        ref.compareAndSet(null, new VxLanPortMapper(vt))
        val subject = BehaviorSubject.create[Map[TunnelIpAndVni, UUID]]()
        val observable = ref.get.observable
        observable.subscribe(subject)
        observable
    }

    /** Synchronous query method to retrieve the uuid of an external vxlan port
     *  associated to the given vni key and tunnel IP. The vni key is 24bits and
     *  its highest byte is ignored. */
    def uuidOf(tunnelIp: IPv4Addr, vni: Int): Option[UUID] = {
        if (ref.get != null) {
            ref.get.uuidOf(tunnelIp, vni & (1 << 24) - 1)
        } else {
            None
        }
    }

    /**
     * Stores the state for a network and exposes an observable for it. If the
     * network is not bound to any Vtep, we unsubscribe from it by calling
     * the complete() method below.
     */
    private final class NetworkState(networkId: UUID, vt: VirtualTopology) {
        private var prevNetwork: Network = null
        private var currentNetwork: Network = null
        private var portIds: List[UUID] = null

        private val mark = PublishSubject.create[Network]()
        /** The network observable, notifications on the VT thread. */
        val observable = vt.store.observable(classOf[Network], networkId)
            .observeOn(vt.vtScheduler)
            .doOnNext(makeAction1(network => {
                prevNetwork = currentNetwork
                currentNetwork = network
                portIds = currentNetwork.getVxlanPortIdsList.asScala
                                        .map(_.asJava).toList
            }))
            .distinctUntilChanged
            .takeUntil(mark)

        /** Completes the observable corresponding to this network state. */
        def complete() = mark.onCompleted()
        /** Gets the previous network or null, if none is set. */
        @Nullable
        def previousNetwork: Network = prevNetwork
        /** Gets the current network or null, if none is set. */
        @Nullable
        def network: Network = currentNetwork
        /** Gets the list of vxlan port ids attached to this network. */
        @Nullable
        def vxlanPortIds: List[UUID] = portIds
        /** Indicates whether the network state has received the network data. */
        def isReady: Boolean = currentNetwork ne null
    }

    /**
     * Stores the state for a port and exposes an observable for it. If the
     * port is not bound to any Vtep, we unsubscribe from it by calling
     * the complete() method below.
     */
    private final class PortState(portId: UUID,
                                  vtepPortMap: mutable.Map[UUID, UUID],
                                  vt: VirtualTopology) {
        private var currentPort: Port = null
        private var vtepID: UUID = null
        /** The network observable, notifications on the VT thread. */
        val observable = vt.store.observable(classOf[Port], portId)
            .observeOn(vt.vtScheduler)
            .doOnNext(makeAction1(port => {
                currentPort = port
                vtepID = currentPort.getVtepId.asJava
                vtepPortMap += vtepID -> portId
            }))
            .take(1)

        /** Gets the current network or null, if none is set. */
        @Nullable
        def port: Port = currentPort
        /** Gets the Vtep ID this port is bound to or null, if none is set. */
        @Nullable
        def vtepId: UUID = vtepID
        /** Indicates whether the network state has received the network data. */
        def isReady: Boolean = currentPort ne null
    }
}

/**
 * A mapper that constructs and maintains a map
 * of (TunnelIP, VNI) -> PortId entries.
 */
private[topology] class VxLanPortMapper(vt: VirtualTopology)
    extends MidolmanLogging {

    import VxLanPortMapper._

    private var vniUUIDMap = new TrieMap[TunnelIpAndVni, UUID]()

    private val store = vt.backend.store

    /* A dummy object to signal the deletion of a Vtep. */
    private val vtepDeleted = 0
    private val vtepDeletionSubject = PublishSubject.create[Any]()

    private val vteps = new mutable.HashMap[UUID, Vtep]()
    private val vtepObservables = new mutable.HashMap[Observable[Vtep], UUID]()
    private val vtepNetworkMap = new mutable.HashMap[UUID, UUID]()
    private val vtepPortMap = new mutable.HashMap[UUID, UUID]()

    private val networks = new mutable.HashMap[UUID, NetworkState]()
    private val networksSubject = PublishSubject.create[Observable[Network]]()

    private val ports = new mutable.HashMap[UUID, PortState]()
    private val portsSubject = PublishSubject.create[Observable[Port]]()

    private def subscribeToNetwork(networkId: UUID, vtepId: UUID): Unit = {
        vtepNetworkMap += vtepId -> networkId
        val networkState = new NetworkState(networkId, vt)
        networks += networkId -> networkState
        networksSubject onNext networkState.observable
    }

    private def unsubscribeFromNetwork(networkId: UUID, vtepId: UUID): Unit = {
        vtepNetworkMap -= vtepId
        networks(networkId).complete()
        networks -= networkId
    }

    private def vtepUpdated(vtep: Vtep): Vtep = {
        val vtepId = vtep.getId.asJava

        if (vtep.getBindingsCount == 0) {
            vtepNetworkMap.get(vtepId) match {
                case Some(netId) =>
                    log.debug("Vtep: {} not bound  to any networks.", vtepId)
                    vteps -= vtepId
                    unsubscribeFromNetwork(netId, vtepId)
                case _ => // do nothing
            }
        } else {
            vteps(vtepId) = vtep

            // We assume that each vtep is bound to at most one network
            val networkId = vtep.getBindings(0).getNetworkId.asJava

            vtepNetworkMap.get(vtepId) match {
                case Some(netId) if networkId != netId =>
                    unsubscribeFromNetwork(netId, vtepId)
                    subscribeToNetwork(networkId, vtepId)
                case None =>
                    subscribeToNetwork(networkId, vtepId)
                case _ => // do nothing
            }
        }

        vtep
    }

    private def networkUpdated(network: Network): Network = {
        val newPortIds = network.getVxlanPortIdsList.asScala.map(_.asJava)

        /* Subscribe to new ports .*/
        for (portId <- newPortIds.filterNot(ports.contains)) {
            val portState = new PortState(portId, vtepPortMap, vt)
            ports += portId -> portState
            portsSubject onNext portState.observable
        }

        /* Unsubscribe from ports not bound to the network anymore. */
        val prevNetwork = networks(network.getId.asJava).previousNetwork
        if (prevNetwork != null) {
            val oldPortIds = prevNetwork.getVxlanPortIdsList.asScala.map(_.asJava)
            for (portId <- oldPortIds if !newPortIds.contains(portId)) {
                val portState = ports(portId)
                ports -= portId
                vtepPortMap -= portState.vtepId
            }
        }
        network
    }

    /**
     * Returns true iff:
     * -this is a newly received network
     * -the network's vxlan ports changed
     */
    private def hasNetworkChanged(network: Network): Boolean = {
        val networkId = network.getId.asJava
        networks.get(networkId) match {
            case Some(net) if net.previousNetwork != null =>
                val oldPorts = net.previousNetwork.getVxlanPortIdsList.asScala.toSet
                val newPorts = network.getVxlanPortIdsList.asScala.toSet
                oldPorts != newPorts
            case _ => true
        }
    }

    /**
     * Returns true iff:
     * -the vtep is received for the 1st time
     * -the vtep is bound to a new network
     * -the vtep is not bound to a network anymore
     */
    private def hasVtepChanged(vtep: Vtep): Boolean = {
        val vtepId = vtep.getId.asJava
        vtepNetworkMap.get(vtepId) match {
            case Some(prevNetworkId) =>
                if (vtep.getBindingsCount == 0) {
                    /* This checks that the vtep was just unbound from the
                       network. */
                    val previousVtep = vteps(vtepId)
                    previousVtep.getBindingsCount > 0
                } else {
                    val newNetworkId = vtep.getBindings(0).getNetworkId.asJava
                    newNetworkId != prevNetworkId
                }

            case None => true
        }
    }

    private def vtepDeleted(observable: Observable[Vtep]): Unit = {
        val vtepId = vtepObservables(observable)
        vtepNetworkMap.get(vtepId) match {
            case Some(networkId) =>
                vtepNetworkMap -= vtepId
                vteps -= vtepId

                /* If no other vtep is bound to this network, then we can safely
                   delete it. */
                if (vtepNetworkMap.values.filter(_ == networkId).size == 0) {
                    networks -= networkId
                }

                vtepPortMap.get(vtepId) match {
                    case Some(portId) => ports -= portId
                    case None => // do nothing.
                }
                vtepPortMap -= vtepId

            case None => // do nothing.
        }

        vtepObservables -= observable
        vtepDeletionSubject onNext vtepDeleted
    }

    private def vtepObservable(observable: Observable[Vtep])
    : Observable[Vtep] = {
        observable.doOnEach(new Observer[Vtep]() {
            override def onNext(vtep: Vtep): Unit = {
                val vtepId = vtep.getId.asJava
                vtepObservables.get(observable) match {
                    case Some(obs) => // do nothing.
                    case None => vtepObservables(observable) = vtepId
                }
            }
            override def onCompleted(): Unit =
                vtepDeleted(observable)
            override def onError(e: Throwable): Unit =
                vtepDeleted(observable)
        })
    }

    /**
     * Builds the map if all networks bound to a Vtep have been received and
     * returns true if a new map was built.
     */
    private def buildMap(update: Any): Boolean = {
        if (networks.values.forall(_.isReady) && ports.values.forall(_.isReady)) {
            val newMap = new TrieMap[TunnelIpAndVni, UUID]()

            vteps.values.foreach(vtep => {
                val vtepId = vtep.getId.asJava
                val networkState = networks.get(vtepNetworkMap(vtepId))

                if (networkState.isDefined) {
                    val portId = vtepPortMap.get(vtepId)
                    if (portId.isDefined) {
                        // We assume one tunnel ip and one network per Vtep.
                        val tunnelIp = IPv4Addr.fromString(vtep.getTunnelIpsList.get(0))
                        val vni = networkState.get.network.getVni

                        newMap += (tunnelIp, vni) -> portId.get
                    }
                }
            })
            if (newMap != vniUUIDMap) {
                vniUUIDMap = newMap
                true
            } else {
                false
            }
        } else {
            false
        }
    }

    private val vtepsObservable =
        Observable.merge(store.observable(classOf[Vtep])
                              .map[Observable[Vtep]](makeFunc1(vtepObservable)))
                  .observeOn(vt.vtScheduler)
                  .filter(makeFunc1(hasVtepChanged))
                  .map(makeFunc1(vtepUpdated))

    private val networksObservable =
        Observable.merge(networksSubject)
                  .filter(makeFunc1(hasNetworkChanged))
                  .map(makeFunc1(networkUpdated))

    private val portsObservable = Observable.merge(portsSubject)

    private[topology] def observable: Observable[Map[TunnelIpAndVni, UUID]] =
        Observable.merge(portsObservable,
                         networksObservable,
                         vtepDeletionSubject,
                         vtepsObservable)
                  .filter(makeFunc1(buildMap))
                  .map[Map[TunnelIpAndVni, UUID]](makeFunc1(_ =>
                      vniUUIDMap.readOnlySnapshot.toMap))

    private[topology] def uuidOf(tunnelIp: IPv4Addr, vni: Int): Option[UUID] =
        vniUUIDMap get (tunnelIp, vni)
}
