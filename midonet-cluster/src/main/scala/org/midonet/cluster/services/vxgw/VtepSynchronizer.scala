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
import java.util.concurrent.{ConcurrentHashMap, Executors}

import scala.collection.JavaConversions._
import scala.concurrent.ExecutionContext.fromExecutor
import scala.concurrent.Future
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

import com.typesafe.scalalogging.Logger

import org.slf4j.LoggerFactory.getLogger

import rx.functions.Action1
import rx.schedulers.Schedulers
import rx.subscriptions.CompositeSubscription
import rx.{Observable, Observer}

import org.midonet.cluster.{DataClient, vxgwVtepControlLog}
import org.midonet.cluster.data.storage.{NotFoundException, StateStorage, Storage}
import org.midonet.cluster.data.vtep.VtepStateException
import org.midonet.cluster.data.vtep.model.MacLocation
import org.midonet.cluster.models.State.VtepConfiguration
import org.midonet.cluster.models.State.VtepConnectionState._
import org.midonet.cluster.models.Topology.{Network, Port, Vtep => NsdbVtep}
import org.midonet.cluster.services.vxgw.FloodingProxyHerald.FloodingProxy
import org.midonet.cluster.services.vxgw.VtepSynchronizer.{NetworkInfo, executor}
import org.midonet.cluster.services.vxgw.data.VtepStateStorage._
import org.midonet.cluster.util.IPAddressUtil
import org.midonet.cluster.util.IPAddressUtil.toIPv4Addr
import org.midonet.cluster.util.UUIDUtil.fromProto
import org.midonet.midolman.state._
import org.midonet.packets.IPv4Addr
import org.midonet.southbound.vtep.ConnectionState._
import org.midonet.southbound.vtep.VtepConstants.bridgeIdToLogicalSwitchName
import org.midonet.southbound.vtep.{ConnectionState, OvsdbVtepDataClient}
import org.midonet.util.concurrent.NamedThreadFactory
import org.midonet.util.functors._
import org.midonet.util.reactivex._

object VtepSynchronizer {
    // We'll do all operations on this thread.
    private val executor = Executors.newSingleThreadExecutor(
        new NamedThreadFactory("vtep-sync", isDaemon = true))
    private val scheduler = Schedulers.from(executor)
    private val ec = fromExecutor(executor)

