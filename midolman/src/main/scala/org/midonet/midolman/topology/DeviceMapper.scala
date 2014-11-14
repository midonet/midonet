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
import java.util.concurrent.atomic.{AtomicReference, AtomicBoolean}

import akka.actor.ActorSystem

import rx.Observable.OnSubscribe
import rx.observers.Subscribers
import rx.subjects.BehaviorSubject
import rx.{Subscriber, Observable, Observer}

import org.midonet.midolman.FlowController
import org.midonet.midolman.FlowController.InvalidateFlowsByTag
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.midolman.topology.VirtualTopology.Device

object DeviceMapper {
    protected[topology] val SUBSCRIPTION_EXCEPTION =
        new IllegalStateException("Device observable not connected")
}

/**
 * The base class for a device observable [[OnSubscribe]] handler. The call()
 * method of this class is called for every subscriber of the enclosing
 * [[rx.Observable]], and it connects the [[rx.Subscriber]] with underlying
 * observable exposed by the observable() method, generating virtual device
 * updates for a specific device type.
 *
 * The class also implements the [[rx.Observer]] interface, to add the following
 * custom actions into the update stream, before any subscriber receives
 * the notification:
 *  - update the [[VirtualTopology]] device cache
 *  - remove the enclosing observable from the [[VirtualTopology]] observable
 *    map, when the update stream receives a onCompleted or onError notification
 *  - generate flow tag invalidation
 *
 * The device mapper isolates the underlying observable and subscribes with
 * a [[BehaviorSubject]] funnel, which ensures that:
 *  - all subscribers correspond to a single subscription to storage
 *  - the [[DeviceMapper]] observer can execute the custom actions before
 *    subscribers are notified.
 */
abstract class DeviceMapper[D <: Device](id: UUID, vt: VirtualTopology)
                                            (implicit m: Manifest[D],
                                                      actorSystem: ActorSystem)
        extends OnSubscribe[D] with Observer[D] with MidolmanLogging {

    import DeviceMapper.SUBSCRIPTION_EXCEPTION

    private final val cache = BehaviorSubject.create[D]()
    private final val subscriber = Subscribers.from(cache)
    private final val subscribed = new AtomicBoolean(false)
    private final val error = new AtomicReference[Throwable](null)

    /**
     * An implementing class must override this method, which is called
     * whenever the device observable receives a new subscriber.
     *
     * It is recommended that the access to the storage layer is handled by this
     * method, such that a subscription to storage is not created until the
     * device observable receives at least one subscriber.
     */
    protected def observable: Observable[D]

    final override def call(s: Subscriber[_ >: D]): Unit = {
        if (subscribed.compareAndSet(false, true)) {
            observable.doOnEach(this).subscribe(subscriber)
        }
        if (subscriber.isUnsubscribed) {
            val e = error.get
            throw if (null != e) e else SUBSCRIPTION_EXCEPTION
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
        error.set(e)
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
