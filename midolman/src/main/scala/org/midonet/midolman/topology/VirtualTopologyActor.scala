/*
 * Copyright 2012 Midokura Europe SARL
 */
package org.midonet.midolman.topology

import scala.collection.mutable
import compat.Platform

import java.util.concurrent.TimeoutException
import java.util.UUID

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.reflect._
import scala.util.Failure

import akka.actor._
import akka.event.LoggingAdapter
import akka.pattern.ask

import com.google.inject.Inject

import org.midonet.cluster.Client
import org.midonet.cluster.client.Port
import org.midonet.midolman.FlowController.InvalidateFlowsByTag
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.logging.{SimulationAwareBusLogging, ActorLogWithoutPath}
import org.midonet.midolman.simulation.{PacketContext, Bridge, Chain, Router}
import org.midonet.midolman.{DeduplicationActor, FlowController, Referenceable}
import org.midonet.midolman.topology.rcu.TraceConditions

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
        protected[VirtualTopologyActor]
        def createManager(client: Client, config: MidolmanConfig): Actor
    }

    case class PortRequest(id: UUID, update: Boolean = false)
        extends DeviceRequest[Port[_]] {
        protected[VirtualTopologyActor] val tag = classTag[Port[_]]

        protected[VirtualTopologyActor]
        def createManager(client: Client, config: MidolmanConfig) =
            new PortManager(id, client)
    }

    case class BridgeRequest(id: UUID, update: Boolean = false)
        extends DeviceRequest[Bridge] {
        protected[VirtualTopologyActor] val tag = classTag[Bridge]

        protected[VirtualTopologyActor]
        def createManager(client: Client, config: MidolmanConfig) =
            new BridgeManager(id, client, config)
    }

    case class RouterRequest(id: UUID, update: Boolean = false)
        extends DeviceRequest[Router] {
        protected[VirtualTopologyActor] val tag = classTag[Router]

        protected[VirtualTopologyActor]
        def createManager(client: Client, config: MidolmanConfig) =
            new RouterManager(id, client, config)
    }

    case class ChainRequest(id: UUID, update: Boolean = false)
        extends DeviceRequest[Chain] {
        protected[VirtualTopologyActor]  val tag = classTag[Chain]

        protected[VirtualTopologyActor]
        def createManager(client: Client, config: MidolmanConfig) =
            new ChainManager(id, client)
    }

    case class ConditionListRequest(id: UUID, update: Boolean = false)
        extends DeviceRequest[TraceConditions] {
        protected[VirtualTopologyActor] val tag = classTag[TraceConditions]

        protected[VirtualTopologyActor]
        def createManager(client: Client, config: MidolmanConfig) =
            new TraceConditionsManager(id, client)
    }

    sealed trait Unsubscribe {
        val id: UUID
    }

    case class BridgeUnsubscribe(id: UUID) extends Unsubscribe

    case class ChainUnsubscribe(id: UUID) extends Unsubscribe

    case class ConditionListUnsubscribe(id: UUID) extends Unsubscribe

    case class PortUnsubscribe(id: UUID) extends Unsubscribe

    case class RouterUnsubscribe(id: UUID) extends Unsubscribe

    // This variable should only be updated by the singleton
    // VirtualTopologyInstance. Also, we strongly recommend not accessing
    // it directly. Call VirtualTopologyActor.expiringAsk instead - it
    // first checks the contents of the volatile and only if the requested
    // device is not found, does it perform an 'ask' on the VTA instance.
    @volatile
    var everything: Map[UUID, Any] = _

    // WARNING!! This code is meant to be called from outside the actor.
    // it should only access the volatile variable 'everything'
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

    private[this] def doExpiringAsk[D](request: DeviceRequest[D],
                                       expiry: Long = 0L,
                                       log: LoggingAdapter,
                                       simLog: SimulationAwareBusLogging)
                                      (implicit pktContext: PacketContext,
                                                system: ActorSystem)
    : Future[D] = {
        val timeLeft =
            if (expiry == 0L) 3000L else expiry - Platform.currentTime

        if (timeLeft <= 0)
            return Future.failed(new TimeoutException)

        val e = everything
        if (null == e || request.update)
            return requestFuture(request, timeLeft, log, simLog)

        // Try using the cache
        e.get(request.id) match {
            case Some(d) => Future.successful(d.asInstanceOf[D])
            case None => requestFuture(request, timeLeft, log, simLog)
        }
    }

    private[this] def requestFuture[D](request: DeviceRequest[D],
                                       timeLeft: Long,
                                       log: LoggingAdapter,
                                       simLog: SimulationAwareBusLogging)
                                      (implicit pktContext: PacketContext,
                                                system: ActorSystem) =
        VirtualTopologyActor.getRef(system)
            .ask(request)(timeLeft milliseconds)
            .mapTo[D](request.tag) andThen {
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
            }
}

