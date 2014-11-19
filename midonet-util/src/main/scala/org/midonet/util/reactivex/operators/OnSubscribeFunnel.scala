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

import rx.internal.operators.NotificationLite
import rx.{Observer, Producer, Subscriber, Observable}
import rx.Observable.OnSubscribe

import org.midonet.util.reactivex._

object OnSubscribeFunnel {

    /**
     * The funnel observable implementation of the [[rx.Producer]] interface,
     * processing notification requests from an [[rx.Subscriber]]. There exists
     * one [[FunnelProducer]] per [[rx.Subscriber]], which reacts to subscriber
     * back-pressure by caching unconsumed notifications by caching them in a
     * local queue.
     *
     * The [[FunnelProducer]] supports changing dynamically the set of
     * source observables, receives as a sequence of source observables.
     * Whenever the sequence emits a new observable, the [[FunnelProducer]]
     * creates a new [[FunnelSubscriber]] subscribed to that observable.
     *
     * The [[FunnelProducer]] does not subscribe the source sequence, until
     * the underlying child observable requests at least one notification. It
     * also preserves the [[rx.Subscription]] between the child subscriber and
     * source observables, such that when a child subscriber un-subscribes:
     *  - it un-subscribes the [[FunnelSubscriber]] from the source observable
     *  - it un-subscribes the [[FunnelProducer]] from the observable of
     *  source observables.
     */
    private final class FunnelProducer[T](sources: Observable[Observable[T]],
                                          child: Subscriber[_ >: T])
            extends Producer with Observer[Observable[_ <: T]] {

        private val subscriber = this.asSubscriber
        private val subscribers = FunnelSubscriberMap[T]()

        private val started = new AtomicBoolean()
        private val on = NotificationLite.instance[T]()
        private val requested = new AtomicLong()
        private val counter = new AtomicLong()

        private val queue = new ConcurrentLinkedQueue[AnyRef]

        @volatile private var done = false

        override def request(count: Long): Unit = {
            requested.addAndGet(count)
            if (started.compareAndSet(false, true)) {
                // This un-subscribes the producer from the sources observable
                // when the child un-subscribes.
                child.add(subscriber)
                sources.subscribe(subscriber)
            }
            next()
        }

        override def onCompleted(): Unit = if (!done) {
            queue.offer(on.completed())
            next()
        }

        override def onError(e: Throwable): Unit = if (!done) {
            queue.offer(on.error(e))
            next()
        }

        override def onNext(source: Observable[_ <: T]): Unit = if (!done) {
            val subscriber = subscribers.getOrElse(source, {
                new FunnelSubscriber[T](source, this, child)
            })
            subscribers.putIfAbsent(source, subscriber) match {
                case None => source.unsafeSubscribe(subscriber)
                case _ =>
            }
        }

        def onSourceCompleted(source: Observable[_ <: T]): Unit = if (!done) {
            subscribers.remove(source)
        }

        def onSourceError(e: Throwable): Unit = if (!done) {
            queue.offer(on.error(e))
            next()
        }

        def onSourceNext(t: T): Unit = if (!done) {
            queue.offer(on.next(t))
            next()
        }

        private def next(): Unit = {
            if (requested.get > 0) {
                if (counter.getAndIncrement == 0 && !done) {
                    do {
                        if (requested.getAndDecrement > 0) {
                            val obj = queue.poll()
                            if (obj ne null) {
                                if (on.accept(child, obj)) {
                                    done = true
                                    subscribers.foreach(s => s._2.unsubscribe())
                                }
                            } else {
                                requested.incrementAndGet
                            }
                        }
                    } while (counter.decrementAndGet > 0 && !done)
                }
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
            extends Subscriber[T]() {

        // This adds the funnel subscriber to the subscription list of the
        // child subscriber, and vice-versa. We do not add the child subscriber
        // as an operator to the funnel subscriber, to prevent changing the
        // producer of the child subscriber, which should be the funnel
        // producer.
        child.add(this)
        add(child)

        override def onCompleted(): Unit = {
            producer.onSourceCompleted(source)
        }

        override def onError(e: Throwable): Unit = {
            producer.onSourceError(e)
        }

        override def onNext(t: T): Unit = {
            producer.onSourceNext(t)
        }
    }

    private type FunnelSourceSet[T] = TrieMap[Observable[_ <: T], Unit]

    private object FunnelSourceSet {
        def apply[T]() = new TrieMap[Observable[_ <: T], Unit]()
    }

    private type FunnelProducerSet[T] = TrieMap[FunnelProducer[T], Unit]

    private object FunnelProducerSet {
        def apply[T]() = new TrieMap[FunnelProducer[T], Unit]()
    }

    private type FunnelSubscriberMap[T] =
        TrieMap[Observable[_ <: T], FunnelSubscriber[T]]

    private object FunnelSubscriberMap {
        def apply[T]() = new TrieMap[Observable[_ <: T], FunnelSubscriber[T]]()
    }

}

/**
 * Implementation of the [[OnSubscribe]] interface for a
 * [[org.midonet.util.reactivex.observables.FunnelObservable]].
 *
 * @param observables The initial sequence of observables added to the funnel
 */
final class OnSubscribeFunnel[T](observables: Observable[Observable[T]])
        extends OnSubscribe[T] {
    import OnSubscribeFunnel._

    override def call(child: Subscriber[_ >: T]): Unit = {
        val producer = new FunnelProducer[T](observables, child)
        child.setProducer(producer)
    }

}
