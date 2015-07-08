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

import rx.Observable.OnSubscribe
import rx._
import rx.subjects.PublishSubject
import rx.subscriptions.Subscriptions

import org.midonet.cluster.models.Topology.{Router, BgpNetwork, BgpPeer}
import org.midonet.cluster.util.{IPAddressUtil, IPSubnetUtil}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.midolman.topology.BgpMapper._
import org.midonet.midolman.topology.devices.{Port, RouterPort}
import org.midonet.packets.IPv4Addr
import org.midonet.quagga.BgpdConfiguration.{BgpRouter, Neighbor, Network}
import org.midonet.util.functors.{makeAction0, makeAction1, makeFunc1}

object BgpMapper {

    trait BgpNotification
    case class BgpPort(port: RouterPort) extends BgpNotification
    case class BgpUpdated(router: BgpRouter, peers: Set[UUID])
        extends BgpNotification

    val MapperClosedException = new IllegalStateException("BGP mapper closed")

    /** The state of the mapper subscription to the underlying storage
      * observables. Unlike the device mappers, the BGP mapper subscribes
      * itself to storage in order to expose only the BGP updates and to
      * control the notification stream as a cold observable for all
      * subscribers. */
    private[topology] object State extends Enumeration {
        type State = Value
        val Subscribed, Unsubscribed, Completed, Error, Closed = Value
    }

    /**
     * Stores the state for a BGP network, and exposes an [[Observable]] that
     * emits updates when the network changes. The observable completes either
     * when the network is deleted, or when calling the `complete()` method,
     * which is used to signal that the network is no longer used by the port.
     *
     * In addition, the observable filters all errors. When an error occurs, it
     * emits a last update, notifying that the state has changed to an error.
     */
    private final class NetworkState(val networkId: UUID, vt: VirtualTopology) {

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
            .doOnError(makeAction1(currentError = _))
            .doOnCompleted(makeAction0(() => completed = true))
            .filter(makeFunc1(_.hasSubnet))
            .map[NetworkState](makeFunc1(networkUpdated))
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
            (currentNetwork ne null) || (error ne null) || completed
        }
        /** Indicates whether the network state has terminated the observable
          * with a completed notification. */
        def isCompleted: Boolean = completed
        /** Indicates whether the network state has terminated the observable
          * with an error notification. */
        def isError: Boolean = error ne null

        /** Maps the [[org.midonet.cluster.models.Topology.BgpNetwork]] message
          * to the corresponding [[Network]] instance. */
        private def networkUpdated(network: BgpNetwork): NetworkState = {
            currentNetwork = Network(IPSubnetUtil.fromV4Proto(network.getSubnet))
            this
        }
    }

    /**
     * Stores the state for a BGP peer, and exposes an [[Observable]] that emits
     * updates when the peer changes. The observable completes either when the
     * peer is deleted, or when calling the `complete()` method, which is used
     * to signal that the network is no longer used by the port.
     *
     * In addition, the observable filters all errors. When an error occurs, it
     * emits a `null` peer, notifying that the state has changed to an error.
     */
    private final class PeerState(val peerId: UUID, vt: VirtualTopology) {

        private var currentPeer: Neighbor = null
        private var currentError: Throwable = null
        private var completed = false
        private val mark = PublishSubject.create[Neighbor]

        // The output observable for this BGP peer state. It emits notifications
        // with distinct BGP peers for the given peer identifier, while
        // filtering all errors.
        val observable = vt.store
            .observable(classOf[BgpPeer], peerId)
            .distinctUntilChanged()
            .observeOn(vt.vtScheduler)
            .doOnError(makeAction1(currentError = _))
            .doOnCompleted(makeAction0(() => completed = true))
            .filter(makeFunc1(peer => peer.hasPeerAs && peer.hasPeerAddress))
            .map[PeerState](makeFunc1(peerUpdated))
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
          * the corresponding [[Neighbor]] instance. */
        private def peerUpdated(peer: BgpPeer): PeerState = {
            val keepAlive = if (peer.hasKeepAlive) peer.getKeepAlive
                            else vt.config.bgpKeepAlive
            val holdTime = if (peer.hasHoldTime) peer.getHoldTime
                           else vt.config.bgpHoldTime
            val connectRetry = if (peer.hasConnectRetry) peer.getConnectRetry
                               else vt.config.bgpConnectRetry
            currentPeer =
                Neighbor(IPAddressUtil.toIPv4Addr(peer.getPeerAddress),
                         peer.getPeerAs,
                         Some(keepAlive),
                         Some(holdTime),
                         Some(connectRetry))
            this
        }
    }
}

