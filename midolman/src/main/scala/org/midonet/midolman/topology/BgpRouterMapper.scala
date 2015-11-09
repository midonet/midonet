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

import scala.collection.mutable
import scala.collection.JavaConverters._

import com.google.common.annotations.VisibleForTesting
import com.typesafe.scalalogging.Logger

import rx.subscriptions.Subscriptions
import rx.{Subscriber, Subscription, Observable}
import rx.Observable.OnSubscribe
import rx.subjects.{BehaviorSubject, PublishSubject}

import org.midonet.cluster.models.Topology.{Router, BgpPeer, BgpNetwork}
import org.midonet.cluster.util.{IPAddressUtil, IPSubnetUtil}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.midolman.topology.BgpRouterMapper.{PeerState, NetworkState}
import org.midonet.midolman.topology.DeviceMapper.MapperState
import org.midonet.midolman.topology.devices.{BgpNeighbor, BgpRouter}
import org.midonet.packets.IPv4Addr
import org.midonet.quagga.BgpdConfiguration.{Neighbor, Network}
import org.midonet.util.functors._

object BgpRouterMapper {

    /**
     * Stores the state for a BGP network, and exposes an [[Observable]] that
     * emits updates when the network changes. The observable completes either
     * when the network is deleted, or when calling the `complete()` method,
     * which is used to signal that the network is no longer used by the router.
     *
     * In addition, the observable filters all errors. When an error occurs, it
     * emits a last update, notifying that the state has changed to an error.
     */
    private final class NetworkState(val networkId: UUID, vt: VirtualTopology,
                                     log: Logger) {

        private var currentNetwork: Network = null
        private var currentError: Throwable = null
        private var completed = false
        private val mark = PublishSubject.create[Network]

        // The output observable for this BGP network state. It emits
        // notifications with distinct BGP networks for the given network
        // identifier, while filtering all errors.
        val observable: Observable[NetworkState] = vt.store
            .observable(classOf[BgpNetwork], networkId)
            .distinctUntilChanged()
            .observeOn(vt.vtScheduler)
            .map[NetworkState](makeFunc1(networkUpdated))
            .doOnError(makeAction1(currentError = _))
            .doOnCompleted(makeAction0(() => completed = true))
            .onErrorResumeNext(Observable.just(this))
            .takeUntil(mark)

        /** Gets the current network. */
        def network = currentNetwork
        /** Gets the current error. */
        def error = currentError

        /** Completes the observable corresponding to this network state. */
        def complete(): Unit = {
            completed = true
            mark.onCompleted()
        }
        /** Indicates whether the network state has received the network data,
          * or has terminated the observable with an error. */
        def isReady: Boolean = {
            (currentNetwork ne null) || (currentError ne null) || completed
        }
        /** Indicates whether the network state has terminated the observable
          * with a completed notification. */
        def isCompleted: Boolean = completed
        /** Indicates whether the network state has terminated the observable
          * with an error notification. */
        def isError: Boolean = currentError ne null

        /** Maps the [[org.midonet.cluster.models.Topology.BgpNetwork]] message
          * to the corresponding [[Network]] instance. */
        private def networkUpdated(network: BgpNetwork): NetworkState = {
            if (!network.hasSubnet) {
                log.warn("BGP network {} missing subnet: network will be " +
                         "ignored until re-added to the BGP router", networkId)
                throw new DeviceMapperException(s"BGP network $networkId " +
                                                "missing subnet")
            }

            currentNetwork = Network(IPSubnetUtil.fromV4Proto(network.getSubnet))
            this
        }
    }

