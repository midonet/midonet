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

import scala.concurrent.{Future, Promise}
import scala.reflect._

import akka.actor.ActorSystem

import com.google.inject.Inject
import com.typesafe.scalalogging.StrictLogging

import rx.{Observer, Subscription}

import org.midonet.cluster.client.Port
import org.midonet.cluster.data.storage.Storage
import org.midonet.midolman.NotYetException
import org.midonet.midolman.services.MidolmanActorsService
import org.midonet.util.reactivex._

/**
 * Manages the devices of the virtual topology by supporting get and subscribe
 * requests for virtual devices.
 *
 * The virtual topology uses a set of device managers (1 per type) to receive
 * topology updates from the storage layer. Each device manager maintains a set
 * of device observables (1 per device) for the subscribed devices, where each
 * includes a device RX observable emitting notification for device changes and
 * a subscription reference counter. The device observable disconnects from the
 * storage layer when the subscriber counter reaches zero.
 *
 * Subscribe requests are handled by the device manager, by subscribing the
 * observer to the device observable. Get requests use a device cache,
 * subscribed to the device observables, to improve response times for
 * existing devices.
 *
 *                     |
 *     get + cache hit | get + cache miss / subscribe
 *         +-----------+------------+
 *         | device                 |
 * +----------------+               |
 * | Topology cache | (1 per VT)    |
 * +----------------+               |
 *         | updates                | future / subscription
 * +----------------------------------------+
 * |             Device manager             | (1 per type)
 * +----------------------------------------+
 *                     | subscription
 * +----------------------------------------+
 * |            Device observable           | (1 per device)
 * +----------------------------------------+
 *
 * This is a companion object of the
 * [[org.midonet.midolman.topology.VirtualTopology]] class, allowing the
 * callers to query the topology using a static method call. The agent should
 * create a single instance of the class, with references to the cluster/storage
 * and the agent actor system.
 */
object VirtualTopology extends StrictLogging {

    private var self: VirtualTopology = null
    private val topology = Topology()

    /**
     * Tries to get the virtual device with the specified identifier.
     * @return The topology device if it is found in the cache of the virtual topology manager. The method throws a
     *         [[org.midonet.midolman.NotYetException]] if the device is not yet
     *         available in the cache, or an [[java.lang.Exception]] if
     *         retrieving the device failed.
     */
    @throws[NotYetException]
    @throws[Exception]
    def tryGet[D <: AnyRef](id: UUID)
                           (implicit tag: ClassTag[D]): D = {
        val device = topology.get(id).asInstanceOf[D]
        if (device eq null) {
            self.managerOf(tag).getOrThrow(id)
        } else {
            device
        }
    }

    /**
     * Gets the topology device with the specified identifier.
     * @return A future for the topology device. If the topology device is
     *         available in the local cache, the future completes synchronously.
     */
    def get[D <: AnyRef](id: UUID)
                        (implicit tag: ClassTag[D]): Future[D] = {
        val device = topology.get(id).asInstanceOf[D]
        if (device eq null) {
            self.managerOf(tag).getEventually(id)
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
        self.managerOf(tag).subscribe(id, observer.asSubscriber)
    }

    private def register(vta: VirtualTopology): Unit = {
        if (null == self) {
            throw new IllegalStateException("VTM already statically registered")
        }
        self = vta
    }
}

@Inject
class VirtualTopology(store: Storage,
                             actorsService: MidolmanActorsService)
        extends StrictLogging {

    import org.midonet.midolman.topology.VirtualTopology._

    private implicit val actorSystem: ActorSystem = actorsService.system

    private val managers = Map[ClassTag[_], DeviceManager[_]](
        classTag[Port] -> new PortManagerEx(store, topology)
    )

    register(this)

    private def managerOf[D <: AnyRef](tag: ClassTag[D]): DeviceManager[D] = {
        managers get tag match {
            case Some(manager) =>
                manager.asInstanceOf[DeviceManager[D]]
            case None =>
                logger.error("Unknown manager for device {}", tag)
                throw new RuntimeException(s"Unknown manager for device $tag")
        }
    }

}

