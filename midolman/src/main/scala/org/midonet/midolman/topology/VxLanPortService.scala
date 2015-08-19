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

import scala.collection.JavaConverters._
import scala.collection.mutable

import com.google.common.util.concurrent.AbstractService
import rx.subjects.PublishSubject
import rx.{Observable, Subscription}

import org.midonet.cluster.models.State.VtepConfiguration
import org.midonet.cluster.models.Topology.{Network, Port, Vtep}
import org.midonet.cluster.services.vxgw.data.VtepStateStorage._
import org.midonet.cluster.util.IPAddressUtil._
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.midolman.topology.DeviceMapper.DeviceState
import org.midonet.packets.IPv4Addr
import org.midonet.util.functors._

object VxLanPortService {

    type TunnelIpAndVni = (IPv4Addr, Int)
    type NetworkIdPort = (UUID, Port)

    case class PortState(id: UUID, var vtepId: UUID)

    @volatile
    private var vniUUIDMap: Map[TunnelIpAndVni, UUID] = _

    /** Synchronous query method to retrieve the uuid of an external vxlan port
     *  associated to the given vni key and tunnel IP. The vni key is 24bits and
     *  its highest byte is ignored. */
    def uuidOf(tunnelIp: IPv4Addr, vni: Int): Option[UUID] = {
        vniUUIDMap get (tunnelIp, vni & (1 << 24) - 1)
    }
}

/**
 * A service that constructs and maintains a map of (TunnelIP, VNI) -> PortId
 * entries.
 *
 * The service observes vteps present in NSDB. Whenever a vtep is updated
 * it checks wether its bindings to networks have changed. If it is the case
 * it garbage collects information for unbound networks and retrieves newly
 * bound networks. To build the (tunnelIP, vni) -> portId map, we also
 * retrieve the vxlan ports bound to the corresponding vteps.
 */
