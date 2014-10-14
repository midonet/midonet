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
import java.util.concurrent.ConcurrentHashMap

import scala.collection.concurrent.TrieMap
import scala.concurrent.{Future, Promise}
import scala.reflect._

import akka.actor.ActorSystem

import com.google.inject.Inject
import com.typesafe.scalalogging.StrictLogging

import rx.{Observer, Subscription}

import org.midonet.cluster.data.storage.Storage
import org.midonet.midolman.NotYetException
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.midolman.topology.devices.Port

/**
 * Manages the devices of the virtual topology by supporting get and subscribe
 * requests for virtual devices.
 *
 * The virtual topology uses a set of device observables (1 per device) to
 * receive topology updates for the storage layer, and emitting simulation
 * devices as notifications. A [[DeviceObservable]] is an abstract class
 * providing common support for subscription-based notifications and reference
 * counting. Each device type implements a custom device observable (e.g.
 * [[PortObservable]]) by overriding the observable() method, where it emits
 * simulation device updates for any update received from storage. See the
 * [[DeviceObservable]] ScalaDoc for how it managers subscriptions.
 *
 * To improve lookup performance, simulation devices are cached by the device
 * observables in a complementary map. Cached copies are updated for every
 * device notification from storage, and cleared when the device stream
 * completes (normally when the device is deleted) or issues an error.
 *
 *                     |
 *   get() + cache hit | get() + cache miss / subscribe()
 *         +-----------+------------+
 *         | device map             | observable map
 * +----------------+               |
 * |  Device cache  | (1 per VT)    |
 * +----------------+               |
 *         | updates                | future / subscription
 * +----------------------------------------+
 * |             DeviceObservable           | (1 per device)
 * +----------------------------------------+
 * extends             | observable()
 * +----------------------------------------+
 * |     Port/Network/RouterObservable      | (1 per device)
 * +----------------------------------------+
 *
 * This is a companion object of the
 * [[org.midonet.midolman.topology.VirtualTopology]] class, allowing the
 * callers to query the topology using a static method call. The agent should
 * create a single instance of the class, with references to the cluster/storage
 * and the agent actor system.
 */
object VirtualTopology extends MidolmanLogging {

    type DeviceFactory = UUID => DeviceObservable[_]

    private var self: VirtualTopology = null

    /**
     * Tries to get the virtual device with the specified identifier.
     * @return The topology device if it is found in the cache of the virtual
     *         topology manager. The method throws a
     *         [[org.midonet.midolman.NotYetException]] if the device is not yet
     *         available in the cache, or an [[java.lang.Exception]] if
     *         retrieving the device failed.
     */
    @throws[NotYetException]
    @throws[Exception]
    def tryGet[D <: AnyRef](id: UUID)
                           (implicit tag: ClassTag[D]): D = {
        val device = self.devices.get(id).asInstanceOf[D]
        if (device eq null) {
            throw new NotYetException(
                guard { self.observableOf(id, tag).getEventually },
                s"Device $id not yet available")
        }
        device
    }

    /**
     * Gets the topology device with the specified identifier.
     * @return A future for the topology device. If the topology device is
     *         available in the local cache, the future completes synchronously.
     */
    def get[D <: AnyRef](id: UUID)
                        (implicit tag: ClassTag[D]): Future[D] = {
        val device = self.devices.get(id).asInstanceOf[D]
        if (device eq null) {
            guard { self.observableOf(id, tag).getEventually }
        } else {
            Promise[D]().success(device).future
        }
    }

    /**
     * Subscribes to notifications for the topology device with the specified
     * identifier. Upon subscription, which may complete asynchronously, the
     * observer will receive a notification with the current state of the
     * device.
     * @param id The device identifier.
     * @param observer The observer.
     * @param tag The device class tag.
     * @return The subscription.
     */
    def subscribe[D <: AnyRef](id: UUID, observer: Observer[D])
                              (implicit tag: ClassTag[D]): Subscription = {
        guard { self.observableOf(id, tag).subscribe(observer) }
    }

    /**
     * Registers a virtual topology instance for this companion object.
     */
    private def register(vta: VirtualTopology): Unit = {
        if (null != self) {
            throw new IllegalStateException("VTM already statically registered")
        }
        self = vta
    }

    /**
     * Guards against race conditions signaled by an [[IllegalStateException]],
     * while executing a given function.
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

@Inject
class VirtualTopology(store: Storage,
                      implicit val actorSystem: ActorSystem)
        extends StrictLogging {

    import org.midonet.midolman.topology.VirtualTopology._

    private[topology] val devices =
        new ConcurrentHashMap[UUID, AnyRef]()
    private[topology] val observables =
        new TrieMap[UUID, DeviceObservable[_]]()

    private val factories = Map[ClassTag[_], DeviceFactory](
        classTag[Port] -> ((id: UUID) => new PortObservable(id, store, this))
    )

    register(this)

    private def observableOf[D <: AnyRef](id: UUID,
                                          tag: ClassTag[D]): DeviceObservable[D] = {

        observables.getOrElse(id, {
            val observable = factories get tag match {
                case Some(factory) =>
                    factory(id).asInstanceOf[DeviceObservable[D]]
                case None =>
                    log.error("Unknown factory for {}", tag)
                    throw new RuntimeException(s"Unknown factory for $tag")
            }
            observables.putIfAbsent(id, observable) match {
                case Some(obs) =>
                    observable.unsafeUnsubscribe()
                    obs.asInstanceOf[DeviceObservable[D]]
                case None => observable
            }
        }).asInstanceOf[DeviceObservable[D]]
    }

}

