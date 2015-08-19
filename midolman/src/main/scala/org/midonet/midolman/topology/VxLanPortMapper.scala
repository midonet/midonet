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

import rx.subjects.PublishSubject
import rx.{Observable, Observer}

import org.midonet.cluster.models.State.VtepConfiguration
import org.midonet.cluster.models.Topology.{Network, Port, Vtep}
import org.midonet.cluster.services.vxgw.data.VtepStateStorage
import org.midonet.cluster.util.IPAddressUtil._
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
        ref.get.observable
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
     * Stores the state for a port and exposes an observable for it. Since
     * a vxlan port is bound to exactly one vtep during its lifetime,
     * the observable below completes after emitting the port.
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

        /** Gets the current port or null, if none is set. */
        @Nullable
        def port: Port = currentPort
        /** Gets the vtep ID this port is bound to or null, if none is set. */
        @Nullable
        def vtepId: UUID = vtepID
        /** Indicates whether the port state has received the port data. */
        def isReady: Boolean = currentPort ne null
    }
}

/**
 * A mapper that constructs and maintains a map of (TunnelIP, VNI) -> PortId
 * entries.
 *
 * This mapper assumes the following:
 * -A logical switch can comprise multiple Vteps but a vtep can be bound to at
 *  most one network.
 * -The tunnel IP of a vtep and vni of a network are immutable.
 * -A Vxlan port is always bound to the same vtep. Vxlan ports can dynamically
 *  be added/remove to/from a network however.
 */
