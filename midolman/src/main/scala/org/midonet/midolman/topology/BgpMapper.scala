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

import org.midonet.cluster.models.Topology.{BgpNetwork, BgpPeer}
import org.midonet.cluster.util.{IPAddressUtil, IPSubnetUtil}
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
    private val NoNetwork = Network(null)

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

        private var currentNeighbor: Neighbor = null
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

        /** Gets the current neighbor. */
        def neighbor = currentNeighbor
        /** Gets the current error. */
        def error = currentError

        /** Completes the observable corresponding to this peer state. */
        def complete(): Unit = mark.onCompleted()
        /** Indicates whether the network state has received the peer data, or
          * has terminated the observable with an error. */
        def isReady: Boolean = {
            (currentNeighbor ne null) || (currentError ne null) || completed
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
            currentNeighbor =
                Neighbor(IPAddressUtil.toIPv4Addr(peer.getPeerAddress),
                         peer.getPeerAs,
                         Some(vt.config.bgpKeepAlive),
                         Some(vt.config.bgpHoldTime),
                         Some(vt.config.bgpConnectRetry))
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

    @volatile private var port: RouterPort = null
    @volatile private var error: Throwable = null
    @volatile private var publishedBgp: Boolean = false

    private val networks = new mutable.HashMap[UUID, NetworkState]
    private val peers = new mutable.HashMap[UUID, PeerState]

    private val addedNetworks = new mutable.HashMap[UUID, NetworkState]
    private val addedPeers = new mutable.HashMap[UUID, PeerState]

    private val neighborsById = new mutable.HashMap[UUID, Neighbor]
    private val neighborsByAddress = new mutable.HashMap[IPv4Addr, Neighbor]

    private lazy val portObservable = VirtualTopology
        .observable[Port](portId)
        .doOnCompleted(makeAction0(portDeleted()))
        .doOnError(makeAction1(portError))
        .onErrorResumeNext(Observable.empty)
        .flatMap[BgpNotification](makeFunc1(portUpdated))

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
      * set of corresponding BGP networks and peers. */
    private def routerPortUpdated(routerPort: RouterPort)
    : Observable[BgpNotification] = {

        val previousPort = port
        port = routerPort

        var update = false

        // Create observables for the new BGP networks corresponding to this
        // port.
        for (networkId <- port.bgpNetworkIds if !networks.contains(networkId)) {
            val networkState = new NetworkState(networkId, vt)
            networks += networkId -> networkState
            addedNetworks += networkId -> networkState
        }

        // Complete the observables for the BGP networks that are no longer part
        // of this port's BGP configuration.
        for ((networkId, networkState) <- networks.toList
             if !port.bgpNetworkIds.contains(networkId)) {
            networks -= networkId
            networkState.complete()
            update = true
        }

        // Create observables for the new BGP peers corresponding to this port.
        for (peerId <- port.bgpPeerIds if !peers.contains(peerId)) {
            val peerState = new PeerState(peerId, vt)
            peers += peerId -> peerState
            addedPeers += peerId -> peerState
        }

        // Complete the observables for the BGP peers that are no longer part of
        // the port's BGP configuration.
        for ((peerId, peerState) <- peers.toList
             if !port.bgpPeerIds.contains(peerId)) {
            peers -= peerId
            peerState.complete()
            removeNeighbor(peerId)
            update = true
        }

        // Publish observables for the added networks and peers.
        for ((_, networkState) <- addedNetworks) {
            networksSubject onNext networkState.observable
        }
        for ((_, peerState) <- addedPeers) {
            peersSubject onNext peerState.observable
        }
        addedNetworks.clear()
        addedPeers.clear()

        // Also emit a BGP notification if the port AS number has changed or if
        // the port changed from interior to exterior.
        if (previousPort ne null) {
            update = update || previousPort.localAs != port.localAs ||
                     !previousPort.isExterior && port.isExterior
        }

        // Check whether the port update should also generate a BGP update
        // notification
        if (update && isBgpReady(null) && port.isExterior) {
            // Emit both a port and a BGP notification.
            Observable.just(BgpPort(port), buildBgp())
        } else if ((previousPort ne null) && previousPort.isExterior &&
                   !port.isExterior && publishedBgp) {
            // Emit an empty BGP notification and a port notification.
            publishedBgp = false
            Observable.just(BgpPort(port),
                            BgpUpdated(BgpRouter(as = port.localAs), Set.empty))
        } else {
            // Emit only a port notification.
            Observable.just(BgpPort(port))
        }
    }

    /** This method is called when the port is deleted. It triggers a completion
      * of the networks and peers observables, by completing the corresponding
      * states. */
    private def portDeleted(): Unit = {
        log.debug("Port deleted")
        state.set(State.Completed)

        bgpSubject.onCompleted()
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

        val ready = (port ne null) &&
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
            removeNeighbor(peerState.peerId)
        } else if (peerState.isError) {
            log.error("Peer {} error", peerState.peerId, peerState.error)
            peers -= peerState.peerId
            removeNeighbor(peerState.peerId)
        } else {
            addNeighbor(peerState)
        }

        buildBgp()
    }

    /** Adds a peer to the neighbor set. The peer is added only if there is no
      * other peer with the same IP address. */
    private def addNeighbor(peerState: PeerState): Unit = {
        neighborsById get peerState.peerId match {
            case None =>
                // The peer is new (not found in the neighbor set)
                neighborsByAddress get peerState.neighbor.address match {
                    case None =>
                        // There is no other peer with the same address
                        log.debug("Adding neighbor {}", peerState.neighbor)
                        neighborsById += peerState.peerId -> peerState.neighbor
                        neighborsByAddress += peerState.neighbor.address ->
                                              peerState.neighbor
                    case Some(peer) =>
                        log.warn("Peer {} {} ignored because peer {} has the " +
                                 "same address", peerState.peerId,
                                 peerState.neighbor, peer)
                }
            case Some(neighbor)
                if neighbor.address == peerState.neighbor.address =>
                // The peer exists, but the address has not changed
                log.debug("Updating neighbor with same address {}",
                          peerState.neighbor)
                neighborsById(peerState.peerId) = peerState.neighbor
                neighborsByAddress(neighbor.address) = peerState.neighbor
            case Some(neighbor) =>
                // The peer exists, but the address has changed
                log.debug("Updating neighbor with different address {}",
                          peerState.neighbor)
                neighborsById(peerState.peerId) = peerState.neighbor
                neighborsByAddress -= neighbor.address
                neighborsByAddress += peerState.neighbor.address ->
                                      peerState.neighbor
        }
    }

    /** Removes the neighbor with the specified peer identifier from the
      * neighbors set. The method also iterates through all peers to verify if
      * any of them can be added to the neighbor set. */
    private def removeNeighbor(peerId: UUID): Unit = {
        neighborsById remove peerId match {
            case Some(neighbor) =>
                log.debug("Removing neighbor {}", neighbor)
                neighborsByAddress -= neighbor.address
            case None =>
                log.debug("Peer {} was not used", peerId)
        }

        // Add to advertised neighbors any peer that is not in the neighbors
        // set.
        for ((id, peerState) <- peers if !neighborsById.contains(id)) {
            addNeighbor(peerState)
        }
    }

    /** Builds the BGP notifications using the current networks and peers.
      * This method should be called when the BGP is ready. */
    private def buildBgp(): BgpNotification = {
        val bgpRouter = BgpRouter(
            as = port.localAs,
            neighbors = neighborsByAddress.toMap,
            networks =
                networks.values.map(_.network).filter(_.cidr ne null).toSet)
        log.debug("Build BGP update {} neighbors {}", bgpRouter,
                  neighborsById.keySet)
        publishedBgp = true
        BgpUpdated(bgpRouter, neighborsById.keySet.toSet)
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
