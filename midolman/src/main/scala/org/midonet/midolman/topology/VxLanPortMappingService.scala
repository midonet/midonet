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

import java.lang.{Boolean => JBoolean}
import java.util.UUID
import java.util.concurrent.TimeUnit

import scala.collection.breakOut
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.fromExecutor
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

import com.google.common.util.concurrent.AbstractService

import rx.{Observable, Observer, Subscriber, Subscription}

import org.midonet.cluster.models.State.VtepConfiguration
import org.midonet.cluster.models.Topology.{Network, Port, Vtep}
import org.midonet.cluster.services.vxgw.data.VtepStateStorage._
import org.midonet.cluster.util.IPAddressUtil._
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.midolman.topology.VxLanPortMappingService.{TunnelInfo, TunnelIpAndVni, VtepInfo}
import org.midonet.packets.IPv4Addr
import org.midonet.util.functors.{makeAction0, makeFunc1, makeFunc2}

object VxLanPortMappingService {

    case class TunnelIpAndVni(tunnelIp: IPv4Addr, vni: Int)
    case class TunnelInfo(tunnelIp: IPv4Addr, tunnelZoneId: UUID, vni: Int)
    case class VtepInfo(vtep: Vtep, config: VtepConfiguration)

    @volatile private var toPortMappings = Map.empty[TunnelIpAndVni, UUID]
    @volatile private var toVtepMappings = Map.empty[UUID, TunnelInfo]

    /** Synchronous query method to retrieve the ID of an external VXLAN port
      * associated to the given VNI key and tunnel IP. The VNI key is 24 bits
      * and its highest byte is ignored. */
    def portOf(tunnelIp: IPv4Addr, vni: Int): Option[UUID] = {
        toPortMappings get TunnelIpAndVni(tunnelIp, vni)
    }

    /** Synchronous query method to retrieve the VTEP tunnel information for
      * the specified VXLAN port identifier. */
    def tunnelOf(portId: UUID): Option[TunnelInfo] = {
        toVtepMappings get portId
    }

}

/**
 * A service that constructs and maintains two maps of:
 *   (TunnelIP, VNI) -> VxLanPortId
 *   VxLanPortId -> (TunnelIP, TunnelZoneId, VNI)
 *
 * The service observes vteps present in NSDB. Whenever a VTEP is updated
 * it checks whether its bindings to networks have changed. If it is the case
 * it garbage collects information for unbound networks and retrieves newly
 * bound networks in order to build the map.
 */
