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
import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.Future
import scala.reflect.ClassTag

import akka.actor.ActorSystem

import rx.subjects.BehaviorSubject
import rx.{Observable, Observer, Subscription}

import org.midonet.midolman.FlowController
import org.midonet.midolman.FlowController.InvalidateFlowsByTag
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.midolman.topology.VirtualTopology.Device
import org.midonet.util.functors._
import org.midonet.util.reactivex._

/**
 * The base class for a device observable, providing support for tracking
 * subscriptions to virtual devices.
 *
 * In the current implementation, the [[DeviceObservable]] keeps an implicit
 * subscription (the counter initialized to 1), corresponding to the VT cache.
 * We can use this in a subsequent patch to perform a garbage collection
 * of unused virtual devices.
 */
abstract class DeviceObservable[D <: Device](id: UUID, vt: VirtualTopology)
                                            (implicit tag: ClassTag[D],
                                                      actorSystem: ActorSystem)
        extends MidolmanLogging {

    private val refCount = new AtomicInteger(1)

    private val inStream = BehaviorSubject.create[D]()

    private val tap = new Observer[D]() {
        override def onCompleted(): Unit = {
            log.debug("Device {}/{} deleted", tag, id)
            val device = vt.devices.remove(id)
            vt.observables.remove(id)
            invalidate(device)
        }

        override def onError(e: Throwable): Unit = {
            log.error("Device {}/{} error", tag, id, e)
            val device = vt.devices.remove(id)
            vt.observables.remove(id)
            invalidate(device)
        }

        override def onNext(device: D) = {
            log.debug("Device {}/{} notification: {}", tag, id, device)
            vt.devices.put(id, device)
            invalidate(device)
        }
    }

    private val subscription = observable.doOnEach(tap).subscribe(inStream)

    private val outStream = inStream
        .doOnSubscribe(makeAction0 {
            refCount.incrementAndGet()
        })
        .doOnUnsubscribe(makeAction0 {
            refCount.decrementAndGet()
        })

    /**
     * A method that must be implemented by a derived class, returning an
     * observable with notifications for this device.
     */
    protected def observable: Observable[D]

    /**
     * Gets a futures for the current device. The future may be already
     * completed if the device already exists in the device observable
     * cache.
     * @throws A [[java.lang.IllegalStateException]] if the device
     *         observable was unsubscribed prior to this subscription.
     */
    @throws[IllegalStateException]
    final def getEventually: Future[D] = {
        if (subscription.isUnsubscribed) {
            throw new IllegalStateException("Device observer unsubscribed")
        }
        outStream.asFuture
    }

    /**
     * Subscribes an observer to this device observable.
     * @throws An [[java.lang.IllegalStateException]] if the device
     *         observable was unsubscribed prior to this subscription.
     */
    @throws[IllegalStateException]
    final def subscribe(subscriber: Observer[_ >: D]): Subscription = {
        if (subscription.isUnsubscribed) {
            throw new IllegalStateException("Device observer unsubscribed")
        }
        outStream.subscribe(subscriber)
    }

    /**
     * Unsafe method to subscribe the device observable from the storage
     * layer. This method should only be used to unsubscribe unused device
     * observables.
     */
    final def unsafeUnsubscribe(): Unit = subscription.unsubscribe()

    private final def invalidate(device: Device): Unit = if (device ne null) {
        FlowController.getRef ! InvalidateFlowsByTag(device.deviceTag)
    }
}
