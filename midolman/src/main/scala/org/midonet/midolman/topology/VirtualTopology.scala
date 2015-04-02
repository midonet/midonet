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
import java.util.concurrent.{ConcurrentHashMap, Executors, ThreadFactory}

import scala.concurrent.{Future, Promise}
import scala.reflect._
import scala.util.control.NonFatal

import com.google.inject.Inject

import rx.Observable
import rx.schedulers.Schedulers

import org.midonet.cluster.DataClient
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.state.LegacyStorage
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.midolman.services.MidolmanActorsService
import org.midonet.midolman.simulation._
import org.midonet.midolman.state.ZkConnectionAwareWatcher
import org.midonet.midolman.topology.devices._
import org.midonet.midolman.{BackChannelMessage, NotYetException, SimulationBackChannel}
import org.midonet.sdn.flows.FlowTagger.FlowTag
import org.midonet.util.functors.makeRunnable
import org.midonet.util.reactivex._

/**
 * This is a companion object of the [[VirtualTopology]] class, allowing the
 * callers to query the topology using a static method call. The agent should
 * create a single instance of the class, with references to the cluster/storage
 * and the agent actor system.
 */
object VirtualTopology extends MidolmanLogging {

    trait Device

    trait VirtualDevice extends Device {
        def deviceTag: FlowTag
    }

    type DeviceFactory = UUID => DeviceMapper[_]

    private[topology] var self: VirtualTopology = null

    /**
     * Tries to get the virtual device with the specified identifier.
     * @return The topology device if it is found in the cache of the virtual
     *         topology manager. The method throws a [[NotYetException]] if the
     *         device is not yet available in the cache, or an [[Exception]] if
     *         retrieving the device failed.
     */
    @throws[NotYetException]
    @throws[Exception]
    def tryGet[D <: Device](id: UUID)
                           (implicit tag: ClassTag[D]): D = {
        val device = self.devices.get(id).asInstanceOf[D]
        if (device eq null) {
            throw new NotYetException(self.observableOf(id, tag).asFuture,
                                      s"Device $id not yet available")
        }
        device
    }

    /**
     * Gets the virtual device with the specified identifier.
     * @return A future for the topology device. If the topology device is
     *         available in the local cache, the future completes synchronously.
     */
    def get[D <: Device](id: UUID)
                        (implicit tag: ClassTag[D]): Future[D] = {
        val device = self.devices.get(id).asInstanceOf[D]
        if (device eq null) {
            self.observableOf(id, tag).asFuture
        } else {
            Promise[D]().success(device).future
        }
    }

    /**
     * Returns an observable for the virtual device with the specified
     * identifier. Upon subscription to this observable, which may complete
     * asynchronously, the subscriber will receive updates with the current
     * state of the device.
     */
    def observable[D <: Device](id: UUID)
                               (implicit tag: ClassTag[D]): Observable[D] = {
        self.observableOf(id, tag)
    }

    /**
     * Registers a virtual topology instance to this companion object.
     */
    private def register(vt: VirtualTopology): Unit = {
        self = vt
    }
}

/**
 * Manages the devices of the virtual topology by supporting get and subscribe
 * requests for virtual devices.
 *
 * The virtual topology uses a set of device observables (1 per device) to
 * receive topology updates for the storage layer, and emitting simulation
 * devices as notifications. A device observable is an [[rx.Observable]] that
 * receives a [[DeviceMapper]] as [[rx.Observable.OnSubscribe]] handler, to
 * manage subscription for virtual devices.
 *
 * A [[DeviceMapper]] is an abstract class, providing common support for
 * on-subscribe event handling, and processing per-device specific notifications
 * such as tag invalidation. Sub-classes must implement the observable() method,
 * which exposes an [[rx.Observable]] for a specific virtual device. An
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
 */
