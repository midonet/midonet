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

import java.util
import java.util.UUID
import java.util.concurrent.{ConcurrentHashMap, Executors, ThreadFactory}

import scala.collection.JavaConversions._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

import org.slf4j.LoggerFactory.getLogger
import rx.functions.Action1
import rx.schedulers.Schedulers
import rx.subscriptions.CompositeSubscription
import rx.{Observable, Observer}

import org.midonet.cluster.DataClient
import org.midonet.cluster.data.storage.NotFoundException
import org.midonet.cluster.data.vtep.model.MacLocation
import org.midonet.cluster.data.vtep.{VtepConnection, VtepDataClient}
import org.midonet.cluster.models.Topology.{Network, Vtep => NsdbVtep}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.vxgw.FloodingProxyHerald.FloodingProxy
import org.midonet.cluster.southbound.vtep.VtepConstants.bridgeIdToLogicalSwitchName
import org.midonet.cluster.util.IPAddressUtil.toIPv4Addr
import org.midonet.cluster.util.UUIDUtil.fromProto
import org.midonet.cluster.util.selfHealingEntityObservable
import org.midonet.midolman.state._
import org.midonet.packets.IPv4Addr
import org.midonet.util.functors._

class NetworkState {
    var macTable: MacPortMap = _
    var arpTable: Ip4ToMacReplicatedMap = _
    // The port id on the network that corresponds to this VTEP
    var vxPort: UUID = _
    val subscriptions = new CompositeSubscription
}