    /** A NetworkInfo containing the various pieces of information we keep
      * about a MidoNet network that is bound to a given VTEP that holds this
      * info.
      */
    class NetworkInfo {
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
                       store: Storage,
                       stateStore: StateStorage,
                       dataClient: DataClient, // TODO: remove, after
                       // abstracting ReplicatedMaps from DataClient
                       fpHerald: FloodingProxyHerald,
                       ovsdbProvider: (IPv4Addr, Int) => OvsdbVtepDataClient)
    extends Observer[NsdbVtep] {

    private val log = Logger(getLogger(vxgwVtepControlLog(vtepId)))
    private implicit val ec = VtepSynchronizer.ec

    // The latest known VTEP instance, in the NSDB.  This variable is always
    // accessed on the global VtepSynchronizer executor, not on the ZK thread,
    // so updates are safe.
    private var nsdbVtep: NsdbVtep = null

    // Ditto as nsdbVtep for thread safety
    private var macRemoteConsumer: VtepMacRemoteConsumer = null

    // Our client to the VTEP's OVSDB.  Also accessed only within the
    // VtepSynchronizer thread.
    private var ovsdb: OvsdbVtepDataClient = null

    // Thread safe: even though the event that triggers a modification
    // comes from the OVSDB client, we hop to the VtepSynchronizer scheduler
    // to handle the event.
    private var connectionState: State = Disconnected

    // Various subscriptions relevant to our VTEP that are managed jointly
    private val subscription = new CompositeSubscription()

    // Info of all the networks that are bound to the VTEP
    private val boundNetworks = new ConcurrentHashMap[UUID, NetworkInfo]

    // A channel to push MacLocation updates that must be propagated into a
    // MidoNet Bridge.  It's initialized from the start and ready for when
    // the connection to the OVSDB is started.
    private val midoMacRemoteConsumer = new MidoMacRemoteConsumer(boundNetworks)

    // Input channel to push MacLocations to the VTEP.  We initialize with a
    // dummy implementation that will get replaced after OVSDB connects.
    // Thread safe accesses since they all execute on the VtepSynchronizer
    // thread.
    private var ovsdbMacLocationObserver: Observer[MacLocation] =
        new Observer[MacLocation] {
            override def onCompleted(): Unit = {
                log.debug("MAC location stream completed")
            }
            override def onError(e: Throwable): Unit = {
                log.warn("MAC location stream failed", e)
            }
            override def onNext(ml: MacLocation): Unit = {
                log.info("MAC location ignored: OVSDB still not connected: {}",
                         ml)
            }
    }

    // Handler for a Flooding Proxy update.  This will result in a new
    // MacLocation for the unknown destination being emitted to the VTEP.
    private val whenFloodingProxyChanges = makeAction1[FloodingProxy] { _ =>
        boundNetworks.keySet().foreach { feedFloodingProxyTo }
        // TODO: double check here, I think we do need to remove any
        // existing ones first or the client will simply add it
    }

    /** This Observer takes care of listening to connection state changes from
      * the VTEP's OVDSB and publishing them to MidoNet's NSDB.
      */
    private val ovsdbCnxnStateHandler = new Observer[State] {
        override def onCompleted(): Unit = {
            log.info("OVSDB connection status stream closed")
        }
        override def onError(t: Throwable): Unit = {
            log.error("Error on OVSDB connection status stream", t)
        }
        override def onNext(s: State): Unit = {
            connectionState = s
            val zkState = connectionState match {
                case Connected | Ready => VTEP_CONNECTED
                case Disconnected
                     | Disconnecting
                     | Connecting => VTEP_DISCONNECTED
                case _ => VTEP_ERROR
            }
            log.info("OVSDB connection state change: {}", s)
            stateStore.setVtepConnectionState(vtepId, zkState).asFuture

            if (connectionState == Ready) {
                // Make sure that we update the OVSDB after any reconnection
                syncNsdbIntoOvsdb()
            }
        }
    }

    /** Sends the current flooding proxy to the Logical Switch associated to
      * the given network in the OVSDB.
      */
    private def feedFloodingProxyTo(nwId: UUID): Unit = {
        val tzId = fromProto(nsdbVtep.getTunnelZoneId)
        val fpIp = fpHerald.lookup(tzId).map(_.tunnelIp).orNull
        log.debug(s"Flooding proxy lookup for tunnel zone $tzId yields $fpIp")
        val lsName = bridgeIdToLogicalSwitchName(nwId)
        log.debug(s"Flooding proxy $fpIp announced to logical switch $lsName")
        ovsdbMacLocationObserver.onNext(MacLocation.unknownAt(fpIp, lsName))
    }

    /** Error handler for the subscription to the VTEP we're managing.
      * Errors will result in the VtepSynchronizer being terminated.
      *
      * Runs on the event thread from Storage.
      */
    override def onError(t: Throwable): Unit = {
        log.error("VTEP error", t)
        ovsdb.close()
        subscription.unsubscribe()
        subscription.clear()
    }

    /** Completion handler for the subscription to the VTEP we're managing.
      * An event here means that the VTEP was deleted.
      *
      * Runs on the event thread from Storage.
      */
    override def onCompleted(): Unit = {
        log.info("VTEP deleted: cleaning up")
        ovsdb.close()
        subscription.unsubscribe()
        subscription.clear()
        // We're not guaranteeed to have gotten all updates for each binding
        // deletion, so we need to ensure that stuff is gone from the VTEP.
        boundNetworks.keySet foreach ignoreNetwork
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
            log.info("VTEP added: loading data from OVSDB at {}:{}",
                     mgmtIp, Int.box(mgmtPort))
            ovsdb = ovsdbProvider(mgmtIp, mgmtPort)
            connectToOvsdb()
            watchOvsdbConnectionEvents()
        } else if (connectionState == Ready) {
            log.debug("VTEP updated: synchronizing NSDB into OVSDB")
            syncNsdbIntoOvsdb()
        } else {
            log.info("VTEP updated, but OVDSB is disconnected: will " +
                     "synchronize when reconnected")
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
                log.info("Connected to OVSDB, start VTEP synchronization")
                extractVtepTunnelIps(vtepId)
                ovsdbMacLocationObserver = obs
                macRemoteConsumer = new VtepMacRemoteConsumer(nsdbVtep,
                              boundNetworks, store, ovsdbMacLocationObserver)
                // Since this is the first connect, we ensure that all networks
                // will get the FP.
                syncNsdbIntoOvsdb()
                watchVtepLocalMacs()
                watchFloodingProxyEvents()
        }
    }

    private def extractVtepTunnelIps(vtepId: UUID): Unit = {
        ovsdb.physicalSwitch.andThen {
            case Success(Some(ps)) =>
                log.info("VTEP tunnel IPs are: {}", ps.tunnelIpStrings)
                val vtepConfig = VtepConfiguration.newBuilder()
                    .addAllTunnelAddresses(
                        ps.tunnelIps.map(IPAddressUtil.toProto))
                if (ps.name ne null)
                    vtepConfig.setName(ps.name)
                if (ps.description ne null)
                    vtepConfig.setDescription(ps.description)
                stateStore.setVtepConfig(vtepId, vtepConfig.build()).asFuture
            case Failure(_) =>
                log.warn("Failed to extract tunnel IPs")
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
                 .subscribe(ovsdbCnxnStateHandler)
        )
    }

