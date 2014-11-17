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

import scala.concurrent.{Future, Promise}

import com.google.inject.Inject

import rx.Observable

import org.midonet.cluster.data.storage.Storage
import org.midonet.midolman.NotYetException
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.midolman.services.MidolmanActorsService
import org.midonet.midolman.topology.devices._
import org.midonet.sdn.flows.FlowTagger.FlowTag
import org.midonet.util.reactivex._

/**
 * Manages the devices of the virtual topology by supporting get and subscribe
 * requests for virtual devices.
 *
 * The virtual topology uses a set of device observables (1 per device) to
 * receive topology updates for the storage layer, and emitting simulation
 * devices as notifications. A device observable is an [[rx.Observable]] that
 * receives a [[DeviceMapper]] as [[rx.Observable.OnSubscribe]] handler, to
 * manager subscription for virtual devices.
 *
 * A [[DeviceMapper]] is an abstract class, providing common support for
 * on-subscribe event handling, and processing per-device specific notifications
 * such as tag invalidation. Sub-classes must implement the observable() method,
 * which exposes an [[rx.Observable]] with specific virtual devices. An
 * implementation example is the [[PortMapper]], which maps the port topology
 * objects received as an observable from the storage layer to the corresponding
 * virtual device.
 *
 * It is recommended that any class implementing [[DeviceMapper]] connects to
 * storage only when the observable() method is called for the first time.
 * This ensures that connections to storage are only created when the
 * enclosing observable receives at least one subscriber. For more information
 * on the [[DeviceMapper]], see its ScalaDoc.
 *
 * To improve lookup performance, simulation devices are cached by the device
 * observables in a complementary map. Cached copies are updated for every
 * device notification from storage, and cleared when the device stream
 * completes (normally when the device is deleted) or issues an error.
 *
 *                         |
 *   get() + cache hit     | get() + cache miss / observable()
 *         +---------------+--------------+
 *         | device map                   | observable map
 * +----------------+                     |
 * |  Device cache  | (1 per VT)          |
 * +----------------+                     |
 *         | updates                      | .asFuture / .observable()
 * +------------------------------------------------+
 * |      rx.Observable(? extends DeviceMapper)     | (1 per device)
 * +------------------------------------------------+
 *                         | observable()
 * +------------------------------------------------+
 * | Port/Network/RouterMapper extends DeviceMapper | (1 per device)
 * +------------------------------------------------+
 *
 * This is a companion object of the
 * [[org.midonet.midolman.topology.VirtualTopology]] class, allowing the
 * callers to query the topology using a static method call. The agent should
 * create a single instance of the class, with references to the cluster/storage
 * and the agent actor system.
 */
object VirtualTopology extends MidolmanLogging {

    trait Device {
        def deviceTag: FlowTag
    }

    type DeviceFactory = UUID => DeviceMapper[_]

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
    def tryGet[D <: Device](id: UUID)
                           (implicit m: Manifest[D]): D = {
        val device = self.devices.get(id).asInstanceOf[D]
        if (device eq null) {
            throw new NotYetException(self.observableOf(id, m).asFuture,
                                      s"Device $id not yet available")
        }
        device
    }

    /**
     * Gets the topology device with the specified identifier.
     * @return A future for the topology device. If the topology device is
     *         available in the local cache, the future completes synchronously.
     */
    def get[D <: Device](id: UUID)
                        (implicit m: Manifest[D]): Future[D] = {
        val device = self.devices.get(id).asInstanceOf[D]
        if (device eq null) {
            self.observableOf(id, m).asFuture
        } else {
            Promise[D]().success(device).future
        }
    }

    /**
     * Returns an observable for the topology device with the specified
     * identifier. Upon subscription to this observable, which may complete
     * asynchronously, the subscriber will receive updates with the current
     * state of the device.
     */
    def observable[D <: Device](id: UUID)
                               (implicit m: Manifest[D]): Observable[D] = {
        self.observableOf(id, m)
    }

    /**
     * Registers a virtual topology instance for this companion object.
     */
    private def register(vt: VirtualTopology): Unit = {
        self = vt
    }
}

class VirtualTopology @Inject() (store: Storage,
                                 val actorsService: MidolmanActorsService)
        extends MidolmanLogging {

    import org.midonet.midolman.topology.VirtualTopology._

    implicit val actorSystem = actorsService.system

    private[topology] val devices =
        new ConcurrentHashMap[UUID, Device]()
    private[topology] val observables =
        new ConcurrentHashMap[UUID, Observable[_]]()

    private val factories = Map[Manifest[_], DeviceFactory](
        manifest[Port] -> ((id: UUID) => new PortMapper(id, store, this)),
        manifest[RouterPort] -> ((id: UUID) => new PortMapper(id, store, this)),
        manifest[BridgePort] -> ((id: UUID) => new PortMapper(id, store, this)),
        manifest[VxLanPort] -> ((id: UUID) => new PortMapper(id, store, this))
    )

    register(this)

    private def observableOf[D <: Device](id: UUID,
                                          m: Manifest[D]): Observable[D] = {

        var observable = observables.get(id)
        if (observable eq null) {
            observable = factories get m match {
                case Some(factory) =>
                    Observable.create(
                        factory(id).asInstanceOf[DeviceMapper[D]])
                case None =>
                    throw new RuntimeException(s"Unknown factory for $m")
            }
            observable = observables.putIfAbsent(id, observable) match {
                case null => observable
                case obs => obs
            }
        }
        observable.asInstanceOf[Observable[D]]
    }

}
