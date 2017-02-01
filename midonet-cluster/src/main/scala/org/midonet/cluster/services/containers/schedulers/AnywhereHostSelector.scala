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

package org.midonet.cluster.services.containers.schedulers

import java.util.UUID

import scala.concurrent.ExecutionContext
import scala.util.Success

import com.google.common.annotations.VisibleForTesting

import rx.Observable.OnSubscribe
import rx.subjects.{BehaviorSubject, Subject}
import rx.subscriptions.Subscriptions
import rx.{Observable, Subscriber, Subscription}

import org.midonet.cluster.models.Topology.Host
import org.midonet.cluster.services.containers.ContainerService
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.containers.{CollectionTracker, Context}
import org.midonet.util.functors._

/**
  * Implements a host selector, where the hosts are selected from all available
  * hosts that exist in the topology store.
  */
class AnywhereHostSelector(context: Context) extends HostSelector {

    private implicit val ec = ExecutionContext.fromExecutor(context.executor)

    // Caches the last notification emitted by the selector an handles the
    // subscription to all child subscribers.
    @volatile private var subject: Subject[HostsEvent, HostsEvent] = null
    // Keeps the subscription to the internal observable.
    @volatile private var subscription: Subscription = null
    // Indicates whether the initial host set was initialized.
    @volatile private var initialized: Boolean = false

    private lazy val unsubscribeAction = makeAction0(onUnsubscribe())

    // The tracker for the hosts in the host group.
    private val hostsTracker =
        new CollectionTracker[HostTracker, HostEvent](context) {
            protected override def newMember(hostId: UUID): HostTracker = {
                new HostTracker(hostId, context)
            }
        }

    // Handles updates from all hosts.
    private val hostsObservable = context.store.observable(classOf[Host])
        .onBackpressureBuffer(ContainerService.SchedulingBufferSize)
        .observeOn(context.scheduler)
        .flatMap(makeFunc1(_.take(1)))
        .doOnNext(makeAction1(newHost))

    // Handles the host notifications internally. Subscribers do not subscribe
    // to this observable directly to allow us to prime the host set with the list
    // fetched by `getAll` and to emit the initial state to each new subscriber.
    private val internalObservable = Observable
        .combineLatest[HostsEvent, Host, HostsEvent](
            hostsTracker.observable,
            hostsObservable,
            makeFunc2(buildEvent))
        .distinctUntilChanged()
        .filter(makeFunc1(_ => isReady))
        .takeUntil(mark)

    /** An observable that emits notifications with all hosts for this
      * selector.
      */
    override val observable = Observable.create(new OnSubscribe[HostsEvent] {
        override def call(child: Subscriber[_ >: HostsEvent]): Unit = {
            context.executor execute makeRunnable {
                if (mark.hasCompleted) {
                    child.onCompleted()
                    return
                }
                if (subject eq null) {
                    subject = BehaviorSubject.create()
                    subscription = internalObservable subscribe subject

                    context.store.getAll(classOf[Host]) onComplete {
                        case Success(all) if subject ne null =>
                            if (all.isEmpty) subject onNext Map.empty
                            else all foreach newHost
                            initialized = true
                            context.log debug s"Hosts state initialized with " +
                                              s"${all.size} hosts"
                        case _ =>
                    }

                }
                subject subscribe child
                child add Subscriptions.create(unsubscribeAction)
            }
        }
    })

    /** Indicates whether the host selector has initialized and received the
      * data from the hosts tracker.
      */
    override def isReady: Boolean = {
        initialized && hostsTracker.isReady
    }

    /** Indicates whether the selector in unsubscribed.
      */
    @VisibleForTesting
    protected[schedulers] def isUnsubscribed = {
        (subject eq null) || subscription.isUnsubscribed
    }

    /** Handles the notification of a new host. The method returns an
      * observable that emits the [[HostEvent]] notifications for the
      * new host.
      */
    private def newHost(host: Host): Unit = {
        val hostId = host.getId.asJava

        context.log debug s"New host $hostId: subscribing to the container " +
                          s"service state"

        hostsTracker add hostId
    }

    /** Updates the internal state with the hosts emitted by this selector.
      */
    private def buildEvent(hosts: HostsEvent, host: Host): HostsEvent = {
        context.log debug s"Hosts updated: $hosts"
        ref = hosts
        ref
    }

    /**
      * A method called when a subscriber unsubscribes. If there are no more
      * subscriber, the method unsubscribes from the underlying observables and
      * clears the internal subject and subscription. This allows them to be
      * recreated for the next subscriber.
      */
    private def onUnsubscribe(): Unit = {
        context.executor execute makeRunnable {
            if ((subject ne null) && !subject.hasObservers) {
                context.log debug s"Unsubscribing from hosts update stream"
                subscription.unsubscribe()
                subject.onCompleted()

                hostsTracker watch Set.empty

                initialized = false
                subscription = null
                subject = null
            }
        }
    }
}
