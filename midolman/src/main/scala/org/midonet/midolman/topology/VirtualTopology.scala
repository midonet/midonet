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
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.{ConcurrentHashMap, ExecutorService}

import scala.collection.mutable
import scala.concurrent.Future
import scala.util.control.NonFatal

import com.codahale.metrics.MetricRegistry
import com.google.common.annotations.VisibleForTesting

import rx.Observable
import rx.Observable.OnSubscribe
import rx.schedulers.Schedulers
import rx.subjects.Subject

import org.midonet.cluster.services.MidonetBackend
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.midolman.simulation._
import org.midonet.midolman.SimulationBackChannel.BackChannelMessage
import org.midonet.midolman.logging.rule.RuleLogEventChannel
import org.midonet.midolman.monitoring.metrics.VirtualTopologyMetrics
import org.midonet.midolman.topology.devices._
import org.midonet.midolman.{NotYetException, SimulationBackChannel}
import org.midonet.sdn.flows.FlowTagger.FlowTag
import org.midonet.util.functors.{makeFunc1, makeRunnable}
import org.midonet.util.reactivex._

/**
 * This is a companion object of the [[VirtualTopology]] class, allowing the
 * callers to query the topology using a static method call. The agent should
 * create a single instance of the class, with references to the cluster/storage
 * and the agent actor system.
 */
object VirtualTopology {
    trait Device

    trait VirtualDevice extends Device {
        def deviceTag: FlowTag
    }

    case class DeviceFactory(clazz: Class[_], builder: UUID => OnSubscribe[_])

    case class Key(clazz: Class[_], id: UUID)

    private[topology] var self: VirtualTopology = null

    @throws[NotYetException]
    @throws[Exception]
    def tryGet[D <: Device](clazz: Class[D], id: UUID): D =
        self.tryGet(clazz, id)

    /**
     * Gets the virtual device with the specified identifier.
     * @return A future for the topology device. If the topology device is
     *         available in the local cache, the future completes synchronously.
     */
    def get[D <: Device](clazz: Class[D], id: UUID): Future[D] = {
        self.get(clazz, id)
    }

    /**
     * Returns an observable for the virtual device with the specified
     * identifier. Upon subscription to this observable, which may complete
     * asynchronously, the subscriber will receive updates with the current
     * state of the device.
     */
    def observable[D <: Device](clazz: Class[D], id: UUID): Observable[D] = {
        self.observableOf(clazz, id)
    }

    /**
     * Clears the topology cache.
     */
    @VisibleForTesting
    private[midonet] def clear(): Unit = {
        self.devices.clear()
    }

