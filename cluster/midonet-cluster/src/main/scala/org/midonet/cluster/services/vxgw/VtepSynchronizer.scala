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
import scala.concurrent.ExecutionContext.fromExecutor
import scala.concurrent.Future
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

import org.slf4j.LoggerFactory.getLogger
import rx.functions.Action1
import rx.schedulers.Schedulers
import rx.subscriptions.CompositeSubscription
import rx.{Observable, Observer}

import org.midonet.cluster.DataClient
import org.midonet.cluster.data.storage.{NotFoundException, StateStorage, Storage}
import org.midonet.cluster.data.vtep.VtepConnection.ConnectionState
import org.midonet.cluster.data.vtep.VtepConnection.ConnectionState._
import org.midonet.cluster.data.vtep.model.{LogicalSwitch, MacLocation}
import org.midonet.cluster.data.vtep.{VtepDataClient, VtepStateException}
import org.midonet.cluster.models.State.VtepConnectionState._
import org.midonet.cluster.models.Topology.{Network, Vtep => NsdbVtep}
import org.midonet.cluster.services.vxgw.FloodingProxyHerald.FloodingProxy
import org.midonet.cluster.services.vxgw.VtepSynchronizer.{NetworkCard, executor}
import org.midonet.cluster.services.vxgw.data.VtepStateStorage._
import org.midonet.cluster.util.IPAddressUtil.toIPv4Addr
import org.midonet.cluster.util.UUIDUtil.fromProto
import org.midonet.midolman.state._
import org.midonet.packets.IPv4Addr
import org.midonet.southbound.vtep.VtepConstants.bridgeIdToLogicalSwitchName
import org.midonet.util.functors._
import org.midonet.util.reactivex._

object VtepSynchronizer {
    // We'll do all operations on this thread.
    private val executor = Executors.newSingleThreadExecutor(new ThreadFactory {
        override def newThread(r: Runnable): Thread = {
            val t = new Thread(r)
            t.setName("vtep-sync")
            t
        }
    })
    private val scheduler = Schedulers.from(executor)
    private val ec = fromExecutor(executor)

    /** A card containing the various pieces of information we keep about a
      * MidoNet network that is bound to a given VTEP that holds this card.
      */
    class NetworkCard {
        // MAC-Port and ARP tables of the network.
        var macTable: MacPortMap = _
        var arpTable: Ip4ToMacReplicatedMap = _
        // port used by the network for all traffic from/to the VTEP.
        var vxPort: UUID = _
        // subscriptions involved in syncing with the network, based on its
        // bindings to the VTEP.
        val subscriptions = new CompositeSubscription
    }
}

/** Models the controller that takes care of a single VTEP.  In MidoNet, each
  * VTEP is managed by a single cluster node at any given moment.  Control
  * functions involve two main contexts: first syncing state (MAC-port
  * and IP-MAC mappings.)  Second, syncing configuration such as the VTEP
  * bindings (that are determined by the bindings configured in the NSDB by
  * the API).
  */