private[topology] class VxLanPortMapper(vt: VirtualTopology)
    extends MidolmanLogging {

    import VxLanPortMapper._

    private var vniUUIDMap = new TrieMap[TunnelIpAndVni, UUID]()

    private val store = vt.backend.store
    private val vtepStateStore =
        VtepStateStorage.asVtepStateStorage(vt.backend.stateStore)

    /* A dummy object to signal the deletion of a vtep. */
    private val vtepDeleted = 0
    private val vtepDeletionSubject = PublishSubject.create[Any]()

    private val vteps = new mutable.HashMap[UUID, Vtep]()
    /* Map of vtep observable to vtep id. This is used to clean-up vtep data
       when a vtep observable completes. */
    private val vtepObservableMap = new mutable.HashMap[Observable[Vtep], UUID]()

    /* Subject for vtep configuration observables. Configuration data of a vtep
       contains the tunnel IP of a vtep. */
    private val vtepStateSubject =
        PublishSubject.create[Observable[VtepConfiguration]]()
    private val vtepTunnelIps = new mutable.HashMap[UUID, IPv4Addr]()

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

        if (vtepsBoundToNetwork(networkId) == 0) {
            networks(networkId).complete()
            networks -= networkId
        }
    }

    /**
     * Method called when we receive a notification for a new vtep.
     */
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

    /**
     *  Method called when we receive a notification for a new network.
     */
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
                ports.get(portId) match {
                    case Some(portState) => vtepPortMap -= portState.vtepId
                    case None => // do nothing.
                }
                ports -= portId
            }
        }
        network
    }

    /**
     * Returns true iff:
     * -this is a newly received network
     * -the network's vxlan ports changed
     */
    private def hasNetworkChanged(newNetwork: Network): Boolean = {
        val networkId = newNetwork.getId.asJava
        networks.get(networkId) match {
            case Some(net) if net.previousNetwork != null =>
                val oldPorts = net.previousNetwork.getVxlanPortIdsList
                val newPorts = newNetwork.getVxlanPortIdsList
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
    private def hasVtepChanged(newVtep: Vtep): Boolean = {
        val vtepId = newVtep.getId.asJava
        vtepNetworkMap.get(vtepId) match {
            case Some(prevNetworkId) =>
                if (newVtep.getBindingsCount == 0) {
                    val previousVtep = vteps(vtepId)
                    previousVtep.getBindingsCount > 0
                } else {
                    val newNetworkId = newVtep.getBindings(0).getNetworkId.asJava
                    newNetworkId != prevNetworkId
                }
            case None => true
        }
    }

    /**
     * Returns the numbers of Vteps bound to a given network.
     */
    private def vtepsBoundToNetwork(networkId: UUID): Int =
        vtepNetworkMap.values.filter(_ == networkId).size

    /**
     * Method called when a vtep is deleted. The observable is the one
     * emitting the vtep's updates.
     */
    private def vtepDeleted(observable: Observable[Vtep]): Unit = {
        val vtepId = vtepObservableMap(observable)
        vtepNetworkMap.get(vtepId) match {
            case Some(networkId) =>
                unsubscribeFromNetwork(networkId, vtepId)

                vtepPortMap.get(vtepId) match {
                    case Some(portId) => ports -= portId
                    case None => // do nothing.
                }
                vtepPortMap -= vtepId

            case None => // do nothing.
        }
        vteps -= vtepId
        vtepTunnelIps -= vtepId
        vtepObservableMap -= observable
        vtepDeletionSubject onNext vtepDeleted
    }

    private def vtepObservable(observable: Observable[Vtep])
    : Observable[Vtep] = {
        observable.doOnEach(new Observer[Vtep]() {
            override def onNext(vtep: Vtep): Unit = {
                val vtepId = vtep.getId.asJava
                vtepObservableMap.get(observable) match {
                    case Some(obs) => // do nothing.
                    case None => vtepObservableMap(observable) = vtepId
                }
                vtepStateSubject onNext
                    vtepStateStore.vtepConfigObservable(vtepId)
            }
            override def onCompleted(): Unit =
                vtepDeleted(observable)
            override def onError(e: Throwable): Unit =
                vtepDeleted(observable)
        })
    }

    private def tunnelIpsReady: Boolean =
        vteps.keySet.forall(vtepTunnelIps.contains)

    /**
     * Builds a new map and returns true iff:
     * -All networks and vxlan ports bound to a vtep have been received
     * -All vtep tunnel ips have been received
     */
    private def buildMap(update: Any): Boolean = {
        if (networks.values.forall(_.isReady) &&
            ports.values.forall(_.isReady) && tunnelIpsReady) {

            val newMap = new TrieMap[TunnelIpAndVni, UUID]()

            vteps.values.foreach(vtep => {
                val vtepId = vtep.getId.asJava
                networks.get(vtepNetworkMap(vtepId)) match {
                    case Some(networkState) =>
                        vtepPortMap.get(vtepId) match {
                            case Some(portId) =>
                                /* We assume one tunnel ip and one network per
                                   vtep. */
                                val tunnelIp = vtepTunnelIps(vtepId)
                                val vni = networkState.network.getVni
                                newMap += (tunnelIp, vni) -> portId
                            case None => // do nothing.
                        }
                    case None => // do nothing.
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

    /* An observable emitting vtep updates. */
    private val vtepsObservable =
        Observable.merge(store.observable(classOf[Vtep])
                              .map[Observable[Vtep]](makeFunc1(vtepObservable)))
                  .observeOn(vt.vtScheduler)
                  .filter(makeFunc1(hasVtepChanged))
                  .map(makeFunc1(vtepUpdated))

    /* An observable emitting vtep configuration updates (containing the
       vtep tunnel IP). */
    private val vtepStateObservable =
        Observable.merge(vtepStateSubject)
                  .doOnNext(makeAction1(vtepConfig => {
                      if (vtepConfig != VtepConfiguration.getDefaultInstance) {
                          val vtepId = vtepConfig.getVtepId.asJava
                          val tunnelIp =
                              vtepConfig.getTunnelAddresses(0).asIPv4Address
                          vtepTunnelIps += vtepId -> tunnelIp
                      }
                  }))

    /* An observable emitting network updates. */
    private val networksObservable =
        Observable.merge(networksSubject)
                  .filter(makeFunc1(hasNetworkChanged))
                  .map(makeFunc1(networkUpdated))

    /* An observable emitting port updates. */
    private val portsObservable = Observable.merge(portsSubject)

    /**
     * The mapper's observable. This observable subscribes to all vteps
     * and, for each vtep, retrieves the network the vtep is bound to as well
     * as the corresponding Vxlan port.
     */
    private[topology] def observable: Observable[Map[TunnelIpAndVni, UUID]] =
        Observable.merge(portsObservable,
                         networksObservable,
                         vtepDeletionSubject,
                         vtepStateObservable,
                         vtepsObservable)
                  .filter(makeFunc1(buildMap))
                  .map[Map[TunnelIpAndVni, UUID]](makeFunc1(_ =>
                      vniUUIDMap.readOnlySnapshot.toMap))

    /**
     * Returns the port id the vtep is bound to, given the vtep tunnel IP and
     * the vni.
     */
    private[topology] def uuidOf(tunnelIp: IPv4Addr, vni: Int): Option[UUID] =
        vniUUIDMap get (tunnelIp, vni)
}
