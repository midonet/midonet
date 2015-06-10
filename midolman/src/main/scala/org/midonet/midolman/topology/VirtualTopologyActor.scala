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
import akka.pattern.AskTimeoutException

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.reflect._

import akka.actor._
import com.google.inject.Inject
import com.typesafe.scalalogging.Logger
import org.midonet.midolman.flows.FlowInvalidator
import org.midonet.midolman.topology.VirtualTopology.Device
import org.midonet.sdn.flows.FlowTagger.FlowTag
import org.slf4j.LoggerFactory

import org.midonet.cluster.Client
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.l4lb.PoolHealthMonitorMapManager
import org.midonet.midolman.NotYetException
import org.midonet.midolman.Referenceable
import org.midonet.midolman.simulation._
import org.midonet.midolman.topology.devices.{PoolHealthMonitorMap, RouterPort, BridgePort, Port}
import org.midonet.util.concurrent._

/**
 * The VirtualTopologyActor is a component that interacts with MidoNet's state
 * management cluster and is responsible for all pieces of state that describe
 * virtual network devices.
 */
object VirtualTopologyActor extends Referenceable {
    val deviceRequestTimeout = 30 seconds
    val log = Logger(LoggerFactory.getLogger("org.midonet.devices.devices-service"))

    override val Name: String = "VirtualTopologyActor"

    case class InvalidateFlowsByTag(tag: FlowTag)

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
        override val id = PoolHealthMonitorMapper.PoolHealthMonitorMapKey