class VirtualTopology @Inject() (val backend: MidonetBackend,
                                 val config: MidolmanConfig,
                                 val state: LegacyStorage,
                                 val dataClient: DataClient,
                                 val connectionWatcher: ZkConnectionAwareWatcher,
                                 val simBackChannel: SimulationBackChannel,
                                 val actorsService: MidolmanActorsService)
    extends MidolmanLogging {

    import VirtualTopology._

    override def logSource = "org.midonet.devices.devices-service"

    @volatile private[topology] var vtThreadId: Long = _
    private val vtExecutor = Executors.newSingleThreadExecutor(
        new ThreadFactory {
            override def newThread(r: Runnable): Thread = {
                val thread = new Thread(r, "devices-service")
                vtThreadId = thread.getId
                thread
            }
        })
    private[topology] val vtScheduler = Schedulers.from(vtExecutor)

    private val ioThreadIndex = new AtomicInteger()
    private val ioExecutorFactory = new ThreadFactory {
        override def newThread(r: Runnable): Thread = {
            new Thread(r, s"devices-io-${ioThreadIndex.getAndIncrement}")
        }
    }
    private val ioExecutor = Executors.newCachedThreadPool(ioExecutorFactory)

    private[topology] val devices =
        new ConcurrentHashMap[UUID, Device]()
    private[topology] val observables =
        new ConcurrentHashMap[UUID, Observable[_]]()

    private val factories = Map[ClassTag[_], DeviceFactory](
        classTag[Bridge] -> (new BridgeMapper(_, this)(actorsService.system)),
        classTag[BridgePort] -> (new PortMapper(_, this)),
        classTag[Chain] -> (new ChainMapper(_, this)),
        classTag[Host] -> (new HostMapper(_, this)),
        classTag[IPAddrGroup] -> (new IPAddrGroupMapper(_, this)),
        classTag[LoadBalancer] -> (new LoadBalancerMapper(_, this)),
        classTag[Pool] -> (new PoolMapper(_, this)),
        classTag[PoolHealthMonitorMap] -> (id => new PoolHealthMonitorMapper(this)),
        classTag[Port] -> (new PortMapper(_, this)),
        classTag[PortGroup] -> (new PortGroupMapper(_, this)),
        classTag[Router] -> (new RouterMapper(_, this)(actorsService.system)),
        classTag[RouterPort] -> (new PortMapper(_, this)),
        classTag[TunnelZone] -> (new TunnelZoneMapper(_, this)),
        classTag[VxLanPort] -> (new PortMapper(_, this))
    )

    register(this)

    def store = backend.store

    def stateStore = backend.stateStore

    private def observableOf[D <: Device](id: UUID,
                                          tag: ClassTag[D]): Observable[D] = {

        var observable = observables.get(id)
        if (observable eq null) {
            observable = factories get tag match {
                case Some(factory) =>
                    Observable.create(
                        factory(id).asInstanceOf[DeviceMapper[D]])
                case None =>
                    throw new RuntimeException(s"Unknown factory for $tag")
            }
            observable = observables.putIfAbsent(id, observable) match {
                case null => observable
                case obs => obs
            }
        }
        observable.asInstanceOf[Observable[D]]
    }

    private[topology] def invalidate(tag: FlowTag): Unit = toBackChannel(tag)

    private[topology] def toBackChannel(msg: BackChannelMessage): Unit =
        simBackChannel.tell(msg)

    /** Safely executes a task on the virtual topology thread. */
    private[topology] def executeVt(task: => Unit) = {
        vtExecutor.execute(makeRunnable {
            try {
                task
            } catch {
                case e: Throwable =>
                    log.error("Uncaught exception on topology thread.", e)
            }
        })
    }

    /** Safely executes a task on the IO thread(s). */
    private[midolman] def executeIo(task: => Unit) = {
        ioExecutor.execute(makeRunnable {
            try {
                task
            } catch {
                case e: Throwable =>
                    log.error("Uncaught exception on topology IO thread.", e)
            }
        })
    }

    /**
     * Checks that this method is executed on the virtual topology thread.
     */
    @throws[DeviceMapperException]
    @inline
    private[topology] def assertThread(): Unit = {
        if (vtThreadId != Thread.currentThread.getId) {
            throw new DeviceMapperException(
                s"Call expected on thread $vtThreadId but received on " +
                s"${Thread.currentThread().getId}")
        }
    }

}