class VxLanPortMappingService(vt: VirtualTopology)
    extends AbstractService with MidolmanLogging {

    override def logSource = "org.midonet.devices.vxgw-port-mapping"

    private val store = vt.backend.store
    private val stateStore = vt.backend.stateStore
    private implicit val ec: ExecutionContext = fromExecutor(vt.vtExecutor)

    // Subscriber to the internal [[observable]] that will be used to cancel
    // watchers when the service is stopped.
    private var vtepsSubscription: Subscription = _
    private val vteps = new mutable.HashMap[UUID, VtepSubscriber]
    private val networks = new mutable.HashMap[UUID, NetworkSnapshot]

    // Pack a VtepConfiguration and a Vtep together
    private val buildVtepInfo =
        makeFunc2[Vtep, VtepConfiguration, VtepInfo](VtepInfo.apply)

    // A usable VTEP Config is one that contains a tunnel IP. Otherwise, we
    // can't map the VTEP's VxLAN ports on networks to any tunnel IP.
    private val isUsableVtepConf = makeFunc1[VtepConfiguration, JBoolean] { c =>
        c != VtepConfiguration.getDefaultInstance
    }

    /** Uses the current set of [[VtepSubscriber]] to update the set of
      * [[NetworkSnapshot]]. For each new network that is bound to a VTEP
      * the method creates a new [[NetworkSnapshot]], adds it to the internal
      * map and subscribes it to the store [[Network]] observable. Old networks
      * are removed from the internal map and unsubscribed from storage. If
      * there has been any change to the bound networks, the method calls the
      * `updateMappings` to refresh the VXLAN port mapping. */
    private def updateNetworks(vtepId: UUID, addedNetworks: Set[UUID],
                               removedNetworks: Set[UUID])
    : Unit = {
        log.debug("Networks updated for VTEP {} added: {} removed: {}",
                  vtepId, addedNetworks, removedNetworks)

        val networkIds: Set[UUID] = vteps.values.flatMap(_.networkIds)(breakOut)
        var networksChanged = false

        if ((addedNetworks ne null) && addedNetworks.nonEmpty) {
            for (networkId <- addedNetworks) networks get networkId match {
                case Some(networkSnapshot) => networkSnapshot.refresh()
                case None =>
                    val networkSnapshot = new NetworkSnapshot(networkId)
                    networks += networkId -> networkSnapshot
                    networkSnapshot.refresh()
            }
            networksChanged = true
        }
        for ((networkId, _) <- networks.toSeq if !networkIds.contains(networkId)) {
            networks -= networkId
            networksChanged = true
        }

        if (removedNetworks eq null) {
            networks.values.foreach(_.removeVtep(vtepId))
            networksChanged = true
        } else if (removedNetworks.nonEmpty) {
            for (networkId <- removedNetworks; network = networks.get(networkId)
                 if network.nonEmpty) {
                network.get.removeVtep(vtepId)
            }
            networksChanged = true
        }

        if (networksChanged) {
            updateMappings()
        }
    }

    /** Updates the VXLAN port mapping using the current set of
      * [[VtepSubscriber]] and [[NetworkSnapshot]], if the data for all
      * subscribers was received from storage. The method iterates over all
      * networks' VXLAN ports and creates an immutable map between the port
      * VTEP's tunnel IP and network VNI to the port UUID. */
    private def updateMappings(): Unit = {
        // Do not update the mappings until all devices are notified.
        for (vtep <- vteps.values if !vtep.isReady) return
        for (network <- networks.values if !network.isReady) return

        VxLanPortMappingService.toPortMappings =
            (for (network <- networks.values;
                  (portId, vtep) <- network.portsToVtep)
                yield TunnelIpAndVni(vtep.tunnelIp, network.vni.intValue) -> portId)(breakOut)

        VxLanPortMappingService.toVtepMappings =
            (for (network <- networks.values;
                  (portId, vtep) <- network.portsToVtep)
                yield portId -> TunnelInfo(vtep.tunnelIp, vtep.tunnelZoneId, network.vni))(breakOut)

        log.debug("Mappings updated: toPort={} toVtep={}",
                  VxLanPortMappingService.toPortMappings,
                  VxLanPortMappingService.toVtepMappings)
    }

    /**
     * Implements a [[Subscriber]] for [[VtepInfo]] notifications, processing
     * the VTEP tunnel IP and bindings. If the bindings have changed, the
     * subscriber calls the `updateNetworks` method to refresh the current set
     * of networks that could be bound to the VTEP. Otherwise, if the VTEP
     * tunnel IP has changed, it calls the `updateMappings` method to refresh
     * them according to the new tunnel IP.
     */
    private class VtepSubscriber(val vtepId: UUID) extends Subscriber[VtepInfo] {

        /** The VTEP tunnel IP address, or `null` if not set. */
        var tunnelIp: IPv4Addr = null
        /** The VTEP tunnel zone ID, or `null` if not set. */
        var tunnelZoneId: UUID = null
        /** The networks bound to the VTEP. */
        var networkIds = Set.empty[UUID]

        /** Indicates whether the VTEP subscriber has received the VTEP
          * configuration from storage. */
        @inline def isReady = (tunnelIp ne null) && (tunnelZoneId ne null)

        override def onNext(vtepInfo: VtepInfo): Unit = {
            vt.assertThread()

            val tunnelIps = vtepInfo.config.getTunnelAddressesList
            val newTunnelIp =
                if (tunnelIps.isEmpty) {
                    log.warn("VTEP {} has no tunnel IPs configured: " +
                             "check that there is one assigned " +
                             "in the OVSDB, and that the VXLAN Gateway " +
                             "Cluster service is running", vtepId)
                    null
                } else {
                    if (tunnelIps.size() > 1) {
                        log.warn("VTEP {} has more than one tunnel IPs " +
                                 "configured: the first one will be used, " +
                                 "but you should configure a single tunnel " +
                                 "IP on each VTEP", vtepId)
                    }
                    tunnelIps.get(0).asIPv4Address
                }
            val newTunnelZoneId =
                if (vtepInfo.vtep.hasTunnelZoneId)
                    vtepInfo.vtep.getTunnelZoneId.asJava
                else null
            val newNetworkIds = vtepInfo.vtep.getBindingsList.asScala
                                             .map(_.getNetworkId.asJava)
                                             .toSet

            // Explicitly remove the bindings from this VTEP to the removed set
            // of networks. This is done because the same networks may still be
            // bound to other VTEPs, and the deletion may not be detected until
            // the corresponding VXLAN ports removed.
            val addedNetworks = newNetworkIds -- networkIds
            val removedNetworks = networkIds -- newNetworkIds
            val vtepChanged = tunnelIp != newTunnelIp ||
                              tunnelZoneId != newTunnelZoneId

            tunnelIp = newTunnelIp
            tunnelZoneId = newTunnelZoneId
            networkIds = newNetworkIds

            log.debug("VTEP {} updated: tunnelIp={} tzoneId={} bindings={}",
                      vtepId, tunnelIp, tunnelZoneId, networkIds)

            if (addedNetworks.nonEmpty || removedNetworks.nonEmpty) {
                updateNetworks(vtepId, addedNetworks, removedNetworks)
            }
            else if (vtepChanged) {
                updateMappings()
            }
        }

        override def onCompleted(): Unit = {
            vt.assertThread()
            log.debug("VTEP {} deleted", vtepId)

            vteps -= vtepId
            updateNetworks(vtepId, null, null)
        }

        override def onError(t: Throwable): Unit = {
            vt.assertThread()
            log.warn("VTEP {} error: ignoring all bindings for this VTEP",
                     vtepId, t)

            vteps -= vtepId
            updateNetworks(vtepId, null, null)
        }
    }

    /**
     * Stores a snapshot for [[Network]]s currently bound to any VTEP. Each
     * snapshot stores an internal map of the network's VXLAN ports and the
     * corresponding [[VtepSubscriber]]. The subscriber processes the network
     * notifications by updating the VXLAN ports map, fetching the data for
     * new ports, and mapping them to the corresponding [[VtepSubscriber]].
     */
    private class NetworkSnapshot(val networkId: UUID) {

        var vni: Integer = null
        val portsToVtep = new mutable.HashMap[UUID, VtepSubscriber]

        /** Indicates whether the network subscriber has received the network
          * and VXLAN ports data, and all ports have been mapped to a VTEP
          * subscriber. */
        def isReady = (vni ne null) && portsToVtep.forall(_._2 ne null)

        def refresh(): Unit = {
            vt.assertThread()
            log.debug("Updating network {}", networkId)

            // Set the VNI to null to prevent any updates until the refreshed
            // network is available
            vni = null

            fetchNetwork()
        }

        /** Drops all the ports that are bound to the specified VTEP. This is
          * used when a VTEP deletion is notified before the removal of its
          * networks. */
        def removeVtep(vtepId: UUID): Unit = {
            portsToVtep.retain((_, vtep) => vtep.vtepId != vtepId)
        }

        /** Fetches the data of the current network from storage. If the request
          * is successful, the method computes the changed VXLAN ports and
          * updates them from storage. If the operation fails, the method
          * schedules a delay retry after [[vt.config.zookeeper.retryMs]] for up
          * to [[vt.config.zookeeper.maxRetries]] retries. */
        private def fetchNetwork(retries: Int = vt.config.zookeeper.maxRetries)
        : Unit = {
            store.get(classOf[Network], networkId) onComplete {
                case Success(network) =>
                    val newVni = network.getVni
                    val newPorts = network.getVxlanPortIdsList.asScala.map(_.asJava).toSet
                    val oldPorts = portsToVtep.keySet

                    if (newPorts != oldPorts || newVni != vni) {
                        vni = newVni

                        val addedPorts = newPorts -- oldPorts
                        val removedPorts = oldPorts -- newPorts

                        portsToVtep ++= addedPorts.map(_ -> null)
                        portsToVtep --= removedPorts

                        log.debug("Network {} updated: vni={} vxlanPorts={}",
                                  networkId, vni, newPorts)
                        if (addedPorts.nonEmpty) fetchPorts(addedPorts)
                        else updateMappings()
                    }

                case Failure(e) if retries > 0 =>
                    log.warn("Failed to load network {}: " +
                             "{} retries left in {} milliseconds", networkId,
                             Int.box(retries),
                             Long.box(vt.config.zookeeper.retryMs))
                    vt.vtScheduler.createWorker().schedule(makeAction0 {
                        fetchNetwork(retries - 1)
                    }, vt.config.zookeeper.retryMs, TimeUnit.MILLISECONDS)

                case Failure(e) =>
                    log.error("Failed to load network {} after " +
                              s"${vt.config.zookeeper.maxRetries} retries")
            }
        }

        /** Fetches the data for the given set of VXLAN port identifiers, and
          * upon successful completion of the request it updates the VXLAN
          * ports map and calls the `updateMappings` method to refresh the
          * VXLAN port mapping. If the operation fails, the method schedules
          * a delay retry after [[vt.config.zookeeper.retryMs]] for up to
          * [[vt.config.zookeeper.maxRetries]] retries. */
        private def fetchPorts(portIds: Set[UUID],
                               retries: Int = vt.config.zookeeper.maxRetries)
        : Unit = {
            store.getAll(classOf[Port], portIds.toSeq) onComplete {
                case Success(ports) =>
                    vt.assertThread()
                    ports.foreach(updatePort)
                    updateMappings()

                case Failure(e) if retries > 0 =>
                    log.warn("Failed to load VXLAN ports for network {}: " +
                             "{} retries left in {} milliseconds", networkId,
                             Int.box(retries),
                             Long.box(vt.config.zookeeper.retryMs))
                    vt.vtScheduler.createWorker().schedule(makeAction0 {
                        fetchPorts(portIds, retries - 1)
                    }, vt.config.zookeeper.retryMs, TimeUnit.MILLISECONDS)

                case Failure(e) =>
                    log.error("Failed to load VXLAN ports for network {} " +
                              s"after ${vt.config.zookeeper.maxRetries} retries")
            }
        }

        /** Updates the [[NetworkSnapshot]] internal VXLAN port map to the
          * VTEP subscriber. If the port data is not expected (such that due
          * to a delayed update) or because the [[VtepSubscriber]] does not
          * exists (because the VTEP has been deleted), the port update is
          * ignored. */
        private def updatePort(port: Port): Unit = {
            val portId = port.getId.asJava

            // Late update: the port is no longer part of the network.
            if (!portsToVtep.contains(portId))
                return
            // Check the VTEP identifier is set in the VXLAN port.
            if (port.getVtepId eq null) {
                log.warn("VXLAN port {} is missing VTEP ID: port ignored",
                         portId)
                portsToVtep -= portId
                return
            }

            val vtepId = port.getVtepId.asJava
            vteps get vtepId match {
                case Some(vtep) => portsToVtep(portId) = vtep
                case None =>
                    log.warn("No VTEP {} for VXLAN port {}: port ignored",
                             portId, vtepId)
                    portsToVtep -= portId
            }
        }
    }

    /** This [[Observer]] picks up a new Observable[Vtep] which represents the
      * lifetime of a new VTEP in the system that we'll have to watch from
      * now on. */
    private val vtepsObserver = new Observer[Observable[Vtep]] {
        override def onCompleted(): Unit = {
            log.warn("Stream of VTEPs closed, this is unexpected")
        }
        override def onError(t: Throwable): Unit = {
            log.warn("Stream of VTEPs emits error", t)
        }
        override def onNext(vtepObservable: Observable[Vtep]): Unit = {
            vt.assertThread()

            val vtep = try {
                // This future should complete immediately, as the
                // Observable is already primed with a copy of the VTEP
                vtepObservable.toBlocking.first()
            } catch {
                case NonFatal(t) =>
                    log.warn("Failed to bootstrap new VTEP, this is normal " +
                             "if it was deleted right after creation")
                    // because when doing .first(), the observable is found
                    // completed
                    return
            }

            // Create a new subscriber for this VTEP.
            val vtepId = vtep.getId.asJava
            val vtepSubscriber = new VtepSubscriber(vtepId)
            vteps += vtepId -> vtepSubscriber
            log.debug("New VTEP {}: subscribing to model and state updates",
                      vtepId)

            // get an observable with updates on the VTEP State key - This one
            // is written by the Cluster, with the tunnel IPs configured in
            // the VTEP's OVSDB.  State keys disappear when their parent
            // model is deleted, so a VTEP deletion will complete both
            // observables and the result of combineLatest.
            val vtepStateObservable = stateStore.vtepConfigObservable(vtepId)
                                                .filter(isUsableVtepConf)

            // Subscribe the VTEP subscribes to updates
            Observable.combineLatest[Vtep, VtepConfiguration, VtepInfo](
                    vtepObservable,
                    vtepStateObservable,
                    buildVtepInfo)
                .observeOn(vt.vtScheduler)
                .subscribe(vtepSubscriber)
        }
    }

    override protected def doStart(): Unit = {
        log info "Starting VXLAN port mapping service"
        vtepsSubscription = store.observable(classOf[Vtep])
                                 .observeOn(vt.vtScheduler)
                                 .subscribe(vtepsObserver)
        notifyStarted()
    }

    override protected def doStop(): Unit = {
        log info "Stopping VXLAN port mapping service"
        vtepsSubscription.unsubscribe()
        for (vtep <- vteps.values) {
            vtep.unsubscribe()
        }
        notifyStopped()
    }

}
