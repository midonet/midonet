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

package org.midonet.cluster.services.state

import java.util
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference

import sun.plugin.dom.exception.InvalidStateException

import org.midonet.cluster.services.state.StateTableSubscriberList.State

object StateTableSubscriberList {

    private type KeyMap = util.Map[StateTableKey, StateTableSubscriber]
    private type IdMap = util.Map[Long, StateTableSubscriber]

    private type KeyHashMap = util.HashMap[StateTableKey, StateTableSubscriber]
    private type IdHashMap = util.HashMap[Long, StateTableSubscriber]

    /**
      * State machine representing the list of table cache subscribers for a
      * given client. This allows the changes to the subscription list to be
      * implemented as atomic operations, such that it can be synchronized
      * with external asynchronous events like new requests or server events.
      */
    private class State(val closed: Boolean, keys: KeyMap, ids: IdMap) {

        /**
          * Adds a new subscriber to the state and returns a new state with
          * the subscriber.
          */
        def add(subscriber: StateTableSubscriber): State = {
            val newKeys = new KeyHashMap(keys)
            val newIds = new IdHashMap(ids)
            newKeys.put(subscriber.key, subscriber)
            newIds.put(subscriber.id, subscriber)
            new State(closed, newKeys, newIds)
        }

        /**
          * Removes a subscriber from the current state and returns a new state
          * with the subscriber removed.
          */
        def remove(subscriber: StateTableSubscriber): State = {
            if (ids.size() == 1 && ids.containsKey(subscriber.id)) {
                if (closed) State.Closed else State.Init
            } else if (ids.size() == 0) {
                this
            } else {
                val newKeys = new KeyHashMap(keys)
                val newIds = new IdHashMap(ids)
                newKeys.remove(subscriber.key, subscriber)
                newIds.remove(subscriber.id, subscriber)
                new State(closed, newKeys, newIds)
            }
        }

        /**
          * @return The subscriber for the specified key.
          */
        def get(key: StateTableKey): StateTableSubscriber = {
            keys.get(key)
        }

        /**
          * @return The subscriber for the specified subscription identifier.
          */
        def get(id: Long): StateTableSubscriber = {
            ids.get(id)
        }

        /**
          * @return The list of subscribers.
          */
        def subscribers: util.Collection[StateTableSubscriber] = {
            keys.values()
        }

    }

    private object State {
        val Init = new State(closed = false, Collections.emptyMap(),
                             Collections.emptyMap())
        val Closed = new State(closed = true, Collections.emptyMap(),
                               Collections.emptyMap())
    }

}

/**
  * A class that manages a list of table cache subscribers. Changes to the
  * subscriber list are atomic and ensures that once the subscriber list is
  * closed, it does not allow any further modifications.
  */
class StateTableSubscriberList {

    private final val state = new AtomicReference[State](State.Init)

    /**
      * Gets the state table subscriber for the given [[StateTableKey]] or if
      * the subscriber does not exist, it calls the specified `creator` method
      * and atomically adds it to the subscriber list. Since the `creator`
      * function may have side-effects and/or it may be called several times,
      * a `deleter` function allows to undo those side effects.
      *
      * The method throws an [[IllegalStateException]] if the subscriber list
      * was closed and no further modifications are allowed.
      *
      * @param key The [[StateTableKey]] that identifier the state table.
      * @param creator The creator function that returns a new subscriber
      *                instance, if the key is not found in the subscriber list.
      *                The method may be called several times, if there are
      *                multiple concurrent changes to the subscriber list. The
      *                method is allowed to have side effects.
      * @param deleter Called when a subscriber instance returned by the creator
      *                function is not added to list because of a concurrent
      *                modification. It allows to undo the side effects of the
      *                creator.
      * @param onNew A function called if the returned subscriber was already
      *              found in the list.
      * @param onExisting A function called if the returned subscriber was
      *                   added from the creator.
      * @return The subscriber.
      */
    @throws[InvalidStateException]
    def getOrElseUpdate(key: StateTableKey,
                        creator: => StateTableSubscriber,
                        deleter: (StateTableSubscriber) => Unit,
                        onNew: (StateTableSubscriber) => Unit,
                        onExisting: (StateTableSubscriber) => Unit)
    : StateTableSubscriber = {
        var newSubscriber: StateTableSubscriber = null
        do {
            val oldState = state.get()
            if (oldState.closed) {
                throw new InvalidStateException("Subscriptions closed")
            }
            val subscriber = oldState.get(key)
            if (subscriber ne null) {
                if (newSubscriber ne null) {
                    deleter(subscriber)
                }
                onExisting(subscriber)
                return subscriber
            } else {
                if (newSubscriber eq null) {
                    newSubscriber = creator
                }
                val newState = oldState.add(newSubscriber)
                if (state.compareAndSet(oldState, newState)) {
                    onNew(newSubscriber)
                    return newSubscriber
                }
            }
        } while (true)
        null
    }

    /**
      * Atomically removes the subscriber for the specified subscription
      * identifier, and returns it.
      *
      * The method throws an [[IllegalStateException]] if the subscriber list
      * was closed and no further modifications are allowed.
      */
    @throws[InvalidStateException]
    def remove(subscriptionId: Long): StateTableSubscriber = {
        do {
            val oldState = state.get
            if (oldState.closed) {
                throw new InvalidStateException("Subscriptions closed")
            }
            val subscriber = oldState.get(subscriptionId)
            if (subscriber eq null) {
                return null
            }
            val newState = oldState.remove(subscriber)
            if ((newState eq oldState) || state.compareAndSet(oldState, newState)) {
                return subscriber
            }
        } while (true)
        null
    }

    /**
      * Atomically removes the specified subscriber from the subscriber list.
      * The method is idempotent and does nothing if the subscriber does not
      * exist.
      */
    def remove(subscriber: StateTableSubscriber): Unit = {
        do {
            val oldState = state.get
            if (oldState.closed) {
                return
            }
            val newState = oldState.remove(subscriber)
            if ((newState eq oldState) || state.compareAndSet(oldState, newState)) {
                return
            }
        } while (true)
    }

    /**
      * Closes this subscriber list and returns the last list of subscribers.
      * Once the list is closed, any future calls to the `getOrElseUpdate` or
      * `remove` methods will throw an [[InvalidStateException]].
      */
    def close(): util.Collection[StateTableSubscriber] = {
        state.getAndSet(State.Closed).subscribers
    }

    /**
      * @return True if the subscriber list is closed.
      */
    def isClosed: Boolean = state.get eq State.Closed

}
