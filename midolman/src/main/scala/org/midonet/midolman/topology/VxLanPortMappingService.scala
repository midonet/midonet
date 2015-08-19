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

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.fromExecutor
import scala.concurrent.duration._
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

import com.google.common.util.concurrent.AbstractService

import rx.{Observable, Observer, Subscriber, Subscription}

import org.midonet.cluster.models.State.VtepConfiguration
import org.midonet.cluster.models.Topology.{Network, Port, Vtep}
import org.midonet.cluster.services.vxgw.data.VtepStateStorage._
import org.midonet.cluster.util.IPAddressUtil._
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.cluster.util.selfHealingTypeObservable
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.midolman.topology.VxLanPortMappingService.{EmptyMappings, MaxStorageRetries, StorageRetryInterval, VtepInfo}
import org.midonet.packets.IPv4Addr
import org.midonet.util.functors.{makeAction0, makeFunc1, makeFunc2}

object VxLanPortMappingService {

    final val MaxStorageRetries = 3
    final val StorageRetryInterval = 1 second
    final val EmptyMappings = Map.empty[TunnelIpAndVni, UUID]

    // Device ID to VxLanPort
    type TunnelIpAndVni = (IPv4Addr, Int)
    case class VtepInfo(vtep: Vtep, config: VtepConfiguration)

    @volatile private var mappings = EmptyMappings

    /** Synchronous query method to retrieve the uuid of an external vxlan port
      * associated to the given VNI key and tunnel IP. The vni key is 24 bits
      * and its highest byte is ignored. */
    def uuidOf(tunnelIp: IPv4Addr, vni: Int): Option[UUID] = {
        mappings get (tunnelIp, vni)
    }

}

