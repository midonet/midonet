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

import java.util
import java.util.UUID

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.reflect._

import akka.actor._
import akka.pattern.AskTimeoutException

import com.google.common.annotations.VisibleForTesting
import com.google.common.collect.HashMultimap
import com.google.inject.Inject

import org.midonet.cluster.Client
import org.midonet.cluster.data.l4lb.{Pool => PoolConfig}
import org.midonet.midolman.{NotYetException, Referenceable}
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.flows.FlowInvalidator
import org.midonet.midolman.l4lb.PoolHealthMonitorMapManager
import org.midonet.midolman.l4lb.PoolHealthMonitorMapManager.PoolHealthMonitorMap
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.midolman.simulation._
import org.midonet.midolman.topology.devices.{BridgePort, Port, RouterPort}
import org.midonet.sdn.flows.FlowTagger.FlowTag
import org.midonet.util.concurrent._

/**
 * The VirtualTopologyActor is a component that interacts with MidoNet's state
 * management cluster and is responsible for all pieces of state that describe
 * virtual network devices.
 */
object VirtualTopologyActor extends Referenceable {

    val DeviceRequestTimeout = 30 seconds

    override val Name: String = "VirtualTopologyActor"

    case class Ask(request: DeviceRequest, promise: Promise[Any])
    case class InvalidateFlowsByTag(tag: FlowTag)
    case class DeleteDevice(id: UUID) extends AnyVal

    sealed trait DeviceRequest {
        val id: UUID
        val update: Boolean

        protected[VirtualTopologyActor] val managerName: String

        override def toString =
            s"${getClass.getSimpleName}[id=$id, update=$update]"

        protected[VirtualTopologyActor]
        def managerFactory(client: Client, config: MidolmanConfig): () => Actor
    }

    case class PortRequest(id: UUID, update: Boolean = false)
            extends DeviceRequest {

        protected[VirtualTopologyActor]
        override val managerName = portManagerName(id)

        protected[VirtualTopologyActor]
        override def managerFactory(client: Client, config: MidolmanConfig) =
            () => new PortManager(id, client)
    }

    case class BridgeRequest(id: UUID, update: Boolean = false)
            extends DeviceRequest {

        protected[VirtualTopologyActor]
        override val managerName = bridgeManagerName(id)

        protected[VirtualTopologyActor]
        override def managerFactory(client: Client, config: MidolmanConfig) =
            () => new BridgeManager(id, client, config)
    }

    case class RouterRequest(id: UUID, update: Boolean = false)
        extends DeviceRequest {

        protected[VirtualTopologyActor]
        override val managerName = routerManagerName(id)

        protected[VirtualTopologyActor]
        def managerFactory(client: Client, config: MidolmanConfig) =
            () => new RouterManager(id, client, config)
    }

    case class ChainRequest(id: UUID, update: Boolean = false)
        extends DeviceRequest {

        protected[VirtualTopologyActor]
        override val managerName = chainManagerName(id)

        protected[VirtualTopologyActor]
        def managerFactory(client: Client, config: MidolmanConfig) =
            () => new ChainManager(id, client)
    }

    case class IPAddrGroupRequest(id: UUID, update: Boolean = false)
            extends DeviceRequest {

        protected[VirtualTopologyActor]
        override val managerName = iPAddrGroupManagerName(id)

        protected[VirtualTopologyActor]
        def managerFactory(client: Client, config: MidolmanConfig) =
            () => new IPAddrGroupManager(id, client)
    }

    case class LoadBalancerRequest(id: UUID, update: Boolean = false)
        extends DeviceRequest {

        protected[VirtualTopologyActor]
        override val managerName = loadBalancerManagerName(id)

        protected[VirtualTopologyActor]
        def managerFactory(client: Client, config: MidolmanConfig) =
            () => new LoadBalancerManager(id, client)
    }

    case class PoolRequest(id: UUID, update: Boolean = false)
        extends DeviceRequest {

        protected[VirtualTopologyActor]
        override val managerName = poolManagerName(id)

        protected[VirtualTopologyActor]
        def managerFactory(client: Client, config: MidolmanConfig) =
            () => new PoolManager(id, client)
    }

    case class PortGroupRequest(id: UUID, update: Boolean = false)
        extends DeviceRequest {

        protected[VirtualTopologyActor]
        override val managerName = portGroupManagerName(id)

        protected[VirtualTopologyActor]
        def managerFactory(client: Client, config: MidolmanConfig) =
            () => new PortGroupManager(id, client)
    }