class VirtualTopologyActor extends Actor with ActorLogWithoutPath {
    import VirtualTopologyActor._

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

    override def preStart() {
        super.preStart()
        everything = Map[UUID, Any]()
    }

    private def manageDevice(id: UUID, actor: => Actor): Unit = {
        if (!managed(id)) {
            log.info("Build a manager for device {}", id)

            context.actorOf(Props(actor).withDispatcher(context.props.dispatcher))
            managed.add(id)
            idToUnansweredClients.put(id, mutable.Set[ActorRef]())
            idToSubscribers.put(id, mutable.Set[ActorRef]())
        }
    }

    private def deviceRequested(req: DeviceRequest[_]) {
        everything.get(req.id) match {
            case Some(dev) => sender ! dev
            case None =>
                log.debug("Adding requester to unanswered clients")
                idToUnansweredClients(req.id).add(sender)
        }

        if (req.update) {
            log.debug("Adding requester {} to subscribed clients for {}",
                      sender, req.id)
            idToSubscribers(req.id).add(sender)
        }
    }

    private def updated[D <: {val id: UUID}](device: D) {
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
                log.debug("Send unanswered client the device update for {}",
                    device)
                client ! device
            }
        }
        idToUnansweredClients(id).clear()
        everything = everything.updated(id, device)
    }

    private def unsubscribe(id: UUID, actor: ActorRef): Unit = {
        def remove(setOption: Option[mutable.Set[ActorRef]]) = setOption match {
            case Some(actorSet) => actorSet.remove(actor)
            case None =>
        }
        remove(idToUnansweredClients.get(id))
        remove(idToSubscribers.get(id))
    }

    def receive = {
        case r: DeviceRequest[_] =>
            log.debug("Received {} for {} with update={}",
                r.getClass.getSimpleName, r.id, r.update)
            manageDevice(r.id, r.createManager(clusterClient, config))
            deviceRequested(r)
        case u: Unsubscribe => unsubscribe(u.id, sender)
        case bridge: Bridge =>
            log.debug("Received a Bridge for {}", bridge.id)
            updated(bridge)
        case chain: Chain =>
            log.debug("Received a Chain for {}", chain.id)
            updated(chain.id, chain)
        case port: Port[_] =>
            log.debug("Received a Port for {}", port.id)
            updated(port.id, port)
        case router: Router =>
            log.debug("Received a Router for {}", router.id)
            updated(router)
        case TraceConditions(conditions) =>
            log.debug("TraceConditions updated to {}", conditions)
            updated(TraceConditionsManager.uuid, conditions)
            // We know the DDA should always get an update to the trace
            // conditions.  For some reason the ChainRequest(update=true)
            // message from the DDA doesn't get the sender properly set.
            DeduplicationActor.getRef() ! conditions
        case invalidation: InvalidateFlowsByTag =>
            FlowController.getRef() ! invalidation
    }
}
