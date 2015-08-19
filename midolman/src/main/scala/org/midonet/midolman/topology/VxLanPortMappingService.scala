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
import java.util
import java.util.UUID
import java.util.concurrent.{ConcurrentHashMap, TimeUnit}

import scala.collection.JavaConversions._
import scala.concurrent.ExecutionContext.fromExecutor
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

import com.google.common.util.concurrent.AbstractService
import rx.{Observable, Observer, Subscription}

import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.State.VtepConfiguration
import org.midonet.cluster.models.Topology.{Network, Port, Vtep}
import org.midonet.cluster.services.vxgw.data.VtepStateStorage._
import org.midonet.cluster.util.IPAddressUtil.toIPv4Addr
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.cluster.util.selfHealingTypeObservable
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.midolman.topology.VxLanPortMappingService.{EmptyVxLanPortIndex, VtepInfo, VxLanPortIndex, vniToTunIpToPort}
import org.midonet.packets.IPv4Addr
import org.midonet.util.functors.{makeAction0, makeFunc1, makeFunc2}

object VxLanPortMappingService {

    // Device ID to VxLanPort
    type VxLanPortIndex = util.HashMap[Commons.UUID, Port]
    type TunnelIpAndVni = (IPv4Addr, Int)
    protected val EmptyVxLanPortIndex = new util.HashMap[Commons.UUID, Port]()

    case class VtepInfo(vtep: Vtep, config: VtepConfiguration)

    private val vniToTunIpToPort = new ConcurrentHashMap[Int, util.Map[IPv4Addr, UUID]]()

