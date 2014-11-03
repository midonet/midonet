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
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.reflect._
import scala.util.Failure

import akka.actor._
import com.google.inject.Inject
import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory

import org.midonet.cluster.Client
import org.midonet.cluster.client.{RouterPort, BridgePort, Port}
import org.midonet.cluster.data.l4lb.{Pool => PoolConfig}
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.l4lb.PoolHealthMonitorMapManager
import org.midonet.midolman.FlowController
import org.midonet.midolman.NotYetException
import org.midonet.midolman.PacketsEntryPoint
import org.midonet.midolman.Referenceable
import org.midonet.midolman.simulation._
import org.midonet.midolman.l4lb.PoolHealthMonitorMapManager.PoolHealthMonitorMap
import org.midonet.midolman.FlowController.InvalidateFlowsByTag
import org.midonet.util.concurrent._

/**
 * The VirtualTopologyActor is a component that interacts with MidoNet's state
 * management cluster and is responsible for all pieces of state that describe
 * virtual network devices.
 */
object VirtualTopologyActor extends Referenceable {
    val deviceRequestTimeout = 5 seconds
    val log = Logger(LoggerFactory.getLogger("org.midonet.devices.devices-service"))

    override val Name: String = "VirtualTopologyActor"

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

    // useful for testing, not much else.
    def clearTopology(): Unit = {
        topology.clear()
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
                                          system: ActorSystem): Future[D] =
        VirtualTopologyActor
            .ask(requestsFactory(tag)(id))(deviceRequestTimeout)
            .mapTo[D](tag).andThen {
                case Failure(ex: ClassCastException) =>
                    log.error("VirtualTopologyManager didn't return a {}!",
                              tag.runtimeClass.getSimpleName)
                case Failure(ex) =>
                    log.warn("Failed to get {}: {} - {}",
                             tag.runtimeClass.getSimpleName, id, ex)
            }(ExecutionContext.callingThread)

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

class VirtualTopologyActor extends Actor {
    import VirtualTopologyActor._
    import context.system

    // TODO(pino): unload devices with no subscribers that haven't been used
    // TODO:       in a while.
    private val idToSubscribers = mutable.Map[UUID, mutable.Set[ActorRef]]()
    private val idToUnansweredClients = mutable.Map[UUID, mutable.Set[ActorRef]]()

    private val managedDevices = mutable.Set[UUID]()

    @Inject
    override val supervisorStrategy: SupervisorStrategy = null

    @Inject
    val clusterClient: Client = null

    @Inject
    val config: MidolmanConfig = null

    /** Build a manager for a device */
    private def manageDevice(r: DeviceRequest): Unit = {
        if (managedDevices(r.id))
            return

        log.info("Build a manager for {}", r)

        val mgrFactory = r.managerFactory(clusterClient, config)
        val props = Props { mgrFactory() }.withDispatcher(context.props.dispatcher)
        context.actorOf(props, r.managerName)

        managedDevices.add(r.id)
        idToUnansweredClients.put(r.id, mutable.Set[ActorRef]())
        idToSubscribers.put(r.id, mutable.Set[ActorRef]())
    }

    private def deviceRequested(req: DeviceRequest) {
        val device = topology.get(req.id)
        if (device eq null) {
            log.debug("Adding requester {} to unanswered clients for {}",
                      sender, req)
            idToUnansweredClients(req.id).add(sender)
        } else {
            sender ! device
        }

        if (req.update) {
            log.debug("Adding requester {} to subscribed clients for {}",
                      sender, req)
            idToSubscribers(req.id).add(sender)
        }
    }

    private def updated[D <: AnyRef{def id: UUID}](device: D) {
        updated(device.id, device)
    }

    private def updated(id: UUID, device: AnyRef) {
        for (client <- idToSubscribers(id)) {
            log.debug("Sending subscriber {} the device update for {}",
                      client, id)
            client ! device
        }
        for (client <- idToUnansweredClients(id)) {
            // Avoid notifying the subscribed clients twice.
            if (!idToSubscribers(id).contains(client)) {
                log.debug("Send unanswered client {} the device update for {}",
                          client, id)
                client ! device
            }
        }
        idToUnansweredClients(id).clear()
        topology.put(id, device)
    }

    private def unsubscribe(id: UUID, actor: ActorRef): Unit = {
        def remove(setOption: Option[mutable.Set[ActorRef]]) = setOption match {
            case Some(actorSet) => actorSet.remove(actor)
            case None =>
        }

        log.debug("Client {} is unsubscribing from {}", actor, id)
        remove(idToUnansweredClients.get(id))
        remove(idToSubscribers.get(id))
    }

    def receive = {
        case null =>
            log.warn("Received null device?")
        case r: DeviceRequest =>
            log.debug("Received {}", r)
            manageDevice(r)
            deviceRequested(r)
        case u: Unsubscribe => unsubscribe(u.id, sender)
        case bridge: Bridge =>
            log.debug("Received a Bridge for {}", bridge.id)
            updated(bridge)
        case chain: Chain =>
            log.debug("Received a Chain for {}", chain.id)
            updated(chain.id, chain)
        case ipAddrGroup: IPAddrGroup =>
            log.debug("Received an IPAddrGroup for {}", ipAddrGroup.id)
            updated(ipAddrGroup)
        case loadBalancer: LoadBalancer =>
            log.debug("Received a LoadBalancer for {}", loadBalancer.id)
            updated(loadBalancer)
        case pool: Pool =>
            log.debug("Received a Pool for {}", pool.id)
            updated(pool)
        case port: Port =>
            log.debug("Received a Port for {}", port.id)
            updated(port)
        case router: Router =>
            log.debug("Received a Router for {}", router.id)
            updated(router)
        case pg: PortGroup =>
            log.debug("Received a PortGroup for {}", pg.id)
            updated(pg)
        case PoolHealthMonitorMap(mappings) =>
            log.info("Received PoolHealthMonitorMappings")
            updated(PoolConfig.POOL_HEALTH_MONITOR_MAP_KEY,
                    PoolHealthMonitorMap(mappings))
        case invalidation: InvalidateFlowsByTag =>
            log.debug("Invalidating flows for tag {}", invalidation.tag)
            FlowController ! invalidation
        case unexpected: AnyRef =>
            log.error("Received unexpected message: {}", unexpected)
        case _ =>
    }
}