    /**
     * Adds a device to the virtual topology.
     */
    @VisibleForTesting
    private[midonet] def add[D <: Device](id: UUID, device: D): Unit = {
        self.devices.put(id, device)
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
class VirtualTopology(val backend: MidonetBackend,
                      val config: MidolmanConfig,
                      simBackChannel: SimulationBackChannel,
                      val ruleLogEventChannel: RuleLogEventChannel,
                      val metricRegistry: MetricRegistry,
                      val vtExecutor: ExecutorService,
                      val ioExecutor: ExecutorService,
                      vtExecutorCheck: () => Boolean)
    extends MidolmanLogging {

    import VirtualTopology._

    override def logSource = "org.midonet.devices.devices-service"

    private[midolman] val vtScheduler = Schedulers.from(vtExecutor)

    private[topology] val devices =
        new ConcurrentHashMap[UUID, Device]()
    private[topology] val observables =
        new ConcurrentHashMap[Key, Observable[_]]()

    private val cacheHits = new AtomicLong(0L)
    private val cacheMisses = new AtomicLong(0L)

    private[topology] val metrics = new VirtualTopologyMetrics(
        metricRegistry, { devices.size() }, { observables.size() },
        { cacheHits.get() }, {  cacheMisses.get() })

    private val traceChains = mutable.Map[UUID, Subject[Chain, Chain]]()

    private val factories = Map[Class[_], DeviceFactory](
        classOf[BgpPort] -> DeviceFactory(
            classOf[BgpPort], new BgpPortMapper(_, this)),
        classOf[BgpRouter] -> DeviceFactory(
            classOf[BgpRouter], new BgpRouterMapper(_, this)),
        classOf[Bridge] -> DeviceFactory(
            classOf[Bridge], new BridgeMapper(_, this, traceChains)),
        classOf[BridgePort] -> DeviceFactory(
            classOf[Port], new PortMapper(_, this, traceChains)),
        classOf[Chain] -> DeviceFactory(
            classOf[Chain], new ChainMapper(_, this, traceChains)),
        classOf[Host] -> DeviceFactory(
            classOf[Host], new HostMapper(_, this)),
        classOf[IPAddrGroup] -> DeviceFactory(
            classOf[IPAddrGroup], new IPAddrGroupMapper(_, this)),
        classOf[LoadBalancer] -> DeviceFactory(
            classOf[LoadBalancer], new LoadBalancerMapper(_, this)),
        classOf[Pool] -> DeviceFactory(
            classOf[Pool], new PoolMapper(_, this)),
        classOf[PoolHealthMonitorMap] -> DeviceFactory(
            classOf[PoolHealthMonitorMap], _ => new PoolHealthMonitorMapper(this)),
        classOf[Port] -> DeviceFactory(
            classOf[Port], new PortMapper(_, this, traceChains)),
        classOf[PortGroup] -> DeviceFactory(
            classOf[PortGroup], new PortGroupMapper(_, this)),
        classOf[QosPolicy] -> DeviceFactory(
            classOf[QosPolicy], new QosPolicyMapper(_, this)),
        classOf[Router] -> DeviceFactory(
            classOf[Router], new RouterMapper(_, this, traceChains)),
        classOf[RouterPort] -> DeviceFactory(
            classOf[Port], new PortMapper(_, this, traceChains)),
        classOf[RuleLogger] -> DeviceFactory(
            classOf[RuleLogger], new RuleLoggerMapper(_, this)),
        classOf[TunnelZone] -> DeviceFactory(
            classOf[TunnelZone], new TunnelZoneMapper(_, this)),
        classOf[Mirror] -> DeviceFactory(
            classOf[Mirror], new MirrorMapper(_, this)),
        classOf[VxLanPort] -> DeviceFactory(
            classOf[Port], new PortMapper(_, this, traceChains))
    )

    register(this)

    def store = backend.store

    def stateStore = backend.stateStore

    def stateTables = backend.stateTableStore

    private def observableOf[D <: Device](clazz: Class[D], id: UUID)
    : Observable[D] = {
        val factory = factories.getOrElse(
            clazz, throw new RuntimeException(s"Unknown factory for $clazz"))
        observableOf(factory, id)
    }

    private def observableOf[D <: Device](factory: DeviceFactory, id: UUID)
    : Observable[D] = {
        val key = Key(factory.clazz, id)
        var observable = observables get key
        if (observable eq null) {
            observable = Observable.create(factory.builder(id))
            observable = observables.putIfAbsent(key, observable) match {
                case null => observable
                case obs => obs
            }
        }
        observable.asInstanceOf[Observable[D]]
            .onErrorResumeNext(makeFunc1((t: Throwable) => t match {
                case DeviceMapper.MapperClosedException =>
                    observables.remove(key, observable)
                    observableOf(factory, id)
                case e: Throwable => Observable.error(e)
            }))
    }

    private[topology] def invalidate(tag: FlowTag): Unit = tellBackChannel(tag)

    private[topology] def tellBackChannel(msg: BackChannelMessage): Unit =
        simBackChannel.tell(msg)

    /** Safely executes a task on the virtual topology thread. */
    private[topology] def executeVt(task: => Unit) = {
        vtExecutor.execute(makeRunnable {
            try {
                task
            } catch {
                case NonFatal(e) =>
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
                case NonFatal(e) =>
                    log.error("Uncaught exception on topology IO thread.", e)
            }
        })
    }

    /** Shut down rule log event channel. Blocks until terminated. */
    private[midolman] def stopRuleLogEventChannel(): Unit = {
        if (ruleLogEventChannel != null) {
            ruleLogEventChannel.stopAsync()
            ruleLogEventChannel.awaitTerminated()
        }
    }

    /**
     * Checks that this method is executed on the virtual topology thread.
     */
    @throws[DeviceMapperException]
    @inline
    private[midolman] def assertThread(): Unit = {
        if (!vtExecutorCheck()) {
            val curThread = Thread.currentThread()
            throw new DeviceMapperException(
                s"Call expected on VT executor thread but received on " +
                s"${curThread.getId} - ${curThread.getName}")
        }
    }

    def get[D <: Device](clazz: Class[D], id: UUID): Future[D] = {
        val device = devices.get(id).asInstanceOf[D]
        if (device eq null) {
            cacheMisses.incrementAndGet()
            observableOf(clazz, id).asFuture
        } else {
            cacheHits.incrementAndGet()
            Future.successful(device)
        }
    }

    /**
     * Tries to get the virtual device with the specified identifier.
     * @return The topology device if it is found in the cache of the virtual
     *         topology. The method throws a [[NotYetException]] if the device
     *         is not yet available in the cache, or an [[Exception]] if
     *         retrieving the device failed.
     */
    @throws[NotYetException]
    @throws[Exception]
    def tryGet[D <: Device](clazz: Class[D], id: UUID): D = {
        val device = devices.get(id).asInstanceOf[D]
        if (device eq null) {
            cacheMisses.incrementAndGet()
            throw new NotYetException(observableOf(clazz, id).asFuture,
                                      s"Device ${clazz.getSimpleName}/$id " +
                                      "not yet available")
        }
        cacheHits.incrementAndGet()
        device
    }
}
