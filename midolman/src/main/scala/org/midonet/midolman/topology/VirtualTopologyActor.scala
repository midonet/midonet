/*
 * Copyright (c) 2013 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.midolman.topology

import java.util.concurrent.TimeoutException
import java.util.UUID

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.reflect._
import scala.util.Failure

import akka.actor._
import akka.event.LoggingAdapter

import com.google.inject.Inject

import compat.Platform

import org.midonet.cluster.Client
import org.midonet.cluster.client.Port
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.logging.{SimulationAwareBusLogging, ActorLogWithoutPath}
import org.midonet.midolman.{DeduplicationActor, FlowController, Referenceable}
import org.midonet.midolman.FlowController.InvalidateFlowsByTag
import org.midonet.midolman.simulation._
import org.midonet.midolman.topology.rcu.TraceConditions
import org.midonet.util.concurrent._

object VirtualTopologyActor extends Referenceable {
    override val Name: String = "VirtualTopologyActor"

    /*
     * VirtualTopologyActor's clients use these messages to request the most
     * recent state of a device and, optionally, notifications when the state
     * changes.
     */
    sealed trait DeviceRequest[D] {
        val id: UUID
        val update: Boolean

        protected[VirtualTopologyActor] val tag: ClassTag[D]
        protected[VirtualTopologyActor] val managerName: String

        override def toString =
            s"${getClass.getSimpleName}[id=$id, update=$update]"

        protected[VirtualTopologyActor]
        def managerFactory(client: Client, config: MidolmanConfig): () => Actor
    }

    case class PortRequest(id: UUID, update: Boolean = false)
            extends DeviceRequest[Port] {
        protected[VirtualTopologyActor] val tag = classTag[Port]

        protected[VirtualTopologyActor]
        override val managerName = "PortManager-" + id

        protected[VirtualTopologyActor]
        override def managerFactory(client: Client, config: MidolmanConfig) =
            () => new PortManager(id, client)
    }

    case class BridgeRequest(id: UUID, update: Boolean = false)
            extends DeviceRequest[Bridge] {
        protected[VirtualTopologyActor] val tag = classTag[Bridge]

        protected[VirtualTopologyActor]
        override val managerName = "BridgeManager-" + id

        protected[VirtualTopologyActor]
        override def managerFactory(client: Client, config: MidolmanConfig) =
            () => new BridgeManager(id, client, config)
    }

    case class RouterRequest(id: UUID, update: Boolean = false)
        extends DeviceRequest[Router] {
        protected[VirtualTopologyActor] val tag = classTag[Router]

        protected[VirtualTopologyActor]
        override val managerName = "RouterManager-" + id

        protected[VirtualTopologyActor]
        def managerFactory(client: Client, config: MidolmanConfig) =
            () => new RouterManager(id, client, config)
    }

    case class ChainRequest(id: UUID, update: Boolean = false)
        extends DeviceRequest[Chain] {
        protected[VirtualTopologyActor] val tag = classTag[Chain]

        protected[VirtualTopologyActor]
        override val managerName = "ChainManager-" + id

        protected[VirtualTopologyActor]
        def managerFactory(client: Client, config: MidolmanConfig) =
            () => new ChainManager(id, client)
    }

    case class IPAddrGroupRequest(id: UUID, update: Boolean = false)
            extends DeviceRequest[IPAddrGroup] {
        protected[VirtualTopologyActor] val tag = classTag[IPAddrGroup]

        protected[VirtualTopologyActor]
        override val managerName = "IPAddrGroupManager-" + id

        protected[VirtualTopologyActor]
        def managerFactory(client: Client, config: MidolmanConfig) =
            () => new IPAddrGroupManager(id, client)
    }

    case class LoadBalancerRequest(id: UUID, update: Boolean = false)
        extends DeviceRequest[LoadBalancer] {
        protected[VirtualTopologyActor] val tag = classTag[LoadBalancer]

        protected[VirtualTopologyActor]
        override val managerName = "LoadBalancerManager-" + id

        protected[VirtualTopologyActor]
        def managerFactory(client: Client, config: MidolmanConfig) =
            () => new LoadBalancerManager(id, client)
    }

    case class PoolRequest(id: UUID, update: Boolean = false)
        extends DeviceRequest[Pool] {
        protected[VirtualTopologyActor] val tag = classTag[Pool]

        protected[VirtualTopologyActor]
        override val managerName = "PoolManager-" + id

        protected[VirtualTopologyActor]
        def managerFactory(client: Client, config: MidolmanConfig) =
            () => new PoolManager(id, client)
    }

    case class ConditionListRequest(id: UUID, update: Boolean = false)
        extends DeviceRequest[TraceConditions] {
        protected[VirtualTopologyActor] val tag = classTag[TraceConditions]

        protected[VirtualTopologyActor]
        override val managerName = "ConditionListManager-" + id

        protected[VirtualTopologyActor]
        def managerFactory(client: Client, config: MidolmanConfig) =
            () => new TraceConditionsManager(id, client)
    }

    case class Unsubscribe(id: UUID)

    @volatile private var topology = Topology()

    // WARNING!! This code is meant to be called from outside the actor.
    // it should only access the volatile variable 'topology'
    def expiringAsk[D](request: DeviceRequest[D])
                      (implicit system: ActorSystem) =
        doExpiringAsk(request, 0L, null, null)(null, system)

    def expiringAsk[D](request: DeviceRequest[D], expiry: Long)
                      (implicit system: ActorSystem) =
        doExpiringAsk(request, expiry, null, null)(null, system)

    def expiringAsk[D](request: DeviceRequest[D],
                       log: LoggingAdapter)
                      (implicit system: ActorSystem) =
        doExpiringAsk(request, 0L, log, null)(null, system)

    def expiringAsk[D](request: DeviceRequest[D],
                       log: LoggingAdapter,
                       expiry: Long)
                      (implicit system: ActorSystem) =
        doExpiringAsk(request, expiry, log, null)(null, system)

    def expiringAsk[D](request: DeviceRequest[D],
                       simLog: SimulationAwareBusLogging,
                       expiry: Long = 0L)
                      (implicit system: ActorSystem,
                                pktContext: PacketContext) =
        doExpiringAsk(request, expiry, null, simLog)(pktContext, system)

    /* Note that the expiry is the exact expiration time, not a time left.
     */
    private[this] def doExpiringAsk[D](request: DeviceRequest[D],
                                       expiry: Long = 0L,
                                       log: LoggingAdapter,
                                       simLog: SimulationAwareBusLogging)
                                      (implicit pktContext: PacketContext,
                                                system: ActorSystem)
    : Future[D] = {
        val timeLeft = if (expiry == 0L) 3000L
                       else expiry - Platform.currentTime
        if (timeLeft <= 0)
            return Future.failed(new TimeoutException)

        if (request.update)
            throw new IllegalArgumentException("Do not use this API for " +
                                               "subscribing to requests")

        topology.device[D](request.id) match {
            case Some(dev) => Future.successful(dev)
            case None => requestFuture(request, timeLeft, log, simLog)
        }
    }

    private[this] def requestFuture[D](request: DeviceRequest[D],
                                       timeLeft: Long,
                                       log: LoggingAdapter,
                                       simLog: SimulationAwareBusLogging)
                                      (implicit pktContext: PacketContext,
                                                system: ActorSystem) =
        VirtualTopologyActor
            .ask(request)(timeLeft milliseconds)
            .mapTo[D](request.tag).andThen {
                case Failure(ex: ClassCastException) =>
                    if (log != null)
                        log.error("VirtualTopologyManager didn't return a {}!",
                            request.tag.runtimeClass.getSimpleName)
                    else if (simLog != null)
                        simLog.error("VirtualTopologyManager didn't return a {}!",
                            request.tag.runtimeClass.getSimpleName)
                case Failure(ex) =>
                    if (log != null)
                        log.warning("Failed to get {}: {} - {}",
                            request.tag.runtimeClass.getSimpleName, request.id, ex)
                    else if (simLog != null)
                        simLog.warning("Failed to get {}: {} - {}",
                            request.tag.runtimeClass.getSimpleName, request.id, ex)
            }(ExecutionContext.callingThread)
}