    /**
     * Stores the state for a BGP peer, and exposes an [[Observable]] that emits
     * updates when the peer changes. The observable completes either when the
     * peer is deleted, or when calling the `complete()` method, which is used
     * to signal that the network is no longer used by the router.
     *
     * In addition, the observable filters all errors. When an error occurs, it
     * emits a `null` peer, notifying that the state has changed to an error.
     */
    private final class PeerState(val peerId: UUID, vt: VirtualTopology,
                                  log: Logger) {

        private var currentPeer: BgpNeighbor = null
        private var currentError: Throwable = null
        private var completed = false
        private val mark = PublishSubject.create[BgpNeighbor]

        // The output observable for this BGP peer state. It emits notifications
        // with distinct BGP peers for the given peer identifier, while
        // filtering all errors.
        val observable = vt.store
            .observable(classOf[BgpPeer], peerId)
            .distinctUntilChanged()
            .observeOn(vt.vtScheduler)
            .map[PeerState](makeFunc1(peerUpdated))
            .doOnError(makeAction1(currentError = _))
            .doOnCompleted(makeAction0(() => completed = true))
            .onErrorResumeNext(Observable.just(this))
            .takeUntil(mark)

        /** Gets the current peer. */
        def peer = currentPeer
        /** Gets the current error. */
        def error = currentError

        /** Completes the observable corresponding to this peer state. */
        def complete(): Unit = mark.onCompleted()
        /** Indicates whether the network state has received the peer data, or
          * has terminated the observable with an error. */
        def isReady: Boolean = {
            (currentPeer ne null) || (currentError ne null) || completed
        }
        /** Indicates whether the peer state has terminated the observable with
          * a completed notification. */
        def isCompleted: Boolean = completed
        /** Indicates whether the peer state has terminated the observable with
          * an error notification. */
        def isError: Boolean = currentError ne null

        /** Maps the [[org.midonet.cluster.models.Topology.BgpPeer]] message to
          * the corresponding [[BgpNeighbor]] instance. */
        private def peerUpdated(peer: BgpPeer): PeerState = {
            if (!peer.hasAsNumber) {
                log.warn("BGP peer {} missing AS number: peer will be " +
                         "ignored until re-added to the BGP router", peerId)
                throw new DeviceMapperException(s"BGP peer $peerId missing " +
                                                "AS number")
            }
            if (!peer.hasAddress) {
                log.warn("BGP peer {} missing IP address: peer will be " +
                         "ignored until re-added to the BGP router", peerId)
                throw new DeviceMapperException(s"BGP peer $peerId missing " +
                                                "IP address")
            }

            val keepAlive = if (peer.hasKeepAlive) peer.getKeepAlive
                            else vt.config.bgpKeepAlive
            val holdTime = if (peer.hasHoldTime) peer.getHoldTime
                           else vt.config.bgpHoldTime
            val connectRetry = if (peer.hasConnectRetry) peer.getConnectRetry
                               else vt.config.bgpConnectRetry
            currentPeer =
                BgpNeighbor(peer.getId.asJava,
                    Neighbor(IPAddressUtil.toIPv4Addr(peer.getAddress),
                             peer.getAsNumber,
                             Some(keepAlive),
                             Some(holdTime),
                             Some(connectRetry),
                             Option(peer.getPassword)))
            this
        }
    }
}

/**
 * An implementation of the [[OnSubscribe]] interface for an [[rx.Observable]]
 * that emits [[BgpRouter]] updates.
 */