    case class PoolHealthMonitorMapRequest(update: Boolean=false)
            extends DeviceRequest {

        protected[VirtualTopologyActor]
        override val managerName = poolHealthMonitorManagerName()
        override val id = PoolConfig.POOL_HEALTH_MONITOR_MAP_KEY

        protected[VirtualTopologyActor]
        def managerFactory(client: Client, config: MidolmanConfig) =
                  () => new PoolHealthMonitorMapManager(client)
    }

    case class Unsubscribe(id: UUID)

    private val topology = Topology()

    @VisibleForTesting
    private[midolman] def clearTopology(): Unit = {
        topology.clear()
    }

    @VisibleForTesting
    private[midolman] def add(id: UUID, device: AnyRef): Unit = {
        topology.put(id, device)
    }

    @VisibleForTesting
    private[midolman] def remove(id: UUID): Unit = {
        topology.remove(id)
    }

    // WARNING!! This code is meant to be called from outside the actor.
    // it should only access the volatile variable 'everything'

    @throws(classOf[NotYetException])
    def tryAsk[D <: AnyRef](id: UUID)
                           (implicit tag: ClassTag[D],
                                     system: ActorSystem): D = {
        val dev = topology.device[D](id)
        if (dev eq null) {
            throw NotYetException(requestFuture(id), s"Waiting for device: $id")
        }
        dev
    }

    private val requestsFactory = Map[ClassTag[_], UUID => DeviceRequest](
        classTag[Port]              -> (new PortRequest(_)),
        classTag[BridgePort]        -> (new PortRequest(_)),
        classTag[RouterPort]        -> (new PortRequest(_)),
        classTag[Bridge]            -> (new BridgeRequest(_)),
        classTag[Router]            -> (new RouterRequest(_)),
        classTag[Chain]             -> (new ChainRequest(_)),
        classTag[IPAddrGroup]       -> (new IPAddrGroupRequest(_)),
        classTag[LoadBalancer]      -> (new LoadBalancerRequest(_)),
        classTag[Pool]              -> (new PoolRequest(_)),
        classTag[PortGroup]         -> (new PortGroupRequest(_))
    )

    private def requestFuture[D](id: UUID)
                                (implicit tag: ClassTag[D],
                                          system: ActorSystem): Future[D] = {
        val promise = Promise[Any]()
        VirtualTopologyActor ! Ask(requestsFactory(tag)(id), promise)
        promise.future.mapTo[D](tag).recover {
            case ex: AskTimeoutException =>
                throw DeviceQueryTimeoutException(id, tag)
            case ex: DeviceDeletedException =>
                throw ex
            case ex =>
                val devType = tag.runtimeClass.getSimpleName
                throw new Exception(s"Failed to get $devType: $id", ex)
        } (ExecutionContext.callingThread)
    }

    def bridgeManagerName(bridgeId: UUID) = "BridgeManager-" + bridgeId

    def portManagerName(portId: UUID) = "PortManager-" + portId

    def routerManagerName(routerId: UUID) = "RouterManager-" + routerId

    def chainManagerName(chainId: UUID) = "ChainManager-" + chainId

    def iPAddrGroupManagerName(groupId: UUID) = "IPAddrGroupManager-" + groupId

    def loadBalancerManagerName(loadBalancerId: UUID) =
            "LoadBalancerManager-" + loadBalancerId

    def poolManagerName(poolId: UUID) = "PoolManager-" + poolId

    def portGroupManagerName(portGroupId: UUID) = "PortGroupManager-" + portGroupId

    def poolHealthMonitorManagerName() = "PoolHealthMonitorMapRequest"

    def getDeviceManagerPath(parentActorName: String, deviceName: String) =
        Referenceable.getReferenceablePath("midolman",
                "%s/%s".format(parentActorName, deviceName))

    def bridgeManagerPath(parentActorName: String, deviceId: UUID) =
        getDeviceManagerPath(parentActorName, bridgeManagerName(deviceId))

    def portManagerPath(parentActorName: String, deviceId: UUID) =
        getDeviceManagerPath(parentActorName, portManagerName(deviceId))

    def routerManagerPath(parentActorName: String, deviceId: UUID) =
        getDeviceManagerPath(parentActorName, routerManagerName(deviceId))

    def chainManagerPath(parentActorName: String, deviceId: UUID) =
        getDeviceManagerPath(parentActorName, chainManagerName(deviceId))