private[topology] class VxLanPortService(vt: VirtualTopology)
    extends AbstractService with MidolmanLogging {

    import VxLanPortService._

    override def logSource = s"org.midonet.devices.vxlanport"

    private val store = vt.backend.store
    private val stateStore = vt.backend.stateStore

    /* Subject for vtep configuration observables. Configuration data of a vtep
       contains the tunnel IP of a vtep. */
    private val vtepStateSubject =
        PublishSubject.create[Observable[VtepConfiguration]]()
    private val networksSubject = PublishSubject.create[Observable[Network]]()
    private val portsSubject = PublishSubject.create[Observable[NetworkIdPort]]()

    private val vteps = new mutable.HashMap[UUID, Vtep]()
    /* For each vtep, a set of networks bound to this vtep. */
    private val vtepBindings = new mutable.HashMap[UUID, Set[UUID]]()
    private val networkVniMap = new mutable.HashMap[UUID, Integer]()
    /* The list of vxlan ports for each network. */
    private val networkPortMap =
        new mutable.HashMap[UUID, mutable.ListBuffer[PortState]]()
    private val vtepTunnelIpMap = new mutable.HashMap[UUID, IPv4Addr]()

    /* Subscriber to the internal [[observable]] */
    private var subscription: Subscription = _

    /**
     * Returns true iff:
     * -the vtep is received for the 1st time
     * -the vtep is bound to a new network
     * -the vtep is not bound to a network anymore
     */
    private def hasVtepChanged(newVtep: Vtep): Boolean = {
        val vtepId = newVtep.getId.asJava
        vteps.get(vtepId) match {
            case Some(prevVtep) =>
                if (newVtep.getBindingsCount == 0) {
                    prevVtep.getBindingsCount > 0
                } else {
                    val prevBoundNetworks = prevVtep.getBindingsList.asScala
                        .map(_.getNetworkId.asJava)
                    val newBoundNetworks = newVtep.getBindingsList.asScala
                        .map(_.getNetworkId.asJava)
                    prevBoundNetworks != newBoundNetworks
                }
            case None => {
                vtepStateSubject onNext
                    stateStore.vtepConfigObservable(vtepId)
                true
            }
        }
    }

    /**
     * Method called when we receive a notification for a vtep.
     */
    private def vtepUpdated(vtep: Vtep): Vtep = {
        val vtepId = vtep.getId.asJava
        vteps(vtepId) = vtep

        val prevBoundNetworks = vtepBindings.getOrElseUpdate(vtepId,
                                                             Set.empty[UUID])
        val newBoundNetworks = vtep.getBindingsList.asScala
            .map(_.getNetworkId.asJava).toSet
        vtepBindings(vtepId) = newBoundNetworks

        log.debug("VTEP: {} updated, bound networks: {}", vtepId,
                  newBoundNetworks)

        /* Remove networks not bound to this vtep anymore. */
        for (netId <- prevBoundNetworks if !newBoundNetworks.contains(netId)) {
            val index =
                networkPortMap(netId).indexWhere(ps => ps.vtepId == vtepId)
            networkPortMap(netId).remove(index)
            /* The network is not bound to any other vteps, discard the network. */
            if (networkPortMap(netId).isEmpty) {
                networkPortMap -= netId
                networkVniMap -= netId
            }
        }

        /* Get newly bound networks using an observable. We may already have
           received this network but since this is a newly bound network for this
           vtep, it means vxlan ports were added to the network. */
        for (netId <- newBoundNetworks if !prevBoundNetworks.contains(netId)) {
            if (networkPortMap.get(netId).isEmpty) {
                networkPortMap(netId) = new mutable.ListBuffer[PortState]()
            }
            networkVniMap(netId) = null
            val networkState =new DeviceState[Network](netId,
                store.observable(classOf[Network], netId).take(1))
            networksSubject onNext networkState.observable
        }

        vtep
    }

    /**
     * Returns true iff we already received the given vxlan port, attached
     * to the given network.
     */
    private def portReceived(networkId: UUID, portId: UUID): Boolean =
        networkPortMap(networkId).exists(portState => portState.id == portId)

    /**
     *  Method called when we receive a notification for a network.
     */
    private def networkReceived(network: Network): Network = {
        val networkId = network.getId.asJava
        val vni = network.getVni
        val portIds = network.getVxlanPortIdsList.asScala.map(_.asJava).toSet

        log.debug("Network: {} updated, vni: {} bound vxlan ports: {}",
                  networkId, new Integer(vni), portIds)

        networkVniMap(networkId) = vni

        /* Obtain not yet received ports using an observable. */
        for (portId <- portIds if (!portReceived(networkId, portId))) {
            networkPortMap(networkId) += PortState(portId, null)
            val observable = store.observable(classOf[Port], portId)
                .observeOn(vt.vtScheduler)
                .take(1)
                .map[(UUID, Port)](makeFunc1(port => (networkId, port)))

            portsSubject onNext observable
        }
        network
    }

    private def tunnelIpsReady: Boolean =
        vteps.keySet.forall(vtepTunnelIpMap.contains)
    private def networksReady: Boolean =
        networkVniMap.values.forall(_ ne null)
    private def portsReady: Boolean = networkPortMap.values.forall(ports => {
        ports.forall(_.vtepId ne null)
    })

    /**
     * Builds a new map and returns true iff:
     * -All networks and vxlan ports bound to a vtep have been received
     * -All vtep tunnel ips have been received
     */
    private def buildMap(update: Any): Any = {
        if (tunnelIpsReady && networksReady && portsReady) {
            val newMap = new mutable.HashMap[TunnelIpAndVni, UUID]()
            for ((netId, portStates) <- networkPortMap) {
                for (portState <- portStates) {
                    val vni = networkVniMap(netId).toInt
                    val tunnelIp = vtepTunnelIpMap(portState.vtepId)
                    newMap += (tunnelIp, vni) -> portState.id
                }
            }

            if (newMap != vniUUIDMap) {
                log.debug("Built new map: {}", newMap.seq)
                vniUUIDMap = newMap.toMap
            }
        }
        update
    }

    /* An observable emitting vtep updates. */
    private val vtepsObservable =
        Observable.merge(store.observable(classOf[Vtep]))
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
                          vtepTunnelIpMap += vtepId -> tunnelIp
                      }
                  }))

    /* An observable emitting network updates. */
    private val networksObservable =
        Observable.merge(networksSubject)
                  .map(makeFunc1(networkReceived))

    /* An observable emitting port updates. */
    private val portsObservable =
        Observable.merge(portsSubject).map(makeFunc1(networkIdPort => {
            val networkId = networkIdPort._1
            val vtepId = networkIdPort._2.getVtepId.asJava
            val portId = networkIdPort._2.getId.asJava
            val portState = networkPortMap(networkId).find(_.id == portId)
            portState.get.vtepId = vtepId
            networkIdPort
        }))

    /* The service's internal observable. This observable subscribes to all vteps
       and, for each vtep, retrieves the network the vtep is bound to as well
       as the corresponding Vxlan port. */
    private val observable = Observable.merge(portsObservable,
                                              networksObservable,
                                              vtepStateObservable,
                                              vtepsObservable)
                                       .map(makeFunc1(buildMap))

    override protected def doStart(): Unit = {
        subscription = observable.subscribe()
        notifyStarted()
    }
    override protected def doStop(): Unit = {
        subscription.unsubscribe()
        notifyStopped()
    }
}
