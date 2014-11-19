/*
 * Copyright 2014 Midokura SARL
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
package org.midonet.util.reactivex.operators

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.{AtomicLong, AtomicBoolean}

import scala.collection.concurrent.TrieMap
import scala.collection.mutable

import rx.internal.operators.NotificationLite
import rx.{Producer, Subscriber, Observable}
import rx.Observable.OnSubscribe

object OnSubscribeFunnel {

    /**
     * The funnel observable implementation of the [[rx.Producer]] interface,
     * processing notification requests from an [[rx.Subscriber]]. There exists
     * one [[FunnelProducer]] per [[rx.Subscriber]], which reacts to subscriber
     * back-pressure by caching unconsumed notifications by caching them in a
     * local queue.
     *
     * The [[FunnelProducer]] supports changing dynamically the set of
     * source observables.
     */
    private final class FunnelProducer[T](child: Subscriber[_ >: T],
                                          sources: Iterable[Observable[_ <: T]])
            extends Producer {

        private val subscribers = FunnelSubscriberMap[T]()

        private val on = NotificationLite.instance[T]()
        private val started = new AtomicBoolean()
        private val requested = new AtomicLong()
        private val counter = new AtomicLong()

        private val queue = new ConcurrentLinkedQueue[AnyRef]

        override def request(count: Long): Unit = {
            requested.addAndGet(count)
            if (!started.get && started.compareAndSet(false, true)) {
                for (source <- sources) {
                    val subscriber = new FunnelSubscriber[T](source, this, child)
                    subscribers += source -> subscriber
                    source.unsafeSubscribe(subscriber)
                }
            }
            next()
        }

        def addSource(source: Observable[_ <: T]): Boolean = {
            val subscriber = new FunnelSubscriber[T](source, this, child)
            subscribers.put(source, subscriber) match {
                case Some(s) => false
                case None =>
                    source.unsafeSubscribe(subscriber); true
            }
        }

        def removeSource(source: Observable[_ <: T]): Boolean = {
            subscribers.remove(source) match {
                case Some(subscriber) => subscriber.unsubscribe(); true
                case None => false
            }
        }

        def onCompleted(source: Observable[_ <: T]): Unit = {
            subscribers.remove(source)
        }

        def onError(e: Throwable): Unit = {
            queue.offer(on.error(e))
            next()
        }

        def onNext(t: T): Unit = {
            queue.offer(on.next(t))
            next()
        }

        private def next(): Unit = {
            if (counter.getAndIncrement == 0) {
                do {
                    if (requested.getAndDecrement > 0) {
                        val obj = queue.poll()
                        if (obj ne null) {
                            on.accept(child, obj)
                        } else {
                            requested.incrementAndGet
                        }
                    }
                } while (counter.decrementAndGet > 0)
            }
        }
    }

    /**
     * An [[rx.Subscriber]], which wraps a child subscriber, and adds it to the
     * list of child subscriber, such that both subscribers share the same
     * subscription to a given source observable. There exists one
     * [[FunnelSubscriber]] per subscriber and source observable.
     *
     * @param source The source observable.
     * @param producer The [[FunnelProducer]] corresponding to the child
     *                 subscriber. The producer will process notifications by
     *                 either sending them to the underlying child
     *                 subscriber if requested them, or caching them in the
     *                 local buffer.
     * @param child The underlying child subscriber.
     */
    private final class FunnelSubscriber[T](source: Observable[_ <: T],
                                            producer: FunnelProducer[T],
                                            child: Subscriber[_ >: T])
            extends Subscriber[T](child) {

        override def onCompleted(): Unit = {
            producer.onCompleted(source)
        }

        override def onError(e: Throwable): Unit = {
            producer.onError(e)
        }

        override def onNext(t: T): Unit = {
            producer.onNext(t)
        }
    }

    private type FunnelSourceSet[T] = TrieMap[Observable[_ <: T], Unit]

    private object FunnelSourceSet {
        def apply[T]() = new TrieMap[Observable[_ <: T], Unit]()

        def apply[T](observables: Seq[Observable[_ <: T]]) = {
            val map = new TrieMap[Observable[_ <: T], Unit]()
            observables.foreach(o => map.put(o, ()))
            map
        }
    }

    private type FunnelProducerSet[T] = TrieMap[FunnelProducer[T], Unit]

    private object FunnelProducerSet {
        def apply[T]() = new TrieMap[FunnelProducer[T], Unit]()
    }

    private type FunnelSubscriberMap[T] =
        mutable.HashMap[Observable[_ <: T], FunnelSubscriber[T]]

    private object FunnelSubscriberMap {
        def apply[T]() =
            new mutable.HashMap[Observable[_ <: T], FunnelSubscriber[T]]()
    }

}

/**
 * Implementation of the [[OnSubscribe]] interface for a
 * [[org.midonet.util.reactivex.observables.FunnelObservable]].
 *
 * @param observables The initial sequence of observables added to the funnel
 */
final class OnSubscribeFunnel[T](observables: Seq[Observable[_ <: T]])
        extends OnSubscribe[T] {

    import OnSubscribeFunnel._

    private val sources = FunnelSourceSet[T](observables)
    private val producers = FunnelProducerSet[T]()

    override def call(child: Subscriber[_ >: T]): Unit = this.synchronized {
        val producer = new FunnelProducer[T](child, sources.keySet)
        producers += producer -> ()
        child.setProducer(producer)
    }

    /**
     * Adds an observable to the funnel. All current subscribers will be
     * subscribed to this observable until the observable completes, or is
     * removed from the funnel.
     *
     * @return True, if the method added the observable successfully to the
     *         funnel; false, if the observable already existed.
     */
    def add(observable: Observable[_ <: T]): Boolean = {
        sources.putIfAbsent(observable, ()) match {
            case Some(fs) => false
            case None => this.synchronized {
                producers.foreach(_._1.addSource(observable))
                true
            }
        }
    }

    /**
     * Removes an observable from the funnel. When the observable is removed,
     * all subscribers of the funnel observable will also be unsubscribed from
     * the observable.
     *
     * @return True, if the method removed the observable successfully from
     *         the funnel; false, if the observable was not found in the funnel.
     */
    def remove(observable: Observable[_ <: T]): Boolean = {
        sources.remove(observable) match {
            case Some(source) => this.synchronized {
                producers.foreach(_._1.removeSource(observable))
                true
            }
            case None => false
        }
    }

}