    def iPAddrGroupManagerPath(parentActorName: String, deviceId: UUID) =
        getDeviceManagerPath(parentActorName, iPAddrGroupManagerName(deviceId))

    def loadBalancerManagerPath(parentActorName: String, deviceId: UUID) =
        getDeviceManagerPath(parentActorName, loadBalancerManagerName(deviceId))

    def poolManagerPath(parentActorName: String, deviceId: UUID) =
        getDeviceManagerPath(parentActorName, poolManagerName(deviceId))

    def poolHealthMonitorManagerPath(parentActorName: String) =
        getDeviceManagerPath(parentActorName, poolHealthMonitorManagerName())
}

class VirtualTopologyActor extends Actor with MidolmanLogging {
    import VirtualTopologyActor._

    override def logSource = "org.midonet.devices.devices-service"

    // TODO(pino): unload devices with no subscribers that haven't been used
    // TODO:       in a while.

    private val promises = HashMultimap.create[UUID, Promise[Any]]()
    private val senders = HashMultimap.create[UUID, ActorRef]()
    private val subscribers = HashMultimap.create[UUID, ActorRef]()
    private val managers = new util.HashMap[UUID, ActorRef]()

    private var actorCounter = 0

    @Inject
    override val supervisorStrategy: SupervisorStrategy = null

    @Inject
    val clusterClient: Client = null

    @Inject
    val config: MidolmanConfig = null

    @Inject
    var flowInvalidator: FlowInvalidator = _

    @Inject
    var metrics: VirtualTopologyMetrics = _

    override def preStart(): Unit = {
        metrics.setPromises(() => promises.size())
        metrics.setSenders(() => senders.size())
        metrics.setSubscribers(() => subscribers.size())
        metrics.setDevices(() => topology.size())
    }

    private[topology] def promiseCount = promises.size()

    private[topology] def senderCount = senders.size()

    private[topology] def subscriberCount = senders.size()

    private[topology] def managersCount = managers.size()

    private def askDevice(req: DeviceRequest, promise: Promise[Any]): Unit = {
        // Check if the device has been received since making the request, in
        // which case complete the promise immediately and do not add it to
        // the promises multi-map.
        val device = topology.get(req.id)
        if (device ne null) {
            promise trySuccess device
        } else {
            val scheduler = context.system.scheduler
            promises.put(req.id, promise)
            implicit val ec = context.dispatcher
            val f = scheduler.scheduleOnce(DeviceRequestTimeout) {
                promise tryFailure new AskTimeoutException(
                    s"Request for device ${req.id} timed out after " +
                    s"$DeviceRequestTimeout milliseconds")
            }
            promise.future onComplete { _ =>
                try promises.remove(req.id, promise) finally f.cancel()
            }
        }
    }

    /** Manages the device, by adding the request sender to the set of
      * unanswered clients and subscribers, if needed.
      */
    private def manageDevice(req: DeviceRequest) : Unit = {
        if (!managers.containsKey(req.id)) {
            log.debug("Manage device {}", req.id)
            val managerFactory = req.managerFactory(clusterClient, config)
            val props = Props {
                managerFactory()
            }.withDispatcher(context.props.dispatcher)
            val uniqueName = s"${req.managerName}_$actorCounter"
            actorCounter += 1
            managers.put(req.id, context.actorOf(props, uniqueName))
        }
    }

    private def deviceRequested(req: DeviceRequest): Unit = {
        // Check if the device has been received since making the request, in
        // which case send the device to the sender immediately.
        val device = topology.get(req.id)
        if (device ne null) {
            sender ! device
        }

        if (req.update) {
            // If the sender is subscribing, add it to the subscribers map.
            log.debug("Adding requester {} to subscribed clients for {}",
                      sender(), req)
            subscribers.put(req.id, sender)
        } else if (device eq null) {
            // Else and if not yet answered, add it to the senders map.
            log.debug("Adding requester {} to unanswered clients for {}",
                      sender(), req)
            senders.put(req.id, sender)
        }

        log.debug(s"Virtual topology statistics: devices=${topology.size()} " +
                  s"promises=${promises.size()} senders=${senders.size()} " +
                  s"subscribers=${subscribers.size()} managers=${managers.size()}")
    }