        protected[VirtualTopologyActor]
        def managerFactory(client: Client, config: MidolmanConfig) =
                  () => new PoolHealthMonitorMapManager(client)
    }

    case class Unsubscribe(id: UUID)

    // useful for testing, not much else.
    def clearTopology(): Unit = {
        VirtualTopology.self.devices.clear()
    }

    // WARNING!! This code is meant to be called from outside the actor.
    // it should only access the volatile variable 'everything'

    @throws(classOf[NotYetException])
    def tryAsk[D <: Device](id: UUID)
                           (implicit tag: ClassTag[D],
                                     system: ActorSystem): D = {
        val dev = VirtualTopology.self.devices.get(id).asInstanceOf[D]
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
            .mapTo[D](tag).recover{
                case ex: AskTimeoutException =>
                    throw DeviceQueryTimeoutException(id, tag)
                case ex =>
                    val devType = tag.runtimeClass.getSimpleName
                    throw new Exception(s"Failed to get $devType: $id", ex)
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

class VirtualTopologyActor extends VirtualTopologyRedirector {
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

    @Inject
    var flowInvalidator: FlowInvalidator = _

    /** Manages the device, by adding the request sender to the set of
      * unanswered clients and subscribers, if needed.
      * @param createManager If true, it creates a legacy device manager for
      *                      this device.
      */
    protected override def manageDevice(req: DeviceRequest,
                                        createManager: Boolean) : Unit = {
        if (managedDevices.contains(req.id)) {
            return
        }

        log.info("Manage device {}", req.id)
        if (createManager) {
            val mgrFactory = req.managerFactory(clusterClient, config)
            val props = Props { mgrFactory() }
                .withDispatcher(context.props.dispatcher)
            context.actorOf(props, req.managerName)
        }

        managedDevices += req.id
        idToUnansweredClients.put(req.id, mutable.Set[ActorRef]())
        idToSubscribers.put(req.id, mutable.Set[ActorRef]())
    }

    protected override def deviceRequested(req: DeviceRequest,
                                           useCache: Boolean): Unit = {
        val device = VirtualTopology.self.devices.get(req.id)
        if ((device ne null) && useCache) {
            sender ! device
        } else {
            log.debug("Adding requester {} to unanswered clients for {}",
                      sender(), req)
            idToUnansweredClients(req.id).add(sender())
        }

        if (req.update) {
            log.debug("Adding requester {} to subscribed clients for {}",
                      sender(), req)
            idToSubscribers(req.id).add(sender())
        }
    }

    protected override def deviceUpdated(id: UUID, device: Device) {
        val subscribers = idToSubscribers.get(id) match {
            case Some(s) => s
            case None => return
        }
        val clients = idToUnansweredClients.get(id) match {
            case Some(c) => c
            case None => return
        }
        for (client <- subscribers) {
            log.debug("Sending subscriber {} the device update for {}",
                      client, id)
            client ! device
        }
        for (client <- clients if !subscribers.contains(client)) {
            log.debug("Send unanswered client {} the device update for {}",
                      client, id)
            client ! device
        }
        clients.clear()
    }

    protected override def deviceDeleted(id: UUID): Unit = {
        managedDevices.remove(id)
        idToSubscribers.remove(id)
        idToUnansweredClients.remove(id)
    }

    protected override def deviceError(id: UUID, e: Throwable): Unit = {
        managedDevices.remove(id)
        val subscribers = idToSubscribers.remove(id) match {
            case Some(s) => s
            case None => return
        }
        val clients = idToUnansweredClients.remove(id) match {
            case Some(c) => c
            case None => return
        }
        // Notify the error to promise sender actors that are not subscribers:
        // this allows tryAsk() futures to complete immediately with an error.
        for (client <- clients if !subscribers.contains(client) &&
                 client.getClass.getName == "akka.pattern.PromiseActorRef") {
            log.debug("Send unanswered client {} device error for {} " +
                      ": {}", client, id, e)
            client ! Status.Failure(e)
        }
    }

    protected override def unsubscribe(id: UUID, actor: ActorRef): Unit = {
        def remove(setOption: Option[mutable.Set[ActorRef]]) = setOption match {
            case Some(actorSet) => actorSet.remove(actor)
            case None =>
        }

        log.debug("Client {} is unsubscribing from {}", actor, id)
        remove(idToUnansweredClients.get(id))
        remove(idToSubscribers.get(id))
    }

    protected override def hasSubscribers(id: UUID): Boolean = {
        idToSubscribers get id match {
            case Some(set) => set.nonEmpty
            case None => false
        }
    }

    @inline private def update(id: UUID, device: Device) = {
        VirtualTopology.self.devices.put(id, device)
        deviceUpdated(id, device)
    }

    override def receive = super.receive orElse {
        case null =>
            log.warn("Received null device")
        case r: DeviceRequest =>
            log.debug("Received: {}", r)
            manageDevice(r, createManager = true)
            deviceRequested(r, useCache = true)
        case u: Unsubscribe => unsubscribe(u.id, sender())
        case bridge: Bridge =>
            log.debug("Received device: {}", bridge)
            update(bridge.id, bridge)
        case chain: Chain =>
            log.debug("Received device: {}", chain)
            update(chain.id, chain)
        case ipAddrGroup: IPAddrGroup =>
            log.debug("Received device: {}", ipAddrGroup)
            update(ipAddrGroup.id, ipAddrGroup)
        case loadBalancer: LoadBalancer =>
            log.debug("Received device: {}", loadBalancer)
            update(loadBalancer.id, loadBalancer)
        case pool: Pool =>
            log.debug("Received device: {}", pool)
            update(pool.id, pool)
        case port: Port =>
            log.debug("Received device: {}", port)
            update(port.id, port)
        case portGroup: PortGroup =>
            log.debug("Received device: {}", portGroup)
            update(portGroup.id, portGroup)
        case router: Router =>
            log.debug("Received device: {}", router)
            update(router.id, router)
        case PoolHealthMonitorMap(mappings) =>
            log.debug("Received pool health monitor mappings: {}", mappings)
            update(PoolHealthMonitorMapper.PoolHealthMonitorMapKey,
                   PoolHealthMonitorMap(mappings))
        case InvalidateFlowsByTag(tag) =>
            log.debug("Invalidating flows for tag {}", tag)
            flowInvalidator.scheduleInvalidationFor(tag)
        case unexpected: AnyRef =>
            log.error("Received unexpected message: {}", unexpected)
        case _ =>
    }
}