    /** Synchronizes configuration from NSDB into the VTEP.  This will
      * examine the new version of the VTEP and bootstrap the newly bound
      * networks, as well as release the networks no longer bound.
      */
    private def syncNsdbIntoOvsdb(): Unit = {

        log.debug("Synchronizing with VTEP")

        // Choose the set of network ids that have bindings
        val nwIds = nsdbVtep.getBindingsList
                            .map { b => fromProto(b.getNetworkId) }
                            .toSet

        val newlyBoundNetworks = nwIds -- boundNetworks.keySet()
        newlyBoundNetworks.foreach { watchNetwork(_) }

        // Take those that are no longer bound and update MidoNet.  Also,
        // propagate new bindings to the VTEP when relevant.
        boundNetworks.keySet().foreach { nwId =>
            if (nwIds.contains(nwId)) {
                log.debug(s"Network $nwId updated its bindings")
                val lsName = bridgeIdToLogicalSwitchName(nwId)
                ovsdb.logicalSwitch(lsName).onComplete {
                    case Success(Some(ls)) =>
                        ensureBindings(ls.uuid, nwId, nsdbVtep.getBindingsList)
                    case Success(None) =>
                        log.warn("Failed to sync new bindings to VTEP: the " +
                                 s"Logical_Switch with name $lsName was not " +
                                 "found.  Recreate the binding to retry.")
                    case Failure(f) =>
                        log.warn("Failed to sync new bindings to VTEP: " +
                                 "failure accessing OVDSB to load Logical " +
                                 s"Switch $lsName.  Recreate binding to " +
                                 s"retry", f)
                }
            } else {
                log.debug(s"Network $nwId no longer in a VxGW")
                ignoreNetwork(nwId)
            }
        }
    }

    private def ignoreNetwork(id: UUID): Unit = {
        log.info(s"Network $id is no longer a VXLAN gateway: unbinding")
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
        ovsdb.logicalSwitch(lsName) foreach {
            case Some(ls) => ovsdb.deleteLogicalSwitch(ls.uuid).recover {
                case t: Throwable =>
                    whenVtepReady { removeLogicalSwitchWithRetry(id) }
            }
            case None => // fine then
        }
    }

    /** Invoke the given action when reconnected */
    private def whenVtepReady(doIt: => Unit): Unit = {
        ovsdb.observable
             .filter{makeFunc1 { _ == Ready }}
             .take(1)
             .observeOn(VtepSynchronizer.scheduler)
             .subscribe(makeAction1[ConnectionState.State] { _ => doIt })
    }

    /** A new network is bound to this VTEP.  Start all subscriptions to the
      * network's virtual state tables, and ensure that the OVSDB contains
      * the right configuration in order to implement the bindings in NSDB.
      */
    private def watchNetwork(nwId: UUID, retries: Int = 10): Unit = {
        var nwInfo = new NetworkInfo
        nwInfo = boundNetworks.getOrElseUpdate(nwId, { nwInfo })
        loadVxlanPortFor(nwId, nwInfo)
            .flatMap { _ => ensureLogicalSwitch(nwId) }
            .flatMap { lsId =>
                ensureBindings(lsId, nwId, nsdbVtep.getBindingsList)
            }
            .map { _ => watchMacPortMap(nwId, macRemoteConsumer) }
            .map { _ => watchArpTable(nwId, macRemoteConsumer) }
            .map { _ => feedFloodingProxyTo(nwId) }
            .onComplete {
                case Success(_) =>
                    log.debug("Network {} is now watched", nwId)
                case Failure(t) if retries == 0 =>
                    log.warn("Failed to watch network {}: no more retries left",
                             nwId, t)
                case Failure(e: IllegalStateException) =>
                    log.info("Network {} is not bound to the VTEP anymore", nwId)
                case Failure(e: NotFoundException) =>
                    log.info("Network {} was removed", nwId)
                case Failure(e: VtepStateException) =>
                    log.info("OVSDB not reachable, waiting until connected, " +
                             "{} retries left", Int.box(retries))
                    whenVtepReady { watchNetwork(nwId, retries - 1) }
                case Failure(t) =>
                    log.warn("Network {} failed to bootstrap: {} retries left",
                             nwId, Int.box(retries), t)
                    executor submit makeRunnable(watchNetwork(nwId, retries -1))
             }
    }

