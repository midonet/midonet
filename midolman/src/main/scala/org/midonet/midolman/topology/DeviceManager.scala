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
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

import scala.collection.concurrent.TrieMap
import scala.concurrent.Future
import scala.reflect.ClassTag
import scala.util.{Failure, Success}

import akka.actor.ActorSystem

import com.typesafe.scalalogging.StrictLogging

import rx.subjects.{PublishSubject, BehaviorSubject}
import rx.{Subscriber, Observable, Observer, Subscription}

import org.midonet.cluster.data.storage.Storage
import org.midonet.midolman.FlowController.InvalidateFlowsByTag
import org.midonet.midolman.{NotYetException, FlowController}
import org.midonet.sdn.flows.FlowTagger.FlowTag
import org.midonet.util.functors.makeAction0
import org.midonet.util.reactivex._

/**
 * The base class for a device manager.
 * @param store The storage interface.
 * @param tag The device class tag.
 * @param actorSystem The actor system.
 */
abstract class DeviceManager[D <: AnyRef](store: Storage, topology: Topology)
                                         (implicit tag: ClassTag[D],
                                                   actorSystem: ActorSystem)
        extends StrictLogging {

    /**
     * A class that manages the observable and subscriptions of a device. It
     * allows concurrent subscriptions to notifications for this device, while
     * tracking all active subscribers and closing the subscription to the
     * storage layer, when there are no more subscriber.
     *
     * This class must be extended to provide an implementation for the
     * observable() method as source of device notifications.
     *
     * @param id The device identifier.
     * @param onClose A function called when the observable is closed.
     */
    protected abstract class DeviceObservable(id: UUID,
                                              onClose: => Unit) {
        private val refCount = new AtomicInteger(0)
        private val lock = new AtomicBoolean()

        private val inStream = BehaviorSubject.create[D]()
        private val tappedStream = PublishSubject.create[D]()

        private val tap = new Observer[D]() {
            override def onCompleted(): Unit = {
                logger.debug("Device {}/{} completed", tag, id)
                topology.remove(id)
                onClose
                DeviceManager.this.onCompleted(id)
            }

            override def onError(e: Throwable): Unit = {
                logger.error("Device {}/{} error", tag, id, e)
                topology.remove(id)
                onClose
                DeviceManager.this.onError(id, e)
            }

            override def onNext(value: D) = {
                logger.debug("Device {}/{} notification: {}", tag, id, value)
                topology.put(id, value)
                DeviceManager.this.onNext(id, value)
            }
        }

        private val subscription = observable.subscribe(inStream)
        inStream.doOnEach(tap).subscribe(tappedStream)

        private val outStream = tappedStream
            .doOnSubscribe(makeAction0 {
                refCount.incrementAndGet()
            })
            .doOnUnsubscribe(makeAction0 {
                while (!lock.compareAndSet(false, true)) { }
                try {
                    if (0 == refCount.decrementAndGet()) {
                        onClose
                        subscription.unsubscribe()
                    }
                } finally { lock.set(false) }
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
            while (!lock.compareAndSet(false, true)) { }
            try {
                if (subscription.isUnsubscribed) {
                    throw new IllegalStateException(
                        "Device observer unsubscribed")
                }
                outStream.asFuture
            } finally { lock.set(false) }
        }

        /**
         * Subscribes an observer to this device observable.
         * @throws An [[java.lang.IllegalStateException]] if the device
         *         observable was unsubscribed prior to this subscription.
         */
        @throws[IllegalStateException]
        final def subscribe(subscriber: Subscriber[_ >: D]): Subscription = {
            while (!lock.compareAndSet(false, true)) { }
            try {
                if (subscription.isUnsubscribed) {
                    throw new IllegalStateException(
                        "Device observer unsubscribed")
                }
                outStream.subscribe(subscriber)
            } finally { lock.set(false) }
        }

        /**
         * Unsafe method to subscribe the device observable from the storage
         * layer. This method should only be used to unsubscribe unused device
         * observables.
         */
        final def unsafeUnsubscribe(): Unit = subscription.unsubscribe()

    }

    private val observables = new TrieMap[UUID, DeviceObservable]

    /**
     * Gets a future for the specified device. The future may be already
     * completed if the device already exists in the device observable
     * cache.
     */
    final def getEventually(id: UUID): Future[D] = guard {
        observableOf(id).getEventually
    }

    /**
     * Gets the specified device. If the device is not available, the method
     * throws a [[org.midonet.midolman.NotYetException]].
     */
    @throws[NotYetException]
    final def getOrThrow(id: UUID): D = {
        val future = getEventually(id)
        future.value match {
            case Some(Success(device)) => device
            case Some(Failure(e)) => throw e
            case None =>
                throw NotYetException(future, s"Waiting for device $id")
        }
    }

    /**
     * Subscribes to notifications for the specified device.
     */
    final def subscribe(id: UUID,
                        subscriber: Subscriber[_ >: D]): Subscription = guard {
        observableOf(id).subscribe(subscriber)
    }

    protected final def invalidate(tag: FlowTag): Unit = {
        FlowController.getRef ! InvalidateFlowsByTag(tag)
    }

    /**
     * This method must be implemented by a derived device manager class, to
     * create a device observable.
     * @param id The device identifier.
     * @param onClose A function that will perform cleanup operations when
     *                the device observable is closed.
     * @return The device observable.
     */
    protected def newObservable(id: UUID, onClose: => Unit): DeviceObservable

    /**
     * A device stream completed notification from a device observable.
     */
    protected def onCompleted(id: UUID): Unit = { }

    /**
     * A device stream error notification from a device observable.
     */
    protected def onError(id: UUID, e: Throwable): Unit = { }

    /**
     * A device stream value notification from a device observable.
     */
    protected def onNext(id: UUID, device: D): Unit = { }

    /**
     * Gets or creates the observable of the specified device.
     */
    private def observableOf(id: UUID): DeviceObservable = {
        observables.getOrElse(id, {
            val observable = newObservable(id, {
                observables.remove(id)
            })
            observables.putIfAbsent(id, observable) match {
                case Some(obs) =>
                    observable.unsafeUnsubscribe()
                    obs
                case None => observable
            }
        })
    }

    /**
     * Guards against race conditions while executing a given function, signaled
     * by an [[java.lang.IllegalStateException]].
     */
    private def guard[R](func: => R): R = {
        while (true) {
            try {
                return func
            } catch {
                case e @ (_: IllegalStateException) =>
            }
        }
        throw new IllegalStateException("Invalid state")
    }
}
