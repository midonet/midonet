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
package org.midonet.midolman.topology

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

import akka.actor.ActorSystem

import rx.Observable.OnSubscribe
import rx.observers.Subscribers
import rx.subjects.BehaviorSubject
import rx.{Subscriber, Observable, Observer}

import org.midonet.midolman.FlowController
import org.midonet.midolman.FlowController.InvalidateFlowsByTag
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.midolman.topology.VirtualTopology.Device

/**
 * TODO
 *
 * The base class for a device observable, providing support for tracking
 * subscriptions to virtual devices.
 *
 * In the current implementation, the [[DeviceMapper]] keeps an implicit
 * subscription (the counter initialized to 1), corresponding to the VT cache.
 * We can use this in a subsequent patch to perform a garbage collection
 * of unused virtual devices.
 */
abstract class DeviceMapper[D <: Device](id: UUID, vt: VirtualTopology)
                                            (implicit m: Manifest[D],
                                                      actorSystem: ActorSystem)
        extends OnSubscribe[D] with Observer[D] with MidolmanLogging {

    private final val cache = BehaviorSubject.create[D]()
    private final val subscriber = Subscribers.from(cache)
    private final val subscribed = new AtomicBoolean(false)

    protected def observable: Observable[D]

    final override def call(s: Subscriber[_ >: D]): Unit = {
        if (subscribed.compareAndSet(false, true)) {
            observable.doOnEach(this).subscribe(subscriber)
        }
        if (!subscriber.isUnsubscribed) {
            throw new IllegalStateException("Device observable not connected")
        }
        cache.subscribe(s)
    }

    final override def onCompleted(): Unit = {
        log.debug("Device {}/{} deleted", m, id)
        val device = vt.devices.remove(id)
        vt.observables.remove(id)
        invalidate(device)
    }

    final override def onError(e: Throwable): Unit = {
        log.error("Device {}/{} error", m, id, e)
        val device = vt.devices.remove(id)
        vt.observables.remove(id)
        invalidate(device)
    }

    final override def onNext(device: D): Unit = {
        log.debug("Device {}/{} notification: {}", m, id, device)
        vt.devices.put(id, device)
        invalidate(device)
    }

    private final def invalidate(device: Device): Unit = if (device ne null) {
        FlowController.getRef ! InvalidateFlowsByTag(device.deviceTag)
    }

}