final class BgpRouterMapper(routerId: UUID,
                            vt: VirtualTopology = VirtualTopology.self)
    extends OnSubscribe[BgpRouter] with MidolmanLogging {

    override def logSource = s"org.midonet.routing.bgp.bgp-router-$routerId"

    private val state = new AtomicReference(MapperState.Unsubscribed)

    private lazy val unsubscribeAction = makeAction0(onUnsubscribe())

    @volatile private var router: Router = null
    @volatile private var error: Throwable = null
    @volatile private var publishedBgp: BgpRouter = null

    private val networks = new mutable.HashMap[UUID, NetworkState]
    private val peers = new mutable.HashMap[UUID, PeerState]

    private val peersById = new mutable.HashMap[UUID, BgpNeighbor]
    private val peersByAddress = new mutable.HashMap[IPv4Addr, BgpNeighbor]

    private lazy val routerObservable = vt.store
        .observable(classOf[Router], routerId)
        .distinctUntilChanged()
        .observeOn(vt.vtScheduler)
        .flatMap[BgpRouter](makeFunc1(routerUpdated))
        .doOnCompleted(makeAction0(routerDeleted()))
        .doOnError(makeAction1(routerError))
        .onErrorResumeNext(Observable.empty)

    private val networksSubject = PublishSubject.create[Observable[NetworkState]]
    private lazy val networksObservable = Observable
        .merge(networksSubject)
        .doOnNext(makeAction1(networkUpdated))
        .filter(makeFunc1(isBgpReady))
        .map[BgpRouter](makeFunc1(_ => buildBgp()))

    private val peersSubject = PublishSubject.create[Observable[PeerState]]
    private lazy val peersObservable = Observable
        .merge(peersSubject)
        .doOnNext(makeAction1(peerUpdated))
        .filter(makeFunc1(isBgpReady))
        .map[BgpRouter](makeFunc1(_ => buildBgp()))

    private lazy val bgpObservable: Observable[BgpRouter] =
        Observable.merge(networksObservable,
                         peersObservable,
                         routerObservable)

    private val bgpSubject = BehaviorSubject.create[BgpRouter]
    @volatile private var bgpSubscription: Subscription = null

    /** Processes new subscriptions to an observable created for this mapper. */
    override def call(child: Subscriber[_ >: BgpRouter]): Unit = {
        // If the mapper is in any terminal state, complete the child
        // immediately and return.
        if (state.get == MapperState.Completed) {
            child.onCompleted()
            return
        }
        if (state.get == MapperState.Error) {
            child onError error
            return
        }
        if (state.get == MapperState.Closed) {
            child onError DeviceMapper.MapperClosedException
            return
        }
        // Otherwise, schedule the subscription on the VT thread.
        vt.executeVt {
            if (state.compareAndSet(MapperState.Unsubscribed,
                                    MapperState.Subscribed)) {
                bgpSubscription = bgpObservable subscribe bgpSubject
            }

            bgpSubject subscribe child
            child add Subscriptions.create(unsubscribeAction)
        }
    }

    /** Returns the current mapper state. */
    @VisibleForTesting
    protected[topology] def mapperState = state.get

    /** Returns whether the mapper has any subscribed observers. */
    @VisibleForTesting
    protected[topology] def hasObservers = bgpSubject.hasObservers

    /** Processes updates for the current router, by updating the set of
      * corresponding BGP networks and peers. */
    private def routerUpdated(r: Router): Observable[BgpRouter] = {
        vt.assertThread()

        val bgpNetworkIds = r.getBgpNetworkIdsList.asScala.map(_.asJava).toSet
        val bgpPeerIds = r.getBgpPeerIdsList.asScala.map(_.asJava).toSet

        log.debug("Router updated {} networks {} peers {}", r.getId.asJava,
                  bgpNetworkIds, bgpPeerIds)

        val addedNetworks = new mutable.MutableList[NetworkState]
        val addedPeers = new mutable.MutableList[PeerState]
        var update = false

        // Create observables for the new BGP networks corresponding to this
        // router.
        for (networkId <- bgpNetworkIds if !networks.contains(networkId)) {
            val networkState = new NetworkState(networkId, vt, log)
            networks += networkId -> networkState
            addedNetworks += networkState
        }

        // Complete the observables for the BGP networks that are no longer part
        // of this router's BGP configuration.
        for ((networkId, networkState) <- networks.toList
             if !bgpNetworkIds.contains(networkId)) {
            networks -= networkId
            networkState.complete()
            update = true
        }

        // Create observables for the new BGP peers corresponding to this
        // router.
        for (peerId <- bgpPeerIds if !peers.contains(peerId)) {
            val peerState = new PeerState(peerId, vt, log)
            peers += peerId -> peerState
            addedPeers += peerState
        }

        // Complete the observables for the BGP peers that are no longer part of
        // the router's BGP configuration.
        for ((peerId, peerState) <- peers.toList
             if !bgpPeerIds.contains(peerId)) {
            peers -= peerId
            peerState.complete()
            removePeer(peerId)
            update = true
        }

        // Publish observables for the added networks and peers.
        for (networkState <- addedNetworks) {
            networksSubject onNext networkState.observable
        }
        for (peerState <- addedPeers) {
            peersSubject onNext peerState.observable
        }

        val previousRouter = router
        router = r

        // Also emit a BGP notification if this is the first router or if the AS
        // number hash changed.
        update  = update || (previousRouter eq null) ||
                  previousRouter.getAsNumber != router.getAsNumber

        if (update && isBgpReady(null)) {
            Observable.just(buildBgp())
        } else {
            Observable.empty()
        }
    }

    /** This method is called when the current router router us deleted. It
      * triggers the completion of all observables, by completing the
      * corresponding subjects and states. */
    private def routerDeleted(): Unit = {
        // Ignore notifications if the mapper is in a terminal state.
        if (state.get.isTerminal) return

        log.debug("Router deleted")
        state.set(MapperState.Completed)

        bgpSubject.onCompleted()
        networksSubject.onCompleted()
        peersSubject.onCompleted()
        for ((_, networkState) <- networks) {
            networkState.complete()
        }
        for ((_, peerState) <- peers) {
            peerState.complete()
        }

        bgpSubscription = null
    }

    /** This method is called when the current router emits an error. */
    private def routerError(e: Throwable): Unit = {
        // Ignore notifications if the mapper is in a terminal state.
        if (state.get.isTerminal) return

        log.debug("Router error", e)
        error = e
        state.set(MapperState.Error)

        bgpSubject onError e
        networksSubject.onCompleted()
        peersSubject.onCompleted()
        for ((_, networkState) <- networks) {
            networkState.complete()
        }
        for ((_, peerState) <- peers) {
            peerState.complete()
        }

        bgpSubscription = null
    }

    /** Filters BGP network and peer notifications, by checking that the state
      * for all referenced entities was received. This ensures that a
      * [[BgpRouter]] notification is not emitted until all networks and peers
      * were loaded from storage. */
    private def isBgpReady(any: AnyRef): Boolean = {
        vt.assertThread()

        val ready = !state.get.isTerminal &&
                    (router ne null) &&
                    networks.forall(_._2.isReady) &&
                    peers.forall(_._2.isReady)

        log.debug("BGP router ready: {}", Boolean.box(ready))
        ready
    }

    /** Processes network updates by checking whether the corresponding state
      * is completed or in error state, updating the networks map, and emitting
      * a BGP notification. */
    private def networkUpdated(networkState: NetworkState): Unit = {
        vt.assertThread()

        // Remove the state if completed or error.
        if (networkState.isCompleted) {
            log.debug("Network {} deleted", networkState.networkId)
            networks -= networkState.networkId
        } else if (networkState.isError) {
            log.error("Network {} error", networkState.networkId,
                      networkState.error)
            networks -= networkState.networkId
        }
    }

    /** Processes peer updates by checking whether the corresponding state is
      * in completed or in error state, updating the peers and neighbors map,
      * and emitting a BGP notification. */
    private def peerUpdated(peerState: PeerState): Unit = {
        vt.assertThread()

        // Remove the state if completed or error.
        if (peerState.isCompleted) {
            log.debug("Peer {} deleted", peerState.peerId)
            peers -= peerState.peerId
            removePeer(peerState.peerId)
        } else if (peerState.isError) {
            log.error("Peer {} error", peerState.peerId, peerState.error)
            peers -= peerState.peerId
            removePeer(peerState.peerId)
        } else {
            addPeer(peerState)
        }
    }

    /** Adds a peer to the peers set. The peer is added only if there is no
      * other peer with the same IP address. */
    private def addPeer(peerState: PeerState): Unit = {
        peersById get peerState.peerId match {
            case None =>
                // The peer is new (not found in the peer-by-address map)
                peersByAddress get peerState.peer.address match {
                    case None =>
                        // There is no other peer with the same address
                        log.debug("Adding peer {}", peerState.peer)
                        peersById += peerState.peerId -> peerState.peer
                        peersByAddress += peerState.peer.address ->
                                          peerState.peer
                    case Some(peer) =>
                        log.warn("Peer {} {} ignored because peer {} has the " +
                                 "same address", peerState.peerId,
                                 peerState.peer, peer)
                }
            case Some(peer)
                if peer.address == peerState.peer.address =>
                // The peer exists, but the address has not changed
                log.debug("Updating peer with same address {}",
                          peerState.peer)
                peersById(peerState.peerId) = peerState.peer
                peersByAddress(peer.address) = peerState.peer
            case Some(peer) =>
                // The peer exists, but the address has changed
                log.debug("Updating peer with different address {}",
                          peerState.peer)
                peersById(peerState.peerId) = peerState.peer
                peersByAddress -= peer.address
                peersByAddress += peerState.peer.address -> peerState.peer
        }
    }

    /** Removes the peer with the specified peer identifier from the peers set.
      * The method also iterates through all peers to verify if any of them can
      * be added to the peers set. */
    private def removePeer(peerId: UUID): Unit = {
        peersById remove peerId match {
            case Some(peer) =>
                log.debug("Removing peer {}", peer)
                peersByAddress -= peer.address
            case None =>
                log.debug("Peer {} was not used", peerId)
        }

        // Add to advertised peers any peer that is not in the peers-by-ID
        // set.
        for ((id, peerState) <- peers if !peersById.contains(id)) {
            addPeer(peerState)
        }
    }

    /** Builds the BGP notifications using the current networks and peers.
      * This method should be called when the BGP is ready. */
    private def buildBgp(): BgpRouter = {
        val bgpRouter = BgpRouter(
            as = router.getAsNumber,
            neighbors = peersByAddress.toMap,
            networks =
                networks.values.map(_.network).filter(_.cidr ne null).toSet)
        log.debug("Build BGP update {} peers {}", bgpRouter,
                  peersById.keySet)
        bgpRouter
    }

    /** A handler called when a subscriber unsubscribes. If there are no more
      * subscribers, the method unsubscribes from the underlying observables,
      * and clears any internal state. The mapper is set in an error state,
      * such that any subsequent subscriber will be notified immediately that
      * the mapper is no longer available. */
    private def onUnsubscribe(): Unit = vt.executeVt {
        if (!bgpSubject.hasObservers && (bgpSubscription ne null) &&
            state.compareAndSet(MapperState.Subscribed, MapperState.Closed)) {
            log.debug("Closing the BGP router notification stream")

            bgpSubscription.unsubscribe()
            bgpSubscription = null

            for ((_, peerState) <- peers) {
                peerState.complete()
            }
            for ((_, networkState) <- networks) {
                networkState.complete()
            }

            router = null
        }
    }
}
