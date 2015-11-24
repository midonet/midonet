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


package org.midonet.cluster.services

import java.util.UUID
import java.util.concurrent.{TimeUnit, ConcurrentHashMap}

import com.google.protobuf.Message
import org.midonet.cluster.data.storage.{StateKey, StateStorage}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.util.functors.makeAction1

import rx.Subscription
import scala.concurrent.{Await, Promise}
import scala.concurrent.duration.Duration
import scala.reflect._

/**
  * This class adds functionality to watch a given state key in ZOOM, and set up
  *
  * individual watchers on each of the entities emitted.  The actions to take
  * when updates are emitted are configurable.
  *
  * Call `subscribe` to start receiving notifications through the
  * `updateHandler`, `unsubscribe` to cancel the subscriptions, and `get` to
  * get the current state key. This class takes care of multiple subscriptions
  * to the same key so `get` method won't block if there was already a
  * subscription (and a first state received) for the same namespace, id and
  * key.
  */
class DeviceStateWatcher[T <: Message](stateStore: StateStorage,
                                      updateHandler: (String, UUID, String, StateKey) => Unit)
                                      (implicit ct: ClassTag[T]) {

    // Keep the number of subscribers for a given key, in case there's more
    // than one.
    private val monitoredDevice = new ConcurrentHashMap[(String, UUID, String),
            (Promise[StateKey], Subscription, Int)]()

    private val TIMEOUT = Duration.apply(5, TimeUnit.SECONDS)

    /**
      * Subscribe to notifications about the state of a given key. When the
      * state is updated the configured `updateHandler` function passed is
      * executed.
      *
      * @param namespace [[String]] state namespace
      * @param deviceId [[UUID]] of the device
      * @param key [[String]] the state key name
      * @return [[UUID]] of the device being monitored.
      */
    def subscribe(namespace: String, deviceId: UUID, key: String): UUID = {
        if (!monitoredDevice.containsKey((namespace, deviceId, key))) {
            // Subscribe to the state of the host
            val subscription = stateStore
                    .keyObservable(namespace,
                        ct.getClass,
                        toProto(deviceId),
                        key)
                    .subscribe(makeAction1 [StateKey]{
                        case state =>
                            update(namespace, deviceId, key, state)
                    })
            val p = Promise.apply[StateKey]()
            monitoredDevice.put((namespace, deviceId, key), (p, subscription, 1))
        }
        else {
            monitoredDevice.get((namespace, deviceId, key)) match {
                case (promise, subscription, refs) =>
                    monitoredDevice.put((namespace, deviceId, key),
                        (promise, subscription, refs+1))
            }
        }
        deviceId
    }

    /**
      * Unsubscribe from notifications of a given key. It clears all related
      * subscriptions if no more subscribers are present.
      *
      * @param namespace [[String]] state namespace
      * @param deviceId [[UUID]] of the device
      * @param key [[String]] the state key name
      * @return [[UUID]] of the device being monitored.
      */
    def unsubscribe(namespace: java.lang.String, deviceId: UUID, key:String): UUID = {
        if (monitoredDevice.containsKey((namespace, deviceId, key))) {
            monitoredDevice.get(deviceId) match {
                case (promise, subscription, 1) =>
                    monitoredDevice.remove((namespace, deviceId, key))
                    subscription.unsubscribe()
                    promise.success(Option.empty[StateKey].get)
                case (promise, subscription, refs) =>
                    monitoredDevice.put((namespace, deviceId, key),
                        (promise, subscription, refs-1))
            }
        }
        deviceId
    }

    /**
      * Gets the current value of the state key. This method blocks until
      * there's a notification about it. If there was a previous subscription
      * to the same key and already received it's state, it won't block and
      * just return the current value.
      *
      * @param namespace [[String]] state namespace
      * @param deviceId [[UUID]] of the device
      * @param key [[String]] the state key name
      * @return [[UUID]] of the device being monitored.
      */
    def get(namespace: String, deviceId: UUID, key: String): StateKey = {
        if (monitoredDevice.containsKey((namespace, deviceId, key))) {
            monitoredDevice.get((namespace, deviceId, key)) match {
                case (promise, _, _) =>
                    Await.result(promise.future, TIMEOUT)
                case _ => Option.empty[StateKey]
            }
        }
        Option.empty[StateKey].get
    }

    private def update(namespace: String, deviceId: UUID, key:String, state: StateKey): Unit = {
        if (monitoredDevice.containsKey((namespace, deviceId, key))) {
            monitoredDevice.get((namespace, deviceId, key)) match {
                case (promise, subscription, refs) =>
                    if (promise.isCompleted)
                        monitoredDevice.put((namespace, deviceId, key), (
                                Promise.successful(state),
                                subscription,
                                refs))
                    else
                        promise.success(state)
                case _ =>
            }
            updateHandler(namespace, deviceId, key, state)
        }
    }

}