/**
 * An implementation of the [[OnSubscribe]] interface for an [[rx.Observable]]
 * that emits [[BgpNotification]] updates.
 */
final class BgpMapper(portId: UUID, vt: VirtualTopology = VirtualTopology.self)
    extends OnSubscribe[BgpNotification] with MidolmanLogging {

    override def logSource = s"org.midonet.routing.bgp.bgp-$portId"

    private val state = new AtomicReference(State.Unsubscribed)
    private lazy val unsubscribeAction = makeAction0(onUnsubscribe())

    @volatile private var router: Router = null
    @volatile private var port: RouterPort = null
    @volatile private var error: Throwable = null
    @volatile private var publishedBgp: BgpRouter = null

    private val networks = new mutable.HashMap[UUID, NetworkState]
    private val peers = new mutable.HashMap[UUID, PeerState]

    private val peersById = new mutable.HashMap[UUID, Neighbor]
    private val peersByAddress = new mutable.HashMap[IPv4Addr, Neighbor]

    private lazy val portObservable = VirtualTopology
        .observable[Port](portId)
        .doOnCompleted(makeAction0(portDeleted()))
        .doOnError(makeAction1(portError))
        .onErrorResumeNext(Observable.empty)
        .flatMap[BgpNotification](makeFunc1(portUpdated))

    private val routerSubject = PublishSubject.create[Observable[BgpNotification]]
    private lazy val routerObservable = Observable.switchOnNext(routerSubject)

    private val networksSubject = PublishSubject.create[Observable[NetworkState]]
    private lazy val networksObservable = Observable
        .merge(networksSubject)
        .filter(makeFunc1(isBgpReady))
        .map[BgpNotification](makeFunc1(networkUpdated))
        .filter(makeFunc1(_ => port.isExterior))

    private val peersSubject = PublishSubject.create[Observable[PeerState]]
    private lazy val peersObservable = Observable
        .merge(peersSubject)
        .filter(makeFunc1(isBgpReady))
        .map[BgpNotification](makeFunc1(peerUpdated))
        .filter(makeFunc1(_ => port.isExterior))

    private lazy val bgpObservable: Observable[BgpNotification] =
        Observable.merge(networksObservable,
                         peersObservable,
                         routerObservable,
                         portObservable)

    private val bgpSubject = PublishSubject.create[BgpNotification]
    @volatile private var bgpSubscription: Subscription = null

    /** Processes new subscriptions to an observable created for this mapper. */
    override def call(child: Subscriber[_ >: BgpNotification]): Unit = {
        // If the mapper is in any terminal state, complete the child
        // immediately and return.
        if (state.get == State.Completed) {
            child.onCompleted()
            return
        }
        if (state.get == State.Error) {
            child.onError(error)
            return
        }
        if (state.get == State.Closed) {
            child.onError(MapperClosedException)
            return
        }
        // Otherwise, schedule the subscription on the VT thread.
        vt.executeVt {
            if (state.compareAndSet(State.Unsubscribed, State.Subscribed)) {
                bgpSubscription = bgpObservable subscribe bgpSubject
            }

            bgpSubject.startWith(initializationUpdates.asJava) subscribe child
            child.add(Subscriptions.create(unsubscribeAction))
        }
    }

    /** Returns the current mapper state. */
    @VisibleForTesting
    protected[topology] def mapperState = state.get

    /** Returns whether the mapper has any subscribed observers. */
    @VisibleForTesting
    protected[topology] def hasObservers = bgpSubject.hasObservers

    /** Processes port updates for the current port by checking the port is a
      * router port. */
    private def portUpdated(port: Port): Observable[BgpNotification] = {
        vt.assertThread()
        log.debug("Port updated: {}", port)

        port match {
            case routerPort: RouterPort => routerPortUpdated(routerPort)
            case _ =>
                log.error("Port {} is not a router port: terminating the BGP " +
                          "observable", portId)
                throw new DeviceMapperException(s"Port $portId is not a " +
                                                s"router port")
        }
    }

    /** Processes port updates for the current router port, by updating the
      * port state and current port router. */
    private def routerPortUpdated(routerPort: RouterPort)
    : Observable[BgpNotification] = {

        // Emit a router observable if this is the first port notification or
        // if the port router has changed.
        if ((port eq null) || (port.routerId != routerPort.routerId)) {
            val routerObservable = vt.store
                .observable(classOf[Router], routerPort.routerId)
                .distinctUntilChanged()
                .observeOn(vt.vtScheduler)
                .doOnCompleted(makeAction0(routerDeleted()))
                .doOnError(makeAction1(routerError))
                .onErrorResumeNext(Observable.empty)
                .flatMap[BgpNotification](makeFunc1(routerUpdated))
            routerSubject onNext routerObservable
        }

        val previousPort = port
        port = routerPort

        if ((previousPort eq null) && isBgpReady(null)) {
            // Emit a BGP notification if this the first port, and the BGP state
            // is ready.
            log.debug("Port received: publishing BGP")
            Observable.just(BgpPort(port), buildBgp())
        } else if ((previousPort ne null) && isBgpReady(null) &&
                   port.isExterior && !previousPort.isExterior) {
            // Emit a BGP notification if the port has changed from an interior
            // to an exterior port.
            log.debug("Port changed to exterior: publishing BGP")
            Observable.just(BgpPort(port), buildBgp())
        } else if ((previousPort ne null) && previousPort.isExterior &&
                   !port.isExterior && (publishedBgp ne null)) {
            // Emit a BGP notification if the port has changed from an exterior
            // to an interior port, and if a previous notification was emitted.
            log.debug("Port changed to interior: revoking BGP")
            val as = publishedBgp.as
            publishedBgp = null
            Observable.just(BgpPort(port),
                            BgpUpdated(BgpRouter(as = as), Set.empty))
        } else {
            // Emit only a port notification.
            Observable.just(BgpPort(port))
        }
    }

    /** This method is called when the port is deleted. It triggers a completion
      * of the alll observables, by completing the corresponding subjects and
      * states. */
    private def portDeleted(): Unit = {
        log.debug("Port deleted")
        state.set(State.Completed)

        bgpSubject.onCompleted()
        routerSubject onNext Observable.empty()
        routerSubject.onCompleted()
        networksSubject.onCompleted()
        peersSubject.onCompleted()
        for ((_, networkState) <- networks) {
            networkState.complete()
        }
        for ((_, peerState) <- peers) {
            peerState.complete()
        }
    }

    /** This method is called when the port emits an error. */
    private def portError(e: Throwable): Unit = {
        log.error("Port error", e)
        error = e
        state.set(State.Error)

        bgpSubject onError e
        routerSubject onNext Observable.empty()
        routerSubject.onCompleted()
        networksSubject.onCompleted()
        peersSubject.onCompleted()
        for ((_, networkState) <- networks) {
            networkState.complete()
        }
        for ((_, peerState) <- peers) {
            peerState.complete()
        }
    }

    /** Processes updates for the current router, by updating the set of
      * corresponding BGP networks and peers. */
    private def routerUpdated(r: Router): Observable[BgpNotification] = {
        vt.assertThread()

        val bgpNetworkIds = r.getBgpNetworkIdsList.asScala.map(_.asJava).toSet
        val bgpPeerIds = r.getBgpPeerIdsList.asScala.map(_.asJava).toSet

        log.debug("Router updated {} networks {} peers {}", r.getId.asJava,
                  bgpNetworkIds, bgpPeerIds)

        val addedNetworks = new mutable.MutableList[NetworkState]
        val addedPeers = new mutable.MutableList[PeerState]
        var update = false

        // Create observables for the new BGP networks corresponding to this
        // port.
        for (networkId <- bgpNetworkIds if !networks.contains(networkId)) {
            val networkState = new NetworkState(networkId, vt)
            networks += networkId -> networkState
            addedNetworks += networkState
        }

        // Complete the observables for the BGP networks that are no longer part
        // of this port's BGP configuration.
        for ((networkId, networkState) <- networks.toList
             if !bgpNetworkIds.contains(networkId)) {
            networks -= networkId
            networkState.complete()
            update = true
        }

        // Create observables for the new BGP peers corresponding to this port.
        for (peerId <- bgpPeerIds if !peers.contains(peerId)) {
            val peerState = new PeerState(peerId, vt)
            peers += peerId -> peerState
            addedPeers += peerState
        }

        // Complete the observables for the BGP peers that are no longer part of
        // the port's BGP configuration.
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

        // Also emit a BGP notification if the router AS number hash changed.
        if (previousRouter ne null) {
            update = update || previousRouter.getLocalAs != router.getLocalAs
        }

        if (update && isBgpReady(null) && port.isExterior) {
            Observable.just(buildBgp())
        } else {
            Observable.empty()
        }
    }

    /** This method is called when the current port router us deleted. It
      * triggers the completion of all observabls, by completing the
      * corresponding subjects and states. */
    private def routerDeleted(): Unit = {
        log.debug("Router deleted")
        state.set(State.Completed)

        bgpSubject.onCompleted()
        routerSubject.onCompleted()
        networksSubject.onCompleted()
        peersSubject.onCompleted()
        for ((_, networkState) <- networks) {
            networkState.complete()
        }
        for ((_, peerState) <- peers) {
            peerState.complete()
        }
    }

    /** This method is called when the current router emits an error. */
    private def routerError(e: Throwable): Unit = {
        log.debug("Router {} error {}", router.getId.asJava, e)
        error = e
        state.set(State.Error)

        bgpSubject onError e
        routerSubject.onCompleted()
        networksSubject.onCompleted()
        peersSubject.onCompleted()
        for ((_, networkState) <- networks) {
            networkState.complete()
        }
        for ((_, peerState) <- peers) {
            peerState.complete()
        }
    }

    /** Filters BGP network and peer notifications, by checking that the state
      * for all referenced entities was received. This ensures that a
      * [[BgpUpdated]] notification is not emitted until all networks and peers
      * were loaded from storage. */
    private def isBgpReady(networkOrPeer: AnyRef): Boolean = {
        vt.assertThread()

        val ready = (port ne null) && (router ne null) &&
                    networks.forall(_._2.isReady) &&
                    peers.forall(_._2.isReady)
        log.debug("BGP ready: {}", Boolean.box(ready))
        ready
    }

    /** Processes network updates by checking whether the corresponding state
      * is completed or in error state, updating the networks map, and emitting
      * a BGP notification. This method should be called when the BGP is
      * ready. */
    private def networkUpdated(networkState: NetworkState): BgpNotification = {
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

        buildBgp()
    }

    /** Processes peer updates by checking whether the corresponding state is
      * in completed or in error state, updating the peers and neighbors map,
      * and emitting a BGP notification. This method should be called when the
      * BGP is ready. */
    private def peerUpdated(peerState: PeerState): BgpNotification = {
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

        buildBgp()
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
    private def buildBgp(): BgpNotification = {
        val bgpRouter = BgpRouter(
            as = router.getLocalAs,
            neighbors = peersByAddress.toMap,
            networks =
                networks.values.map(_.network).filter(_.cidr ne null).toSet)
        log.debug("Build BGP update {} peers {}", bgpRouter,
                  peersById.keySet)
        publishedBgp = bgpRouter
        BgpUpdated(bgpRouter, peersById.keySet.toSet)
    }

    /** Returns the list of initialization updates for the current state of the
      * BGP mapper. If the port is already set, the first update is a
      * [[BgpPort]] notification. If the BGP state is ready, the second update
      * is a [[BgpUpdated]] notification with the current state. */
    private def initializationUpdates: List[BgpNotification] = {
        if (port ne null) {
            if (isBgpReady(null)) {
                List(BgpPort(port), buildBgp())
            } else {
                List(BgpPort(port))
            }
        } else {
            List.empty
        }
    }

    /** A handler called when a subscriber unsubscribes. If there are no more
      * subscribers, the method unsubscribes from the underlying observables,
      * and clears any internal state. The mapper is set in an error state,
      * such that any subsequent subscriber will be notified immediately that
      * the mapper is no longer available. */
    private def onUnsubscribe(): Unit = vt.executeVt {
        if (!bgpSubject.hasObservers &&
            state.compareAndSet(State.Subscribed, State.Closed)) {
            log.debug("Closing the BGP notification stream")

            bgpSubscription.unsubscribe()
            bgpSubscription = null

            for ((_, peerState) <- peers) {
                peerState.complete()
            }
            for ((_, networkState) <- networks) {
                networkState.complete()
            }

            port = null
        }
    }

}