class VtepSynchronizer(vtepId: UUID,
                       nodeId: UUID,
                       store: Storage,
                       stateStore: StateStorage,
                       dataClient: DataClient, // TODO: remove, after
                       // abstracting ReplicatedMaps from DataClient
                       fpHerald: FloodingProxyHerald,
                       ovsdbProvider: (IPv4Addr, Int) => VtepDataClient)
    extends Observer[NsdbVtep] {

    private val log = getLogger(s"org.midonet.cluster.vxgw-vtep-$vtepId")
    private implicit val ec = VtepSynchronizer.ec

    // The latest known VTEP instance, in the NSDB
    private var nsdbVtep: NsdbVtep = null
    private var vtepUpdater: VtepMacRemoteConsumer = null

    // Our client to the VTEP's OVSDB
    private var ovsdb: VtepDataClient = null
    @volatile private var connectionState: State = Disconnected

    // Various subscriptions relevant to our VTEP that are managed jointly
    private val subscription = new CompositeSubscription()

    // Cards of all the networks that are bound to the VTEP
    private val boundNetworks = new ConcurrentHashMap[UUID, NetworkCard]

    // A channel to push MacLocation updates that must be propagated into a
    // MidoNet Bridge.  It's initialized from the start and ready for when
    // the connection to the OVSDB is started.
    private val midoMacRemoteConsumer = new MidoMacRemoteConsumer(boundNetworks)

    // Input channel to push MacLocations to the VTEP.  We initialize with a
    // dummy implementation that will get replaced after OVSDB connects.
    private var vtepMacRemoteConsumer: Observer[MacLocation] =
        new Observer[MacLocation] {
            override def onCompleted(): Unit = {}
            override def onError(e: Throwable): Unit = {}
            override def onNext(ml: MacLocation): Unit = {
                log.info(s"MacLocation ignored: OVSDB still not connected $ml")
            }
    }

    // Handler for a Flooding Proxy update.  This will result in a new
    // MacLocation for the unknown destination being emitted to the VTEP.
    private val whenFloodingProxyChanges = makeAction1[FloodingProxy] { fp =>
        boundNetworks.keys.foreach { feedFloodingProxyTo }
        // TODO: double check here, I think we do need to remove any
        // existing ones first or the client will simply add it
    }

    /** This Observer takes care of listening to connection state changes from
      * the VTEP's OVDSB and publishing them to MidoNet's NSDB.
      */
    private val whenOvsdbCnxnStatusChanges = new Observer[State] {
            override def onCompleted(): Unit = {}
            override def onError(e: Throwable): Unit = {}
            override def onNext(s: State): Unit = {
                connectionState = s
                val stToZk = connectionState match {
                    case Connected => VTEP_CONNECTED
                    case Disconnected | Broken => VTEP_DISCONNECTED
                    case _ => VTEP_ERROR
                }
                log.info(s"OVSDB connection state change: $s")
                stateStore.setVtepConnectionState(vtepId, stToZk).asFuture

                if (connectionState != Connected) {
                    return
                }

                syncNsdbIntoOvsdb()
            }
    }

    /** Sends the current flooding proxy to the Logical Switch associated to
      * the given network in the OVSDB.
      */
    private def feedFloodingProxyTo(nwId: UUID): Unit = {
        val tzId = fromProto(nsdbVtep.getTunnelZoneId)
        val fpIp = fpHerald.lookup(tzId).map(_.tunnelIp).orNull
        val lsName = bridgeIdToLogicalSwitchName(nwId)
        log.debug(s"FloodingProxy $fpIp announced to LogicalSwitch $lsName")
        vtepMacRemoteConsumer.onNext(MacLocation.unknownAt(fpIp, lsName))
    }

    /** Error handler for the subscription to the VTEP we're managing.
      * Errors will result in the VtepSynchronizer being terminated.
      */
    override def onError(t: Throwable): Unit = {
        log.error("Error on VTEP subscription to NSDB", t)
        ovsdb.close()
        subscription.unsubscribe()
        subscription.clear()
    }

    /** Completion handler for the subscription to the VTEP we're managing.
      * An event here means that the VTEP was deleted.
      */
    override def onCompleted(): Unit = {
        log.info("The VTEP was deleted from MidoNet, cleaning up..")
        ovsdb.close()
        subscription.unsubscribe()
        subscription.clear()
        // We're not guaranteeed to have gotten all updates for each binding
        // deletion, so we need to ensure that stuff is gone from the VTEP.
        ignoreNetworks(boundNetworks.keySet)
    }

    /** Handler for updates in the VTEP configuration in NSDB.  This will
      * trigger when we first are hooked to a VTEP observable, and on any
      * subsequent updates of the bindings of the vtep.
      *
      * The event is immediately offloaded from the emitter thread and queued
      * in the main VtepSynchronizer executor.
      */
    override def onNext(vtep: NsdbVtep): Unit = executor submit makeRunnable {
        val oldState = nsdbVtep
        nsdbVtep = vtep
        if (oldState == null) {
            val mgmtIp = toIPv4Addr(vtep.getManagementIp)
            val mgmtPort = vtep.getManagementPort
            log.info(s"First loaded VTEP data from OVSDB $mgmtIp:$mgmtPort")
            ovsdb = ovsdbProvider(mgmtIp, mgmtPort)
            connectToOvsdb()
            watchOvsdbConnectionEvents()
        }
        if (connectionState == Connected) {
            syncNsdbIntoOvsdb()
        }
    }

    /** This method will connect to the VTEP's OVSDB and wire the
      * notifications channels so that a MidoNet updater reacts to the
      * changes in the MacLocation tables, and a Vtep updater reacts to
      * changes in MidoNet's ARP and Mac tables of those Networks bound to
      * this VTEP.
      */
    private def connectToOvsdb(): Unit = {
        ovsdb.connect().flatMap {
            case _ => ovsdb.macRemoteUpdater
        } onComplete {
            case Failure(NonFatal(t)) =>
                log.error("Failed to connect to OVDSB", t)
            case Success(obs) =>
                log.info("Connected to OVSDB, start sync processes..")
                vtepMacRemoteConsumer = obs
                vtepUpdater = new VtepMacRemoteConsumer(nsdbVtep, boundNetworks,
                                                        store,
                                                        vtepMacRemoteConsumer)
                watchVtepLocalMacs()
                watchFloodingProxyEvents()
        }
    }

    private def watchVtepLocalMacs(): Unit = {
        subscription.add(
            ovsdb.macLocalUpdates
                .observeOn(VtepSynchronizer.scheduler)
                .subscribe(midoMacRemoteConsumer)
        )
    }

    private def watchFloodingProxyEvents(): Unit = {
        subscription.add(
            fpHerald.observable
                    .filter(makeFunc1 { fp: FloodingProxy =>
                        fp.tunnelZoneId == fromProto(nsdbVtep.getTunnelZoneId)
                    })
                    .observeOn(VtepSynchronizer.scheduler)
                    .subscribe(whenFloodingProxyChanges)
        )

    }

    private def watchOvsdbConnectionEvents(): Unit = {
        subscription.add(
            ovsdb.observable
                 .observeOn(VtepSynchronizer.scheduler)
                 .subscribe(whenOvsdbCnxnStatusChanges)
        )
    }

    /** Synchronizes configuration from NSDB into the VTEP. */
    private def syncNsdbIntoOvsdb(): Unit = {

        log.debug("Syncing with VTEP")

        // Chose the set of network ids that have bindings
        val nwIds = nsdbVtep.getBindingsList
                            .foldLeft(Set.empty[UUID]){ (ids, binding) =>
                                ids + fromProto(binding.getNetworkId)
                            }

        // Take those that are newly bound
        val newNwIds = new util.ArrayList[UUID]()
        nwIds foreach { nwId =>
            if (!boundNetworks.containsKey(nwId)) {
                newNwIds add nwId
                watchNetwork(nwId)
                whenVtepConnected { feedFloodingProxyTo(nwId) }
            }
        }

        // Take those that are no longer bound
        val unboundNwIds = boundNetworks.keySet().filterNot(nwIds.contains).toList

        // Update the midonet side
        log.debug(s"Networks no longer bound: $unboundNwIds")
        ignoreNetworks(unboundNwIds)
    }

    private def ignoreNetworks(ids: Iterable[UUID]): Unit = ids foreach { id =>
        val nwState = boundNetworks.remove(id)
        nwState.subscriptions.unsubscribe()
        if (nwState.arpTable != null) nwState.arpTable.stop()
        if (nwState.macTable != null) nwState.macTable.stop()
        removeLogicalSwitchWithRetry(id)
    }

    /** Remove the logical switch, and retry if there is a failure */
    private def removeLogicalSwitchWithRetry(id: UUID): Unit = {
        if (boundNetworks.contains(id)) {
            // in case it was rebound inbetween a retry
            return
        }
        val lsName = bridgeIdToLogicalSwitchName(id)
        ovsdb.removeLogicalSwitch(lsName).recover {
          case t: Throwable =>
              whenVtepConnected { removeLogicalSwitchWithRetry(id) }
        }
    }

    /** Invoke the given action when reconnected */
    private def whenVtepConnected(doIt: => Unit): Unit = {
        ovsdb.observable
             .filter{makeFunc1 { _ == Connected }}
             .take(1)
             .observeOn(VtepSynchronizer.scheduler)
             .subscribe(makeAction1[ConnectionState.State] { _ => doIt })
    }

    /** A new network is bound to this VTEP.  Start all subscriptions to the
      * network's virtual state tables, and ensure that the OVSDB contains
      * the right configuration in order to implement the bindings in NSDB.
      */
    private def watchNetwork(nwId: UUID, retries: Int = 10): Unit = {
        boundNetworks.getOrElseUpdate(nwId, { new NetworkCard })
        ensureLogicalSwitch(nwId)
            .flatMap { _ => ensureBindings(nwId, nsdbVtep.getBindingsList) }
            .map { _ => watchMacPortMap(nwId, vtepUpdater) }
            .map { _ => watchArpTable(nwId, vtepUpdater) } onComplete {
                case Success(_) =>
                    log.debug(s"Network $nwId is now watched")
                case Failure(t) if retries == 0 =>
                    log.warn(s"Failed to watch network $nwId, no more " +
                             s"retries left", t)
                case Failure(e: IllegalArgumentException) =>
                    log.info(s"Network $nwId is not bound to the VTEP anymore")
                case Failure(e: NotFoundException) =>
                    log.info(s"Network $nwId was removed")
                case Failure(e: VtepStateException) =>
                    log.info("OVSDB not reachable, waiting until connected, " +
                             s"$retries retries left")
                    whenVtepConnected { watchNetwork(nwId, retries - 1) }
                case Failure(t) =>
                    log.warn(s"Network $nwId failed to bootstrap, $retries " +
                             s"left", t)
                    executor submit makeRunnable(watchNetwork(nwId, retries -1))
            }
    }

    private def watchArpTable(nwId: UUID, vtepUpdater: VtepMacRemoteConsumer): Unit = {
        if (!boundNetworks.containsKey(nwId)) {
            return
        }
        val arpTable: Ip4ToMacReplicatedMap = try {
            dataClient.getIp4MacMap(nwId)
        } catch {
            case e: StateAccessException =>
                log.warn(s"Error loading ARP table of network $nwId, retrying")
                executor.submit( makeRunnable {
                    watchMacPortMap(nwId, vtepUpdater)
                })
                return
            case NonFatal(_) =>
                log.error(s"Non recoverable failure loading ARP table of " +
                          s"network " + nwId)
                return
        }

        boundNetworks.get(nwId).arpTable = arpTable

        watchMap(nwId, arpTable, vtepUpdater.buildArpUpdateHandler(nwId))
    }

    private def watchMacPortMap(nwId: UUID,
                                vtepUpdater: VtepMacRemoteConsumer): Unit = {
        if (!boundNetworks.contains(nwId)) {
            return
        }
        val macTable: MacPortMap = try {
            dataClient.bridgeGetMacTable(nwId, 0, false)
        } catch {
            case e: StateAccessException =>
                log.warn(s"Error loading MAC table of network $nwId, retring")
                executor.submit( makeRunnable {
                    watchMacPortMap(nwId, vtepUpdater)
                })
                return
            case NonFatal(_) =>
                log.error(s"Non recoverable failure loading MAC table of " +
                          s"network " + nwId)
                return
        }

        boundNetworks.get(nwId).macTable = macTable

        watchMap(nwId, macTable, vtepUpdater.buildMacPortHandler(nwId))
    }

    /** Watch the given replicated map, do something with each notification.
      *
      * Only as long as the given nwId is among the networks bound for this
      * Vtep.
      */
    private def watchMap[K, V](nwId: UUID, map: ReplicatedMap[K, V],
                               handlr: Action1[MapNotification[K, V]]): Unit = {
        boundNetworks.get(nwId) match {
            case null =>
            case card => card.subscriptions.add (
                Observable.create(new MapObservableOnSubscribe(map))
                    .observeOn(VtepSynchronizer.scheduler)
                    .doOnUnsubscribe { makeAction0 { map.stop() } }
                    .subscribe(handlr)
            )
        }
    }

    /** Ensure that the LogicalSwitch that corresponds to the given MidoNet
      * network exists in this VTEP.
      *
      * TODO: should this function live in the VtepMacRemoteConsumer, which
      * should probably be called VtepSyncBroker or something similar.
      */
    private def ensureLogicalSwitch(nwId: UUID): Future[LogicalSwitch] = {
        if (!boundNetworks.containsKey(nwId)) {
            val msg = s"Can't verify LogicalSwitch for unbound network $nwId"
            log.debug(msg)
            return Future.failed(new IllegalStateException(msg))
        }
        store.get(classOf[Network], nwId).flatMap { network =>
            ovsdb.ensureLogicalSwitch(bridgeIdToLogicalSwitchName(nwId),
                                      network.getVni)
        }
    }

    /** Ensures that the VTEP contains these bindings on each relevant
      * Logical Switch.  For all networks present in the given list, bindings
      * that exist on the VTEP but not in the list will be wiped off.
      *
      * Logical Switches associated to networks that are not in the list will
      * not be affected, so they should be cleaned up elsewhere.
      *
      * TODO: should this function live in the VtepMacRemoteConsumer, which
      * should probably be called VtepSyncBroker or something similar.
      */
    private def ensureBindings(nwId: UUID,
                               bindings: util.List[NsdbVtep.Binding]) = {
        if (!boundNetworks.containsKey(nwId)) {
            val msg = s"Can't ensure bindings for unbound network $nwId"
            log.debug(msg)
            Future.failed(new IllegalArgumentException(msg))
        } else {
            Future.sequence(
                bindings.groupBy(_.getNetworkId).map { nwAndBdgs =>
                    ovsdb.ensureBindings (
                        bridgeIdToLogicalSwitchName(fromProto(nwAndBdgs._1)),
                        nwAndBdgs._2.map { proto =>
                            (proto.getPortName, proto.getVlanId.toShort)
                        }
                    )
                }
            )
        }
    }
}