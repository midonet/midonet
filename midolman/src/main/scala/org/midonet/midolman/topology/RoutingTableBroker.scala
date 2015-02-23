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

import java.util
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

import javax.annotation.concurrent.{NotThreadSafe, ThreadSafe}

import scala.collection.JavaConverters._

import rx.Observable.OnSubscribe
import rx.subjects.PublishSubject
import rx.subscriptions.Subscriptions
import rx.{Observable, Subscriber}

import org.midonet.midolman.layer3.Route
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.midolman.state.ReplicatedSet.Watcher
import org.midonet.midolman.state.{ReplicatedSet, StateAccessException}
import org.midonet.midolman.topology.RoutingTableBroker.BrokerState
import org.midonet.util.functors._

/** Represents a set of route updates. */
case class RouteUpdates(added: Set[Route], removed: Set[Route]) {
    def nonEmpty = added.nonEmpty || removed.nonEmpty
    override def toString = s"[added=$added removed=$removed]"
}

object RoutingTableBroker {
    /** Indicates the state of the [[RoutingTableBroker]] */
    object BrokerState extends Enumeration {
        class BrokerState(val isInitialized: Boolean, val isStarted: Boolean)
            extends Val
        val Latent = new BrokerState(false, false)
        val Started = new BrokerState(true, true)
        val Stopped = new BrokerState(true, false)
    }
}

/**
 * Implements the [[OnSubscribe]] interface for an observable that emits
 * routing table updates for a given router. It wraps an underlying [[Route]]
 * replicated set, which is initialized on the first subscription, and started
 * whenever there exists at least one subscriber. The underlying table is
 * stopped when all current subscribers unsubscribe.
 *
 * It also allows the router to update the routing table with updates
 * received from persistent storage. These updates are cached until they
 * can be successfully committed to the replicated set.
 */
private class RoutingTableBroker(vt: VirtualTopology, routerId: UUID)
    extends OnSubscribe[RouteUpdates] with Watcher[Route] with MidolmanLogging {

    override def logSource =
        s"org.midonet.devices.router.router-$routerId.routing-table"

    private val subject = PublishSubject.create[RouteUpdates]()
    private val restartHandler = makeRunnable(start())

    @volatile private var state = BrokerState.Latent
    private var table: ReplicatedSet[Route] = null

    private val cache = new ConcurrentLinkedQueue[RouteUpdates]()
    private val cacheSync = new AtomicInteger()

    /** Updates the routing table with the specified routes. */
    def update(added: Set[Route], removed: Set[Route]): Unit = {
        cache.add(RouteUpdates(added, removed))
        update()
    }

    /** Indicates whether the broker has any current observers. */
    def hasObservers: Boolean = subject.hasObservers

    /**
     * Called when a [[rx.Subscriber]] subscribes to the corresponding
     * [[Observable]].
     */
    @ThreadSafe
    protected override def call(child: Subscriber[_ >: RouteUpdates])
    : Unit = {
        synchronized {
            log.debug("Subscribing to routing table")
            start()
            child.add(Subscriptions.create(makeAction0(unsubscribe())))
            subject.subscribe(child)
        }
    }

    /** Processes notifications from the underlying replicated set. */
    override def process(added: util.Collection[Route],
                         removed: util.Collection[Route]): Unit = {
        subject.onNext(RouteUpdates(added.asScala.toSet,
                                    removed.asScala.toSet))
    }

    /**
     * Initializes and starts the underlying routing table. The method
     * can be called when a new subscription occurs, or when retrying
     * to initialize the underlying map after a previous storage error.
     */
    @NotThreadSafe
    private def start(): Unit = {
        log.debug("Starting the routing table")
        if (!state.isInitialized) {
            try {
                table = vt.state.routerRoutingTable(routerId)
                table.addWatcher(this)
                state = BrokerState.Stopped
            } catch {
                case e: StateAccessException =>
                    log.warn("Initializing the routing table failed: " +
                             "retrying.", e)
                    vt.connectionWatcher.handleError(routerId.toString,
                                                     restartHandler, e)
            }
        }
        if (state.isInitialized && !state.isStarted) {
            table.start()
            state = BrokerState.Started
            update()
        }
    }

    /**
     * Updates the underlying routing table map with the updates stored in the
     * update cache. The method works by draining the cache queue for each
     * concurrent modification of the queue and only allows one thread to
     * perform this draining at a time.
     */
    @ThreadSafe
    private def update(): Unit = {
        if (state == BrokerState.Started) {
            if (cacheSync.getAndIncrement == 0) {
                do {
                    val routeUpdate = cache.poll()
                    if (routeUpdate ne null) {
                        for (route <- routeUpdate.added) {
                            table.add(route)
                        }
                        for (route <- routeUpdate.removed) {
                            table.remove(route)
                        }
                    }
                } while (cacheSync.decrementAndGet() > 0)
            }
        }
    }

    /**
     * Called when a subscribers unsubscribes. The method stops the
     * underlying routing table if there are no more subscribers.
     */
    @ThreadSafe
    private def unsubscribe(): Unit = {
        synchronized {
            log.debug("Unsubscribing from routing table")
            if (state.isStarted && !subject.hasObservers) {
                table.stop()
                state = BrokerState.Stopped
            }
        }
    }
}