    /** The [[NetworkInfo]] requires having the VxLAN port that links to the
      * VTEP managed in this synchronizer.  This method finds this port, and
      * updates the network info with it.  Note that this is a one-off in the
      * lifetime of the VxGW with this network, as the VxLAN port never changes.
      */
    private def loadVxlanPortFor(nwId: UUID, nwInfo: NetworkInfo)
    : Future[Unit] = {
        if (nwInfo.vxPort != null) {
            return Future.successful[Unit](())
        }
        store.get(classOf[Network], nwId).flatMap { nw =>
            val pIds = nw.getVxlanPortIdsList.map { fromProto }
            store.getAll(classOf[Port], pIds)
        }.map { ports =>
            ports.find(p => fromProto(p.getVtepId) == vtepId) match {
                case Some(p) =>
                    nwInfo.vxPort = fromProto(p.getId)
                    log.debug("Network {} port for VTEP is: {}", nwId,
                              nwInfo.vxPort)
                case None =>
                    throw new IllegalStateException(
                        s"Cannot find a VXLAN port for VTEP on network $nwId")
            }
        }
    }

    private def watchArpTable(nwId: UUID, vtepUpdater: VtepMacRemoteConsumer)
    : Unit = {
        val nwInfo = boundNetworks.get(nwId)
        if (nwInfo == null) {
            return
        }
        if (nwInfo.arpTable != null) {
            log.debug("ARP table for network {} is already being watched", nwId)
            return
        }
        val arpTable: Ip4ToMacReplicatedMap = try {
            dataClient.getIp4MacMap(nwId)
        } catch {
            case e: StateAccessException =>
                log.warn("Error loading ARP table of network {}: retrying", nwId)
                executor.submit( makeRunnable {
                    watchArpTable(nwId, vtepUpdater)
                })
                return
            case NonFatal(e) =>
                log.error("Non recoverable failure loading ARP table of " +
                          "network: {}", nwId, e)
                return
        }

        boundNetworks.get(nwId).arpTable = arpTable

        watchMap(nwId, arpTable, vtepUpdater.buildArpUpdateHandler(nwId))
        log.debug("ARP table of network {} is now watched", nwId)
    }

    private def watchMacPortMap(nwId: UUID,
                                vtepUpdater: VtepMacRemoteConsumer): Unit = {
        val nwInfo = boundNetworks.get(nwId)
        if (nwInfo == null) {
            return
        }
        if (nwInfo.macTable != null) {
            log.debug("MAC table for network {} is already being watched", nwId)
            return
        }
        val macTable: MacPortMap = try {
            dataClient.bridgeGetMacTable(nwId, 0, false)
        } catch {
            case e: StateAccessException =>
                log.warn("Error loading MAC table of network {}: retrying", nwId)
                executor.submit( makeRunnable {
                    watchMacPortMap(nwId, vtepUpdater)
                })
                return
            case NonFatal(e) =>
                log.error("Non recoverable failure while loading the MAC " +
                          "table for network: {}", nwId, e)
                return
        }

        boundNetworks.get(nwId).macTable = macTable

        watchMap(nwId, macTable, vtepUpdater.buildMacPortHandler(nwId))
        log.debug("MAC-port table of network {} is now watched", nwId)
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
            case info => info.subscriptions.add (
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
    private def ensureLogicalSwitch(nwId: UUID): Future[UUID] = {
        if (!boundNetworks.containsKey(nwId)) {
            val msg =
                s"Cannot verify the logical switch for unbound network $nwId"
            log.debug(msg)
            return Future.failed(new IllegalStateException(msg))
        }
        store.get(classOf[Network], nwId).flatMap { network =>
            if (network.getVni < 10000) {
                throw new IllegalStateException(
                    s"Network $nwId has unsupported VNI less than 10000 " +
                    "(this is a problem): binding will not complete")
            } else {
                val vni = network.getVni
                log.debug("Fetch logical switch for network: {} VNI: {}",
                          nwId, Int.box(vni))
                ovsdb.createLogicalSwitch(bridgeIdToLogicalSwitchName(nwId),
                                          network.getVni)
            }
        }
    }

    /** Ensures that the VTEP contains these bindings on the given Logical
      * Switch.
      */
    private def ensureBindings(lsId: UUID,
                               nwId: UUID,
                               bindings: util.List[NsdbVtep.Binding]) = {
        log.debug("Ensuring that VTEP bindings exist for network: {}", nwId)
        if (!boundNetworks.containsKey(nwId)) {
            val msg = s"Cannot consolidate bindings for unbound network: $nwId"
            log.debug(msg)
            Future.failed(new IllegalArgumentException(msg))
        } else {
            val portVlanPairs =
                bindings.filter(bdg => fromProto(bdg.getNetworkId) == nwId)
                        .map(bdg => (bdg.getPortName, bdg.getVlanId.toShort))
            log.debug("New bindings from network {}: {}", nwId, portVlanPairs)
            ovsdb.setBindings(lsId, portVlanPairs)
        }
    }
}