class VirtualTopologyActor extends Actor with ActorLogWithoutPath {
    import VirtualTopologyActor._
    import context.system

    // TODO(pino): unload devices with no subscribers that haven't been used
    // TODO:       in a while.
    private val idToSubscribers = mutable.Map[UUID, mutable.Set[ActorRef]]()
    private val idToUnansweredClients = mutable.Map[UUID, mutable.Set[ActorRef]]()
    private val managed = mutable.Set[UUID]()

    @Inject
    override val supervisorStrategy: SupervisorStrategy = null

    @Inject
    val clusterClient: Client = null

    @Inject
    val config: MidolmanConfig = null

    private def manageDevice(r: DeviceRequest[_]): Unit = {
        if (managed(r.id))
            return

        log.info("Build a manager for {}", r)

        val mgrFactory = r.managerFactory(clusterClient, config)
        val props = Props(mgrFactory).withDispatcher(context.props.dispatcher)
        context.actorOf(props, r.managerName)

        managed.add(r.id)
        idToUnansweredClients.put(r.id, mutable.Set[ActorRef]())
        idToSubscribers.put(r.id, mutable.Set[ActorRef]())
    }

    private def deviceRequested(req: DeviceRequest[_]) {
        topology.get(req.id) match {
            case Some(dev) => sender ! dev
            case None =>
                log.debug("Adding requester {} to unanswered clients for {}",
                          sender, req)
                idToUnansweredClients(req.id).add(sender)
        }

        if (req.update) {
            log.debug("Adding requester {} to subscribed clients for {}",
                      sender, req)
            idToSubscribers(req.id).add(sender)
        }
    }

    private def updated[D <: {def id: UUID}](device: D) {
        updated(device.id, device)
    }

    private def updated(id: UUID, device: Any) {
        for (client <- idToSubscribers(id)) {
            log.debug("Sending subscriber {} the device update for {}",
                      client, device)
            client ! device
        }
        for (client <- idToUnansweredClients(id)) {
            // Avoid notifying the subscribed clients twice.
            if (!idToSubscribers(id).contains(client)) {
                log.debug("Send unanswered client {} the device update for {}",
                          client, device)
                client ! device
            }
        }
        idToUnansweredClients(id).clear()
        topology += id -> device
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
        case r: DeviceRequest[_] =>
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
        case traceCondition @ TraceConditions(conditions) =>
            log.debug("TraceConditions updated to {}", conditions)
            updated(TraceConditionsManager.uuid, conditions)
            // We know the DDA should always get an update to the trace
            // conditions.  For some reason the ChainRequest(update=true)
            // message from the DDA doesn't get the sender properly set.
            DeduplicationActor ! traceCondition
        case invalidation: InvalidateFlowsByTag =>
            log.debug("Invalidating flows for tag {}", invalidation.tag)
            FlowController ! invalidation
    }
}
