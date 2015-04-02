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
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

import javax.annotation.Nullable

import scala.collection.mutable
import scala.reflect.ClassTag

import rx.Observable.OnSubscribe
import rx.observers.Subscribers
import rx.subjects.{BehaviorSubject, PublishSubject}
import rx.{Observable, Observer, Subscriber}

import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.midolman.topology.DeviceMapper.DeviceState
import org.midonet.midolman.topology.VirtualTopology.Device
import org.midonet.util.functors._

object DeviceMapper {
    protected[topology] val SubscriptionException =
        new IllegalStateException("Device observable not connected")

    /**
     * Stores the state for a device of type T.
     */
    protected[topology] final class DeviceState[T >: Null <: Device]
        (id: UUID)(implicit tag: ClassTag[T]) {

        private var currentDevice: T = null
        private val mark = PublishSubject.create[T]

        /** The device observable, notifications on the VT thread. */
        val observable = VirtualTopology
            .observable[T](id)
            .doOnNext(makeAction1(currentDevice = _))
            .takeUntil(mark)

        /** Completes the observable corresponding to this device state */
        def complete() = mark.onCompleted()
        /** Gets the current device or null, if none is set. */
        @Nullable
        def device: T = currentDevice
        /** Indicates whether the device state has received the device data */
        def isReady: Boolean = currentDevice ne null
    }
}

/**
 * The base class for a device observable [[OnSubscribe]] handler. The call()
 * method of this class is called for every subscriber of the enclosing
 * [[rx.Observable]], and it connects the [[rx.Subscriber]] with underlying
 * observable exposed by the observable() method, generating device
 * updates for a specific device type.
 *
 * The class also implements the [[rx.Observer]] interface, to add the following
 * custom actions into the update stream, before any subscriber receives
 * the notification:
 *  - update the [[VirtualTopology]] device cache
 *  - remove the enclosing observable from the [[VirtualTopology]] observable
 *    map, when the update stream receives a onCompleted or onError notification
 *
 * The device mapper isolates the underlying observable and subscribes with
 * a [[BehaviorSubject]] funnel, which ensures that:
 *  - all subscribers correspond to a single subscription to storage
 *  - the [[DeviceMapper]] observer can execute the custom actions before
 *    subscribers are notified.
 */
abstract class DeviceMapper[D <: Device](val id: UUID, vt: VirtualTopology)
                                        (implicit tag: ClassTag[D])
    extends OnSubscribe[D] with Observer[D] with MidolmanLogging {

    import DeviceMapper.SubscriptionException

    private final val cache = BehaviorSubject.create[D]()
    private final val subscriber = Subscribers.from(cache)
    private final val subscribed = new AtomicBoolean(false)
    protected final val error = new AtomicReference[Throwable](null)

    /**
     * An implementing class must override this method, which is called
     * whenever the device observable receives a new subscriber.
     *
     * It is recommended that the access to the storage layer is handled by this
     * method, such that a subscription to storage is not created until the
     * device observable receives at least one subscriber.
     */
    protected def observable: Observable[D]

    override final def call(s: Subscriber[_ >: D]) = {
        if (subscribed.compareAndSet(false, true)) {
            observable.doOnEach(this).subscribe(subscriber)
        }
        if (subscriber.isUnsubscribed) {
            val e = error.get
            throw if (null != e) e else SubscriptionException
        }
        cache.subscribe(s)
    }

    override final def onCompleted() = {
        log.debug("Device {}/{} deleted", tag, id)
        vt.devices.remove(id) match {
            case device: D => onDeviceChanged(device)
            case _ =>
        }
        vt.observables.remove(id)
    }

    override final def onError(e: Throwable) = {
        log.error("Device {}/{} error", tag, id, e)
        error.set(e)
        vt.devices.remove(id) match {
            case device: D => onDeviceChanged(device)
            case _ =>
        }
        vt.observables.remove(id)
    }

    override final def onNext(device: D) = {
        log.debug("Device {}/{} notification: {}", tag, id, device)
        vt.devices.put(id, device)
        onDeviceChanged(device)
    }

    protected def onDeviceChanged(device: D): Unit = {}

    /**
     * Checks that this method is executed on the same thread as the one used
     * during the initialization of the mapper.
     */
    @throws[DeviceMapperException]
    @inline protected def assertThread(): Unit = {
        if (vt.vtThreadId != Thread.currentThread.getId) {
            throw new DeviceMapperException(
                tag.runtimeClass, id,
                s"Call expected on thread ${vt.vtThreadId} but received on " +
                s"${Thread.currentThread().getId}")
        }
    }

    /**
     * Synchronize devices with the new list of deviceIds. Complete and remove
     * the device state for any devices whose IDs are not in deviceIds, and
     * create, add to devices, and publish to devicesObserver a new DeviceState
     * for any IDs not already in devices.
     *
     * @param deviceIds New list of device IDs.
     * @param devices Current map of device IDs to device states.
     * @param devicesObserver Observer for publishing device observables.
     * @tparam T Device type.
     */
    protected def updateDeviceState[T >: Null <: Device](
            deviceIds: Set[UUID], devices: mutable.Map[UUID, DeviceState[T]],
            devicesObserver: Observer[Observable[T]])
            (implicit tag: ClassTag[T]): Unit = {
        // Complete and remove observables for devices no longer needed.
        for ((id, state) <- devices.toList if !deviceIds.contains(id)) {
            state.complete()
            devices -= id
        }

        // Create state for new devices, and publish their observables to the
        // aggregate observer.
        for (id <- deviceIds if !devices.contains(id)) {
            val state = new DeviceState[T](id)
            devices(id) = state
            devicesObserver.onNext(state.observable)
        }
    }
}

class DeviceMapperException(msg: String) extends Exception(msg) {
    def this(clazz: Class[_], id: UUID) =
        this(s"Device mapper exception for device ${clazz.getSimpleName} $id")
    def this(clazz: Class[_], id: UUID, msg: String) =
        this(s"Device mapper exception for device ${clazz.getSimpleName} $id" +
             s": $msg")
}
