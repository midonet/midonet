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
import java.util.concurrent.ConcurrentHashMap

import scala.collection.JavaConverters._

import rx.Subscription

import org.midonet.midolman.topology.VirtualTopology.{Device, Key}
import org.midonet.util.concurrent.ReactiveActor

/**
 * A trait for an [[akka.actor.Actor]] that can subscribe to topology
 * notifications. The trait includes the common code for managing the
 * subscriptions to topology devices.
 */
trait TopologyActor extends ReactiveActor[AnyRef] {

    private val subscriptions = new ConcurrentHashMap[Key, Subscription]

    override def postStop(): Unit = {
        for (subscription <- subscriptions.values().asScala) {
            subscription.unsubscribe()
        }
        subscriptions.clear()
        super.postStop()
    }

    /**
     * Subscribes to notifications for the specified device.
     */
    protected def subscribe[D <: Device](clazz: Class[D], id: UUID)
    : Unit = {
        val key = Key(clazz, id)
        subscriptions.get(key) match {
            case null =>
                subscriptions.put(key,
                                  VirtualTopology.observable[D](clazz, id)
                                                 .subscribe(this))
            case subscription if subscription.isUnsubscribed =>
                subscriptions.put(key,
                                  VirtualTopology.observable[D](clazz, id)
                                                 .subscribe(this))
            case _ =>
        }

    }

    /**
     * Unsubscribes from notifications from the specified device. The method
     * returns `true` if the actor was subscribed to the device, `false`
     * otherwise.
     */
    protected def unsubscribe[D <: Device](clazz: Class[D], id: UUID)
    : Boolean = {
        subscriptions.get(Key(clazz, id)) match {
            case null => false
            case subscription =>
                subscription.unsubscribe()
                true
        }
    }

}