class VtepSynchronizer(nodeId: UUID,
                       vtepId: UUID,
                       fpHerald: FloodingProxyHerald,
                       backend: MidonetBackend,
                       dataClient: DataClient,
                       ovsdbProvider: (IPv4Addr, Int) => VtepDataClient) {

    // We'll do all operations on this thread.
    private val syncExecutor = Executors.newSingleThreadExecutor(
        new ThreadFactory {
            override def newThread(r: Runnable): Thread = {
                val t = new Thread(r)
                t.setName(s"vtep-sync-$vtepId")
                t
            }
        })

    private val syncScheduler = Schedulers.from(syncExecutor)
    private implicit val ec = ExecutionContext.fromExecutor(syncExecutor)

    @volatile private var vtepCnxnState = VtepConnection.State.DISCONNECTED
    @volatile private var resyncNeeded = true

    private val log = getLogger(s"org.midonet.cluster.vtep-$vtepId")

    // The latest known VTEP instance, in the NSDB
    private var nsdbVtep: NsdbVtep = null

    // Our OVSDB
    private var ovsdb: VtepDataClient = null

    // Various subscriptions that are global to this VTEP.
    private val subscription = new CompositeSubscription()

    // State tables of all the networks that we care about
    private val nwStates = new ConcurrentHashMap[UUID, NetworkState]

    // Handle a Flooding Proxy update.  This will result in a new MacLocation
    // for the unknown destination being emitted to the VTEP.
    private val handleFloodingProxyUpdate = makeAction1[FloodingProxy] { fp =>
        nwStates.keys.foreach { nwId =>
            // TODO: double check here, I think we do need to remove any
            // existing ones first or the client will simply add it
            ovsdb.macRemoteUpdater.onNext (
                MacLocation.unknownAt(fp.tunnelIp,
                                      bridgeIdToLogicalSwitchName(nwId))
            )
        }
    }

    def start(): Unit = {

        // Subscribe to the VTEP in NSDB
        subscription.add (
            selfHealingEntityObservable[NsdbVtep](backend.store, vtepId)
                .observeOn(syncScheduler)
                .subscribe (new Observer[NsdbVtep] {
                    override def onCompleted(): Unit = onNsdbCompletion()
                    override def onError(t: Throwable): Unit = onNsdbFailure(t)
                    override def onNext(t: NsdbVtep): Unit = onNsdbUpdated(t)
                })
        )
        // Watch the flooding proxy changes, and if on our tunnel zone, apply it
        subscription.add(
            fpHerald.observable
                    .filter(makeFunc1 { fp: FloodingProxy =>
                        fp.tunnelZoneId == fromProto(nsdbVtep.getTunnelZoneId)
                    })
            .observeOn(syncScheduler)
            .subscribe(handleFloodingProxyUpdate)
        )
    }

    def onNsdbFailure(t: Throwable): Unit = {
        log.error("Error on VTEP subscription to NSDB")
        ovsdb.disconnect(nodeId)
    }

    def onNsdbCompletion(): Unit = {
        log.error("The VTEP was deleted from MidoNet, cleaning up")
        // We're not guaranteeed to have gotten all updates for each binding
        // deletion, so we need to ensure that stuff is gone from the VTEP.
        this.ignoreNetworks(nwStates.keysIterator)
    }

    /** Handle changes in the VTEP model stored in NSDB.  These will be
      * exclusively when the set of VxLAN ports are changed.
      */
    def onNsdbUpdated(vtep: NsdbVtep): Unit = {

        val oldState = nsdbVtep

        nsdbVtep = vtep

        if (oldState == null) {
            log.info("First loaded VTEP data from OVSDB")
            val mgmtIp = toIPv4Addr(vtep.getManagementIp)
            val mgmtPort = vtep.getManagementPort
            log.info(s"Connect to VTEP at $mgmtIp:$mgmtPort")
            ovsdb = ovsdbProvider(mgmtIp, mgmtPort)
            ovsdb.connect(nodeId)
            subscription.add(
                ovsdb.macLocalUpdates
                     .observeOn(syncScheduler)
                     .subscribe(new MidoNetUpdater(nwStates))
            )
            log.debug("Waiting for connection.. ")
            ovsdb.awaitState(Set(VtepConnection.State.CONNECTED))
            // TODO: review the logic here. What happens if the connection is
            // lost and recovered?  I'm assuming that there will be a bunch
            // of updates queued in the Executor's queue, so when the
            // connection is restored they'll automatically be processed.
            //
            // However, we're not there yet: first of all, the current retry
            // is going to keep insisting on the VTEP despite it being
            // disconnected.  We need to implement a backoff so the queue
            // gets blocked until the VTEP can actually apply the updates.
        }
        sync()
    }

    /** Synchronizes configuration in the NSDB into the VTEP.
      */
    def sync(): Unit = {

        // Chose the set of network ids that have bindings
        val nwIds = nsdbVtep.getBindingsList
                            .foldLeft(Set.empty[UUID]){ (ids, binding) =>
                                ids + fromProto(binding.getNetworkId)
                            }

        // Take those that are newly bound
        val newNwIds = new util.ArrayList[UUID]()
        nwIds foreach { nwId =>
            if(!nwStates.contains(nwId)) {
                newNwIds add nwId
            }
        }

        // Take those that are no longer bound
        val unboundNwIds = nwStates.keySet().filterNot(nwIds.contains).toList

        newNwIds foreach watchNetwork

        // Update the midonet side
        ignoreNetworks(unboundNwIds.iterator)
    }

    def ignoreNetworks(ids: util.Iterator[UUID]): Unit = ids foreach { id =>
        val nwState = nwStates.remove(id)
        nwState.subscriptions.unsubscribe()
        if (nwState.arpTable != null) nwState.arpTable.stop()
        if (nwState.macTable != null) nwState.macTable.stop()
        val lsName = bridgeIdToLogicalSwitchName(id)
        try {
            ovsdb.removeLogicalSwitch(lsName)
        } catch {
            case NonFatal(t) => log.warn("Failed to remove logical switch " +
                                         s"$lsName from OVSDB - It will be " +
                                         "cleaned the next time that the " +
                                         "service is restarted.", t)
        }
    }

    private def watchNetwork(nwId: UUID): Unit = {
        nwStates.getOrElseUpdate(nwId, { new NetworkState })

        val vtepUpdater = new VtepUpdater(nsdbVtep, nwStates, fpHerald,
                                          backend.store, ovsdb)
        ensureLogicalSwitch(nwId)   // fetches from NSDB and write to OVSDB
            .map { _ => ensureBindings(nwId, nsdbVtep.getBindingsList) }
            .map { _ => watchMacPortMap(nwId, vtepUpdater) }
            .map { _ => watchArpTable(nwId, vtepUpdater) } match {
                case e: NotFoundException =>
                    log.info(s"Network $nwId was removed")
                case NonFatal(t) =>
                    log.warn(s"Network $nwId failed to bootstrap, retrying")
                    syncExecutor submit makeRunnable(watchNetwork(nwId))
            }
    }

    private def watchArpTable(nwId: UUID, vtepUpdater: VtepUpdater): Unit = {
        if (!nwStates.containsKey(nwId)) {
            return
        }
        val arpTable: Ip4ToMacReplicatedMap = try {
            dataClient.getIp4MacMap(nwId)
        } catch {
            case e: StateAccessException =>
                log.warn(s"Error loading ARP table of network $nwId, retring")
                syncExecutor.submit( makeRunnable {
                    watchMacPortMap(nwId, vtepUpdater)
                })
                return
            case _ =>
                log.error(s"Non recoverable failure loading ARP table of " +
                          s"network " + nwId)
                return
        }

        nwStates.get(nwId).arpTable = arpTable

        watchMap(nwId, arpTable, vtepUpdater.arpUpdateHandler(nwId))
    }

    private def watchMacPortMap(nwId: UUID,
                                vtepUpdater: VtepUpdater): Unit = {
        if (!nwStates.contains(nwId)) {
            return
        }
        val macTable: MacPortMap = try {
            dataClient.bridgeGetMacTable(nwId, 0, false)
        } catch {
            case e: StateAccessException =>
                log.warn(s"Error loading MAC table of network $nwId, retring")
                syncExecutor.submit( makeRunnable {
                    watchMacPortMap(nwId, vtepUpdater)
                })
                return
            case _ =>
                log.error(s"Non recoverable failure loading MAC table of " +
                          s"network " + nwId)
                return
        }

        nwStates.get(nwId).macTable = macTable

        watchMap(nwId, macTable, vtepUpdater.macPortHandler(nwId))
    }

    private def watchMap[K, V](nwId: UUID, map: ReplicatedMap[K, V],
                               handlr: Action1[MapNotification[K, V]]): Unit = {
        val nwState = nwStates.get(nwId)
        if (nwState == null) {
            return
        }

        nwState.subscriptions.add (
            Observable.create(new MapObservableOnSubscribe(map))
                .observeOn(Schedulers.immediate())
                .doOnUnsubscribe { makeAction0(map.stop()) }
                .subscribe(handlr)
        )
    }

    /** Ensure that the logical switches fo the given networks exist in this
      * VTEP.
      */
    def ensureLogicalSwitch(nwId: UUID): Future[Any] = {
        if (!nwStates.contains(nwId)) {
            return Future.failed(new IllegalStateException())
        }

        backend.store.get(classOf[Network], nwId).map { network =>
            ovsdb.ensureLogicalSwitch(bridgeIdToLogicalSwitchName(nwId),
                                      network.getVni).get
        }
    }

    /** Ensures that the VTEP contains these bindings on each relevant
      * Logical Switch.  For all networks present in the given list, bindings
      * that exist on the VTEP but not in the list will be wiped off.
      *
      * Logical Switches associated to networks that are not in the list will
      * not be affected, so they should be cleaned up elsewhere.
      */
    def ensureBindings(nwId: UUID,
                       bindings: util.List[NsdbVtep.Binding]): Unit = {
        if (!nwStates.containsKey(nwId)) {
            return
        }
        bindings.groupBy(binding => binding.getNetworkId)
                .foreach{ nwAndBindings =>
                    val nwId = fromProto(nwAndBindings._1)
                    ovsdb.ensureBindings(
                        bridgeIdToLogicalSwitchName(nwId),
                        nwAndBindings._2.map { proto =>
                            (proto.getPortName, proto.getVlanId.toShort)
                        }
                    )
                }
    }

}