    /** Synchronous query method to retrieve the uuid of an external vxlan port
      *  associated to the given vni key and tunnel IP. The vni key is 24bits and
      *  its highest byte is ignored. */
    def uuidOf(tunnelIp: IPv4Addr, vni: Int): Option[UUID] = {
        val tunToPort = vniToTunIpToPort.get(vni)
        if (tunToPort != null) {
            Option(tunToPort.get(tunnelIp))
        } else {
            None
        }
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

    override def logSource = s"org.midonet.devices.vxgw-port-mapping"

    private val store = vt.backend.store
    private val stateStore = vt.backend.stateStore
    private implicit val ec: ExecutionContext = fromExecutor(vt.vtExecutor)

    // Subscriber to the internal [[observable]] that will be used to cancel
    // watchers when the service is stopped.
    private var subscription: Subscription = _

    // We have here a map of each network that has bindings to any VTEP, with
    // its vxlan ports indexed by VTEP
    private val nwToVxlanPortIndex = new util.HashMap[Commons.UUID, VxLanPortIndex]

    // Containst the latest known state of each VTEP, populated after it's
    // notified by the storage observable.  It's needed for VTEP deletions,
    // so we know what bindings were affected and can trigger the removals
    // from the VNI-TunnelIp Map.
    private val knownVteps = new util.HashMap[Commons.UUID, VtepInfo]

    // Contains the latest known state of each network bound to VTEPs
    private val knownNetworks = new util.HashMap[Commons.UUID, Network]


    /* Pack a VtepConfiguration and a Vtep together */
    private val buildVtepInfo = makeFunc2[Vtep, VtepConfiguration, VtepInfo] {
        (vtep, vtepConf) => { VtepInfo(vtep, vtepConf) }
    }

    /* A usable VTEP Config is one that contains a tunnel IP.  Otherwise, we
     *  can't map the VTEP's VxLAN ports on networks to any tunnel IP.
     */
    private val isUsableVtepConf = makeFunc1[VtepConfiguration, JBoolean] { c =>
        c != VtepConfiguration.getDefaultInstance
                                                                          }

    /** Given a VTEP, it extracts all network ids that are present in its
      * bindings.  Iterator-based to avoid garbage.
      */
    private def nwIdsFromBindings(vtep: Vtep): util.Set[Commons.UUID] = {
        val nwIds = new util.HashSet[Commons.UUID]
        if (vtep != null) {
            val it = vtep.getBindingsList.iterator()
            while (it.hasNext) {
                nwIds.add(it.next().getNetworkId)
            }
        }
        nwIds
    }

    /** Ensure that we our local cache contains the VxLAN ports in the networks
      * that are bound to the given VTEP.  If that's not the case, issue
      * requests to storage to retrieve and cache them.
      */
    private def updateBindings(vtepInfo: VtepInfo, retries: Int = 2): Unit = {

        val vtep = vtepInfo.vtep
        val vtepId = vtepInfo.vtep.getId
        val oldVtep = knownVteps.put(vtepId, vtepInfo)

        val boundBefore = nwIdsFromBindings(if (oldVtep == null) null
                                            else oldVtep.vtep)
        val boundNow = nwIdsFromBindings(vtep)

        // We only need to reload the networks that are newly bound, or with no
        // more bindings to this VTEP.  All other cases don't need any
        // work, because the VxLAN port for each VTEP never changes, so the
        // one we load on the first binding to a Network is good for all
        // further bindings to the same network.
        val needingRefresh = new util.HashSet[Commons.UUID]()
        needingRefresh.addAll(boundBefore.filterNot(boundNow.contains))
        needingRefresh.addAll(boundNow.filterNot(boundBefore.contains))

        // Load the the vxlan ports on this network, indexed by vtep
        store.getAll(classOf[Network], needingRefresh.toSeq)
             .flatMap { nws => Future.sequence(nws map loadVxlanPortIndex) }
             .map { updateNwToPortMap }
             .onComplete {
                 case Success(_) =>
                     val tunnelIps = vtepInfo.config.getTunnelAddressesList
                     if (tunnelIps.size <= 0) {
                         warnThatVtepHasNoTunnelIps(vtep)
                     } else {
                         updateTunnelVniToPortMap(needingRefresh, vtepId,
                                                  extractTunnelIp(vtepInfo))
                     }
                 case Failure(t) if retries <= 0 =>
                     log.warn("Failed to load networks with new bindings to" +
                              "VTEP ${vtepId.asJava} - no retries left", t)
                 case Failure(t) =>
                     log.info("Failed to load networks with new bindings to " +
                              s"VTEP ${vtepId.asJava}; $retries retries left", t)
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
      * Port.  The main VNI-TunnelIP map is not touched yet.
      */
    private def loadVxlanPortIndex(network: Network)
    : Future[(Network, VxLanPortIndex)] = {
        val nwId = network.getId
        val nwUuid = nwId.asJava
        if (network.getVxlanPortIdsCount == 0) {
            log.debug(s"Network $nwUuid has no more bindings to VTEPs")
            Future.successful((network, EmptyVxLanPortIndex))
        } else {
            knownNetworks.put(nwId, network)
            store.getAll(classOf[Port], network.getVxlanPortIdsList)
                .map { ports =>
                    log.debug(s"Network $nwUuid has bindings to " +
                              s"${ports.size} VTEPs")
                    (network, constructVxlanPortIndex(network.getId, ports))
                }
        }
    }

    @inline
    private def warnThatVtepHasNoTunnelIps(vtep: Vtep): Unit = {
        log.warn(s"VTEP ${fromProto(vtep.getId)} has no tunnel IPs configured" +
                 s".  Please check that there is one assigned in the OVSDB, " +
                 s"and that the VxLAN Gateway Cluster service is running")
    }

    @inline
    private def extractTunnelIp(vtepInfo: VtepInfo): IPv4Addr = {
        val vtep = vtepInfo.vtep
        val chosenIp = vtepInfo.config.getTunnelAddresses(0)
        if (vtepInfo.config.getTunnelAddressesCount > 1) {
            log.warn(s"VTEP ${fromProto(vtep.getId)} more than one tunnel " +
                     s"IPs configured.  We will choose $chosenIp, but you're " +
                     s"strongly advised to use a single tunnel IP on each VTEP")
        }
        toIPv4Addr(chosenIp)
    }

    /** Assumes that nwToVxPorts has already been updated with each network's
      * set of VxLAN ports.  For each network that is passed, it will update the
      * main VNI-TunnelIp-To-VxLanPort map.
      */
    private def updateTunnelVniToPortMap(nwIds: util.Set[Commons.UUID],
                                         vtepId: Commons.UUID,
                                         tunIp: IPv4Addr): Unit = {
        val itNws = nwIds.iterator()
        while(itNws.hasNext) {
            val nwId = itNws.next()
            val vni = knownNetworks.get(nwId).getVni
            val vxPorts = nwToVxlanPortIndex.get(nwId)
            if (vxPorts == null) {
                log.debug(s"Network ${nwId.asJava} doesn't have VxLAN ports")
                vniToTunIpToPort.remove(vni)
            } else {
                val vxPort = vxPorts.get(vtepId)
                if (vxPort != null) {
                    var tunIpToPort = vniToTunIpToPort.get(vni)
                    if (tunIpToPort == null) {
                        tunIpToPort = new util.HashMap[IPv4Addr, UUID]
                        vniToTunIpToPort.put(vni, tunIpToPort)
                    }
                    tunIpToPort.put(tunIp, fromProto(vxPort.getId))
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
            val newIndex = nwAndPorts._2
            val oldIndex = if (newIndex.isEmpty) {
                nwToVxlanPortIndex.remove(network.getId)
            } else {
                nwToVxlanPortIndex.put(network.getId, newIndex)
            }
            val removed = new util.ArrayList[Port]
            if (oldIndex != null) {
                oldIndex.values().foreach { port =>
                    if (!newIndex.containsValue(port)) {
                        removed.add(port)
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
    private def constructVxlanPortIndex(nwId: Commons.UUID, ports: Seq[Port])
    : VxLanPortIndex = {
        val index = new VxLanPortIndex()
        val it = ports.iterator
        while (it.hasNext) {
            val port = it.next()
            if (port.hasVtepId) {
                index.put(port.getVtepId, port)
            } else {
                log.warn(s"Port is in ${port.getId.asJava} in network " +
                         s"${nwId.asJava} as VxLAN port, but has another type")
            }
        }
        index
    }

    /** Handles changes in a VTEP by looking at changed bindings and
      * triggering the relevant loads from storage to update the internal
      * cache of VNI-TunnelIp to VxLAN Port id.
      */
    private def vtepUpdateHandler(id: Commons.UUID) = new Observer[VtepInfo] {
        private val vtepId = id.asJava
        override def onCompleted(): Unit = {
            log.debug(s"VTEP $vtepId is deleted")
            val removed = knownVteps.remove(vtepId)
            if (removed != null) {
                // This will detect that the networks formerly bound are no
                // longer bound to this VTEP, and clean them up.
                updateBindings(removed)
            }
        }
        override def onError(t: Throwable): Unit = {
            log.warn(s"Update stream of VTEP $vtepId emits an error", t)
        }
        override def onNext(vtepInfo: VtepInfo): Unit = {
            log.debug(s"VTEP $vtepId is updated")
            updateBindings(vtepInfo)
        }
    }

    /** This Observer picks up a new Observable[Vtep] which represents the
      * lifetime of a new VTEP in the system that we'll have to watch from
      * now on.
      */
    private val handleNewVtep = new Observer[Observable[Vtep]] {
        override def onCompleted(): Unit = {
            log.warn("Stream of VTEPs closed, this is unexpected")
        }
        override def onError(t: Throwable): Unit = {
            log.warn("Stream of VTEPs emits error", t)
        }
        override def onNext(vtepObservable: Observable[Vtep]): Unit = {
            val vtep = try {
                // This this future should complete immediately, as the
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
            // get an observable with updates on the VTEP State key - This one
            // is written by the Cluster, with the tunnel IPs configured in
            // the VTEP's OVSDB.
            val vtepId = vtep.getId.asJava
            val vtepStateObservable = stateStore.vtepConfigObservable(vtepId)
                .filter(isUsableVtepConf)

            // For each new pair (vtep, vtepConfig), we'll have VxLAN port
            // mappings to update.
            Observable.combineLatest[Vtep, VtepConfiguration, VtepInfo](
                vtepObservable,
                vtepStateObservable,
                buildVtepInfo
            ).observeOn(vt.vtScheduler)
             .subscribe(vtepUpdateHandler(vtep.getId))
        }
    }

    override protected def doStart(): Unit = {
        subscription = selfHealingTypeObservable[Vtep](store)
                          .observeOn(vt.vtScheduler)
                          .subscribe(handleNewVtep)
        notifyStarted()
    }

    override protected def doStop(): Unit = {
        subscription.unsubscribe()
        notifyStopped()
    }
}
