/*
 * Copyright 2016 Midokura SARL
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

package org.midonet.util.reactivex

import java.util.concurrent.atomic.AtomicReference

import rx.Observable.OnSubscribe
import rx.Subscriber
import rx.subscriptions.Subscriptions

import org.midonet.util.reactivex.SubscriptionList.State
import org.midonet.util.functors.makeAction0

object SubscriptionList {

    /**
      * Represents the current state of the subscription list, encapsulating the
      * current immutable list of observers. Any change to the subscription list
      * involves creating a new [[State]].
      */
    final class State[T](val terminated: Boolean,
                         val subscribers: Array[Subscriber[T]]) {

        def add(subscriber: Subscriber[_ >: T]): State[T] = {
            val count = subscribers.length
            val subs = new Array[Subscriber[T]](count + 1)
            System.arraycopy(subscribers, 0, subs, 0, count)
            subs(count) = subscriber.asInstanceOf[Subscriber[T]]
            new State[T](terminated, subs)
        }

        def remove(subscriber: Subscriber[_ >: T]): State[T] = {
            val count = subscribers.length
            if (count == 1 && (subscribers(0) eq subscriber)) {
                Empty.asInstanceOf[State[T]]
            } else if (count == 0) {
                this
            } else {
                var subs = new Array[Subscriber[T]](count - 1)
                var indexIn = 0
                var indexOut = 0
                while (indexIn < count) {
                    val sub = subscribers(indexIn)
                    if (sub ne subscriber) {
                        if (indexOut == count - 1) {
                            // Subscriber not found.
                            return this
                        }
                        subs(indexOut) = sub
                        indexOut += 1
                    }
                    indexIn += 1
                }
                if (indexOut == 0) {
                    Empty.asInstanceOf[State[T]]
                } else {
                    if(indexOut < count - 1) {
                        // Subscription found more than once.
                        val temp = new Array[Subscriber[T]](indexOut)
                        System.arraycopy(subs, 0, temp, 0, indexOut)
                        subs = temp
                    }
                    new State(terminated, subs)
                }
            }
        }
    }

    private val NoSubscribers = new Array[Subscriber[AnyRef]](0)
    private val Empty = new State(terminated = false, NoSubscribers)
    private val Terminated = new State(terminated = true, NoSubscribers)

}

/**
  * A concurrent lock-free [[Subscriber]] list.
  */
abstract class SubscriptionList[T]
    extends AtomicReference[State[T]](SubscriptionList.Empty.asInstanceOf[State[T]])
    with OnSubscribe[T] {

    override def call(child: Subscriber[_ >: T]): Unit = {
        start(child)
        child.add(Subscriptions.create(makeAction0 { remove(child) }))
        if (!child.isUnsubscribed) {
            if (add(child) && child.isUnsubscribed) {
                remove(child)
            }
        }
    }

    protected def subscribers: Array[Subscriber[T]] = {
        get().subscribers
    }

    protected def terminate(): Array[Subscriber[T]] = {
        val oldState = get()
        if (oldState.terminated) {
            SubscriptionList.NoSubscribers.asInstanceOf[Array[Subscriber[T]]]
        } else {
            getAndSet(SubscriptionList.Terminated.asInstanceOf[State[T]])
                .subscribers
        }
    }

    /**
      * Called when a new subscriber subscribes but before it is added to the
      * subscriber list.
      */
    protected def start(child: Subscriber[_ >: T]): Unit

    /**
      * Called after a subscriber is added to the subscriber list.
      */
    protected def added(child: Subscriber[_ >: T]): Unit

    /**
      * Called when a subscriber subscribes to a terminal state.
      */
    protected def terminated(child: Subscriber[_ >: T]): Unit

    /**
      * Adds a new subscriber to the subscriber list.
      */
    private def add(subscriber: Subscriber[_ >: T]): Boolean = {
        do {
            val oldState = get()
            if (oldState.terminated) {
                terminated(subscriber)
                return false
            }
            val newState = oldState.add(subscriber)
            if (compareAndSet(oldState, newState)) {
                added(subscriber)
                return true
            }
        } while (true)
        false
    }

    /**
      * Removes a subscriber from the subscriber list.
      */
    private def remove(subscriber: Subscriber[_ >: T]): Unit = {
        do {
            val oldState = get()
            if (oldState.terminated) {
                return
            }
            val newState = oldState.remove(subscriber)
            if ((newState eq oldState) || compareAndSet(oldState, newState)) {
                return
            }
        } while (true)
    }

}