    private def deviceUpdated(id: UUID, device: AnyRef): Unit = {
        // Ignore the device notification if the device is no longer managed.
        if (!managers.containsKey(id))
            return

        // Update the topology cache.
        topology.put(id, device)

        // Complete the promises for the ask requests, and remove them from the
        // map.
        val promiseIterator = promises.removeAll(id).iterator()
        while (promiseIterator.hasNext) {
            val promise = promiseIterator.next()
            log.debug("Complete ask for device {}", id)
            promise trySuccess device
        }

        // Answer the senders for direct requests, and remove them from the map.
        val senderIterator = senders.removeAll(id).iterator()
        while (senderIterator.hasNext) {
            val sender = senderIterator.next()
            log.debug("Send unanswered client {} the device update for {}",
                      sender, id)
            sender ! device
        }

        // Notify all subscribers.
        val subscriberIterator = subscribers.get(id).iterator()
        while (subscriberIterator.hasNext) {
            val subscriber = subscriberIterator.next()
            log.debug("Sending subscriber {} the device update for {}",
                      subscriber, id)
            subscriber ! device
        }

        log.debug(s"Virtual topology statistics: devices=${topology.size()} " +
                  s"promises=${promises.size()} senders=${senders.size()} " +
                  s"subscribers=${subscribers.size()} managers=${managers.size()}")
    }

    private def deviceDeleted(id: UUID): Unit = {
        topology.remove(id)

        // Complete the promises for the ask requests immediately, do not let
        // for them timeout.
        val promiseIterator = promises.removeAll(id).iterator()
        while (promiseIterator.hasNext) {
            promiseIterator.next() tryFailure DeviceDeletedException(id)
        }

        senders.removeAll(id)
        subscribers.removeAll(id)
        val manager = managers.remove(id)
        if (manager ne null) {
            context.stop(manager)
        }

        log.debug(s"Virtual topology statistics: devices=${topology.size()} " +
                  s"promises=${promises.size()} senders=${senders.size()} " +
                  s"subscribers=${subscribers.size()} managers=${managers.size()}")
    }

    private def unsubscribe(id: UUID, actor: ActorRef): Unit = {
        log.debug("Client {} is unsubscribing from {}", actor, id)
        subscribers.remove(id, actor)
        if (subscribers.get(id).isEmpty) {
            subscribers.removeAll(id)
        }
    }

    override def receive = {
        case null =>
            log.warn("Received null device?")
        case Ask(request, promise) =>
            log.debug("Ask {}", request)
            manageDevice(request)
            askDevice(request, promise)
        case request: DeviceRequest =>
            log.debug("Received {}", request)
            manageDevice(request)
            deviceRequested(request)
        case u: Unsubscribe =>
            unsubscribe(u.id, sender())
        case bridge: Bridge =>
            log.debug("Received a Bridge for {}", bridge.id)
            deviceUpdated(bridge.id, bridge)
        case chain: Chain =>
            log.debug("Received a Chain for {}", chain.id)
            deviceUpdated(chain.id, chain)
        case ipAddrGroup: IPAddrGroup =>
            log.debug("Received an IPAddrGroup for {}", ipAddrGroup.id)
            deviceUpdated(ipAddrGroup.id, ipAddrGroup)
        case loadBalancer: LoadBalancer =>
            log.debug("Received a LoadBalancer for {}", loadBalancer.id)
            deviceUpdated(loadBalancer.id, loadBalancer)
        case pool: Pool =>
            log.debug("Received a Pool for {}", pool.id)
            deviceUpdated(pool.id, pool)
        case port: Port =>
            log.debug("Received a Port for {}", port.id)
            deviceUpdated(port.id, port)
        case router: Router =>
            log.debug("Received a Router for {}", router.id)
            deviceUpdated(router.id, router)
        case pg: PortGroup =>
            log.debug("Received a PortGroup for {}", pg.id)
            deviceUpdated(pg.id, pg)
        case PoolHealthMonitorMap(mappings) =>
            log.debug("Received PoolHealthMonitorMappings")
            deviceUpdated(PoolConfig.POOL_HEALTH_MONITOR_MAP_KEY,
                          PoolHealthMonitorMap(mappings))
        case InvalidateFlowsByTag(tag) =>
            log.debug("Invalidating flows for tag {}", tag)
            flowInvalidator.scheduleInvalidationFor(tag)
        case DeleteDevice(id) =>
            log.debug("Device {} deleted", id)
            deviceDeleted(id)
        case unexpected: AnyRef =>
            log.error("Received unexpected message: {}", unexpected)
        case _ =>
    }

    // exposes the managers map for testing
    def managerOf(dev: UUID): Option[ActorRef] = Option(managers.get(dev))
}