/**
 * A service that constructs and maintains a map of (TunnelIP, VNI) -> PortId
 * entries.
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
    private val networks = new mutable.HashMap[UUID, NetworkSubscriber]

    // Pack a VtepConfiguration and a Vtep together
    private val buildVtepInfo = makeFunc2[Vtep, VtepConfiguration, VtepInfo] {
        (vtep, vtepConf) => VtepInfo(vtep, vtepConf)
    }

    // A usable VTEP Config is one that contains a tunnel IP.  Otherwise, we
    // can't map the VTEP's VxLAN ports on networks to any tunnel IP.
    private val isUsableVtepConf = makeFunc1[VtepConfiguration, JBoolean] { c =>
        c != VtepConfiguration.getDefaultInstance
    }

    /*
    /** Given a VTEP, it extracts all network ids that are present in its
      * bindings.  Iterator-based to avoid garbage. */
    private def nwIdsFromBindings(vtep: Vtep): mutable.Set[UUID] = {
        val nwIds = new mutable.HashSet[UUID]
        if (vtep != null) {
            val it = vtep.getBindingsList.iterator()
            while (it.hasNext) {
                nwIds += it.next().getNetworkId.asJava
            }
        }
        nwIds
    }

    /** Ensure that we our local cache contains the VxLAN ports in the networks
      * that are bound to the given VTEP.  If that's not the case, issue
      * requests to storage to retrieve and cache them. */
    private def updateBindings(vtepInfo: VtepInfo, retries: Int = 2): Unit = {

        val vtep = vtepInfo.vtep
        val vtepId = vtepInfo.vtep.getId.asJava
        val oldVtep = knownVteps.put(vtepId, vtepInfo)

        val boundBefore = nwIdsFromBindings(if (oldVtep == null) null
                                            else oldVtep.vtep)
        val boundNow = nwIdsFromBindings(vtep)

        // We only need to reload the networks that are newly bound, or with no
        // more bindings to this VTEP.  All other cases don't need any
        // work, because the VxLAN port for each VTEP never changes, so the
        // one we load on the first binding to a Network is good for all
        // further bindings to the same network.
        val needingRefresh = new mutable.HashSet[UUID]()
        needingRefresh ++= boundBefore -- boundNow
        needingRefresh ++= boundNow -- boundBefore

        // Load the vxlan ports on this network, indexed by vtep
        store.getAll(classOf[Network], needingRefresh.toSeq)
             .flatMap { nws => Future.sequence(nws map loadVxlanPortIndex) }
             .map { updateNwToPortMap }
             .onComplete {
                 case Success(_) =>
                     val tunnelIps = vtepInfo.config.getTunnelAddressesList
                     if (tunnelIps.size <= 0) {
                         log.warn("VTEP {} has no tunnel IPs configured. " +
                                  "Please check that there is one assigned " +
                                  "in the OVSDB, and that the VXLAN Gateway " +
                                  "Cluster service is running", vtep)
                     } else {
                         updateTunnelVniToPortMap(needingRefresh, vtepId,
                                                  extractTunnelIp(vtepInfo))
                     }
                 case Failure(t) if retries <= 0 =>
                     log.warn("Failed to load networks with new bindings to" +
                              "VTEP {}: no retries left", vtepId, t)
                 case Failure(t) =>
                     log.info("Failed to load networks with new bindings to " +
                              "VTEP {}: {} retries left", vtepId,
                              Int.box(retries), t)
                     val retry = retryUpdateBindings(vtepId, vtepInfo,
                                                     retries - 1)
                     vt.vtScheduler.createWorker()
                                   .schedule(retry, 1, TimeUnit.SECONDS)
        }
    }

    /** Returns an action that will retry to update the bindings IFF the
      * VtepInfo that sits in cache by the time of the retry is still the
      * same as the one given as an argument.  Otherwise, it'll do nothing.
      */
    private def retryUpdateBindings(vtepId: UUID, vtepInfo: VtepInfo,
                                    retries: Int) = makeAction0 {
        val currVtepInfo = knownVteps.get(vtepId)
        if (currVtepInfo == vtepInfo) {
            updateBindings(vtepInfo, retries - 1)
        } else {
            log.debug("VTEP bindings update retry aborted as there is a new " +
                      "version of the VTEP")
        }
    }


    /** Given a network, it loads it and all its VxLAN ports from storage all
      * its ports, and returns the index of VTEP ID to its corresponding VxLAN
      * Port. The main VNI-TunnelIP map is not touched yet.
      */
    private def loadVxlanPortIndex(network: Network)
    : Future[(Network, VxLanPortIndex)] = {
        val nwId = network.getId.asJava
        if (network.getVxlanPortIdsCount == 0) {
            log.debug(s"Network $nwId has no more bindings to VTEPs")
            Future.successful((network, EmptyVxLanPortIndex))
        } else {
            knownNetworks.put(nwId, network)
            store.getAll(classOf[Port], network.getVxlanPortIdsList)
                 .map { ports =>
                     log.debug(s"Network $nwId has bindings to " +
                               s"${ports.size} VTEPs")
                     (network, constructVxlanPortIndex(nwId, ports))
                 }
        }
    }
    @inline
    private def extractTunnelIp(vtepInfo: VtepInfo): IPv4Addr = {
        val chosenIp = vtepInfo.config.getTunnelAddresses(0)
        if (vtepInfo.config.getTunnelAddressesCount > 1) {
            val vtepId = vtepInfo.vtep.getId.asJava
            log.warn(s"VTEP $vtepId more than one tunnel IPs configured.  We " +
                     s"will choose $chosenIp, but you're strongly advised to " +
                     "use a single tunnel IP on each VTEP")
        }
        toIPv4Addr(chosenIp)
    }

    /** Assumes that nwToVxPorts has already been updated with each network's
      * set of VxLAN ports.  For each network that is passed, it will update the
      * main VNI-TunnelIp-To-VxLanPort map. */
    private def updateTunnelVniToPortMap(networkIds: mutable.Set[UUID],
                                         vtepId: UUID,
                                         tunIp: IPv4Addr): Unit = {
        val interator = networkIds.iterator
        while(interator.hasNext) {
            val networkId = interator.next()
            val network = knownNetworks get networkId
            if (network eq null) {
                log.warn("")
            }
            val vni = knownNetworks.get(networkId).getVni
            val vxPorts = networkToVxlanPortIndex.get(networkId)
            if (vxPorts == null) {
                log.debug(s"Network $networkId doesn't have VxLAN ports")
                vniToTunIpToPort.remove(vni)
            } else {
                val vxPort = vxPorts.get(vtepId)
                if (vxPort != null) {
                    var tunIpToPort = vniToTunIpToPort.get(vni)
                    if (tunIpToPort == null) {
                        tunIpToPort = new ConcurrentHashMap[IPv4Addr, UUID]
                        vniToTunIpToPort.put(vni, tunIpToPort)
                    }
                    tunIpToPort.put(tunIp, vxPort.getId.asJava)
                }
            }
        }
    }

    /** Taking a map of network ids to their corresponding index of vxlan
      * ports per VTEP, this method updates the internal cache by setting the
      * new VxLanPortIndex, or removing it altogether if no VTEPs are bound
      * to the network.
      */
    private def updateNwToPortMap(idx: Seq[(Network, VxLanPortIndex)]): Unit = {
        val it = idx.iterator
        while (it.hasNext) {
            val nwAndPorts = it.next()
            val network = nwAndPorts._1
            val networkId = network.getId.asJava
            val newIndex = nwAndPorts._2
            val oldIndex = if (newIndex.isEmpty) {
                networkToVxlanPortIndex.remove(networkId)
            } else {
                networkToVxlanPortIndex.put(networkId, newIndex)
            }
            if (oldIndex != null) {
                oldIndex.values().foreach { port =>
                    if (!newIndex.containsValue(port)) {
                        evictMapping(network.getVni, port.getId.asJava)
                    }
                }
            }
        }
    }

    /** Given a VNI, it will find whether there was a mapping from any vtep
      * tunnel ip to it, and remove it.
      */
    private def evictMapping(vni: Int, portId: UUID): Unit = {
        val vniTunToPort = vniToTunIpToPort.get(vni)
        val it = vniTunToPort.entrySet().iterator()
        while(it.hasNext) {
            val entry = it.next()
            if (entry.getValue == portId) {
                it.remove()
            }
        }
    }

    /** Given a network id and a list of ports, builds an index of VTEP ID ->
     * VxLAN port
     */
    private def constructVxlanPortIndex(nwId: UUID, ports: Seq[Port])
    : VxLanPortIndex = {
        val index = new VxLanPortIndex()
        val it = ports.iterator
        while (it.hasNext) {
            val port = it.next()
            if (port.hasVtepId) {
                index.put(port.getVtepId.asJava, port)
            } else {
                log.warn("Port {} is in network {} as VXLAN port, but is not " +
                         "a VXLAN port type", port.getId.asJava, nwId)
            }
        }
        index
    }

    /** Handles changes in a VTEP by looking at changed bindings and
      * triggering the relevant loads from storage to update the internal
      * cache of VNI-TunnelIp to VxLAN Port id.
      */
    private def vtepUpdateHandler(id: UUID) = new Observer[VtepInfo] {
        private val vtepId = id
        override def onCompleted(): Unit = {
            log.debug("VTEP {} deleted", vtepId)
            val removed = knownVteps.remove(vtepId)
            if (removed != null) {
                // This will detect that the networks formerly bound are no
                // longer bound to this VTEP, and clean them up.
                updateBindings(removed)
            }
        }
        override def onError(t: Throwable): Unit = {
            log.warn("VTEP {} error", vtepId, t)
        }
        override def onNext(vtepInfo: VtepInfo): Unit = {
            log.debug("VTEP {} updated", vtepId)
            updateBindings(vtepInfo)
        }
    }*/

    private class VtepSubscriber(vtepId: UUID) extends Subscriber[VtepInfo] {
        private var info: VtepInfo = null

        var tunnelIp: IPv4Addr = null
        var bindings = Set.empty[UUID]

        override def onNext(vtepInfo: VtepInfo): Unit = {
            vt.assertThread()

            info = vtepInfo

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
            val newBindings = vtepInfo.vtep.getBindingsList.asScala
                                           .map(_.getNetworkId.asJava)
                                           .toSet
            val tunnelIpChanged = tunnelIp != newTunnelIp
            val bindingsChanged = bindings != newBindings
            tunnelIp = newTunnelIp
            bindings = newBindings
            log.debug("VTEP {} updated: tunnelIp={} bindings={}", vtepId,
                      tunnelIp, bindings)

            if (bindingsChanged) updateNetworks()
            else if (tunnelIpChanged) updateMappings()
        }

        override def onCompleted(): Unit = {
            vt.assertThread()
            log.debug("VTEP {} deleted", vtepId)

            vteps -= vtepId
            updateNetworks()
        }

        override def onError(t: Throwable): Unit = {
            vt.assertThread()
            log.warn("VTEP {} error: ignoring all bindings for this VTEP",
                     vtepId, t)

            vteps -= vtepId
            updateNetworks()
        }

        def isReady = info ne null

    }

    private class NetworkSubscriber(networkId: UUID) extends Subscriber[Network] {
        var vni: Integer = null
        val portsToVtep = new mutable.HashMap[UUID, VtepSubscriber]

        override def onNext(network: Network): Unit = {
            vt.assertThread()

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
        }

        override def onCompleted(): Unit = {
            vt.assertThread()
            log.debug("Network {} deleted", networkId)

            networks -= networkId
            updateMappings()
        }

        override def onError(t: Throwable): Unit = {
            vt.assertThread()
            log.error("Network {} error", networkId, t)

            networks -= networkId
            updateMappings()
        }

        def isReady = (vni ne null) && portsToVtep.forall(_._2 ne null)

        private def fetchPorts(portIds: Set[UUID],
                               retries: Int = MaxStorageRetries): Unit = {
            store.getAll(classOf[Port], portIds.toSeq) onComplete {
                case Success(ports) =>
                    vt.assertThread()
                    for (port <- ports) {
                        updatePort(port)
                    }
                    updateMappings()

                case Failure(e) if retries > 0 =>
                    log.warn("Failed to load VXLAN ports for network {}: " +
                             "{} retries left in {}", networkId,
                             Int.box(retries), StorageRetryInterval)
                    vt.vtScheduler.createWorker().schedule(makeAction0 {
                        fetchPorts(portIds, retries - 1)
                    }, StorageRetryInterval.toMillis, TimeUnit.MILLISECONDS)

                case Failure(e) =>
                    log.error("Failed to load VXLAN ports for network {} " +
                              s"after $MaxStorageRetries retries")
            }
        }

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
        vtepsSubscription = selfHealingTypeObservable[Vtep](store)
                                .observeOn(vt.vtScheduler)
                                .subscribe(vtepsObserver)
        notifyStarted()
    }

    override protected def doStop(): Unit = {
        vtepsSubscription.unsubscribe()
        for (vtep <- vteps.values) {
            vtep.unsubscribe()
        }
        for (network <- networks.values) {
            network.unsubscribe()
        }
        notifyStopped()
    }

    private def updateNetworks(): Unit = {
        val networkIds = vteps.values.flatMap(_.bindings).toSet
        var networksChanged = false
        for (networkId <- networkIds if !networks.contains(networkId)) {
            val networkSubscriber = new NetworkSubscriber(networkId)
            networks += networkId -> networkSubscriber

            store.observable(classOf[Network], networkId)
                 .observeOn(vt.vtScheduler)
                 .subscribe(networkSubscriber)
            networksChanged = true
        }
        for (networkId <- networks.keys.toSeq if !networkIds.contains(networkId)) {
            networks -= networkId
            networksChanged = true
        }

        if (networksChanged) {
            updateMappings()
        }
    }

    private def updateMappings(): Unit = {
        // Do not update the mappings until all devices are notified.
        for (vtep <- vteps.values if !vtep.isReady) return
        for (network <- networks.values if !network.isReady) return

        val ports = for (network <- networks.values) yield
            network.portsToVtep
                   .map(p => (p._2.tunnelIp, network.vni.intValue()) -> p._1)
                   .toMap
        VxLanPortMappingService.mappings =
            if (ports.nonEmpty) ports.reduce(_ ++ _) else EmptyMappings
    }

}
