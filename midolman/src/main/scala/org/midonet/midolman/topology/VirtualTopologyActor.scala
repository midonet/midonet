/*
 * Copyright 2012 Midokura Europe SARL
 */
package org.midonet.midolman.topology

import collection.JavaConverters._
import collection.mutable
import collection.immutable
import akka.actor._
import akka.pattern.ask
import akka.util.duration._
import java.util.UUID
import com.google.inject.Inject

import org.midonet.midolman.{DeduplicationActor, FlowController, Referenceable}
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.FlowController.InvalidateFlowsByTag
import org.midonet.midolman.rules.Condition
import org.midonet.midolman.simulation.{Bridge, Chain, Router}
import org.midonet.cluster.Client
import org.midonet.cluster.client.Port
import org.midonet.midolman.logging.ActorLogWithoutPath
import akka.dispatch.{Promise, Future, ExecutionContext}
import compat.Platform
import java.util.concurrent.TimeoutException
import org.midonet.midolman.simulation.VlanAwareBridge

object VirtualTopologyActor extends Referenceable {
    override val Name: String = "VirtualTopologyActor"

    /*
     * VirtualTopologyActor's clients use these messages to request the most
     * recent state of a device and, optionally, notifications when the state
     * changes.
     */
    sealed trait DeviceRequest {
        def id: UUID
        def update: Boolean
    }


    case class PortRequest(id: UUID, update: Boolean) extends DeviceRequest

    case class BridgeRequest(id: UUID, update: Boolean) extends DeviceRequest

    case class VlanBridgeRequest(id: UUID, update: Boolean) extends DeviceRequest

    case class PortSetHolderRequest(id: UUID, update: Boolean) extends DeviceRequest

    case class RouterRequest(id: UUID, update: Boolean) extends DeviceRequest

    case class ChainRequest(id: UUID, update: Boolean) extends DeviceRequest

    case class ConditionListRequest(id: UUID, update: Boolean) extends DeviceRequest

    sealed trait Unsubscribe

    case class VlanBridgeUnsubscribe(id: UUID) extends Unsubscribe

    case class BridgeUnsubscribe(id: UUID) extends Unsubscribe

    case class ChainUnsubscribe(id: UUID) extends Unsubscribe

    case class ConditionListUnsubscribe(id: UUID) extends Unsubscribe

    case class PortUnsubscribe(id: UUID) extends Unsubscribe

    case class RouterUnsubscribe(id: UUID) extends Unsubscribe

    case class Everything(idToBridge: immutable.Map[UUID, Bridge],
                          idToVlanBridge: immutable.Map[UUID, VlanAwareBridge],
                          idToChain: immutable.Map[UUID, Chain],
                          idToPort: immutable.Map[UUID, Port[_]],
                          idToRouter: immutable.Map[UUID, Router],
                          idToConditionList:
                              immutable.Map[UUID, immutable.Seq[Condition]])

    // This variable should only be updated by the singleton
    // VirtualTopologyInstance. Also, we strongly recommend not accessing
    // it directly. Call VirtualTopologyActor.expiringAsk instead - it
    // first checks the contents of the volatile and only if the requested
    // device is not found, does it perform an 'ask' on the VTA instance.
    @volatile
    var everything: Everything = null

    // WARNING!! This code is meant to be called from outside the actor.
    // it should only access the volatile variable 'everything'
    def expiringAsk(request: DeviceRequest, expiry: Long = 0L)
                   (implicit ec: ExecutionContext,
                    system: ActorSystem): Future[Any] = {
        val timeLeft = expiry match {
            case 0L => 3000L
            case ex => ex - Platform.currentTime
        }

        if (timeLeft <= 0)
            return Promise.failed(new TimeoutException)
        val e = everything
        if (null == e || request.update)
            return VirtualTopologyActor.getRef(system)
                .ask(request)(timeLeft milliseconds)

        // Try using the cache
        val deviceMap: immutable.Map[UUID, Any] = request match {
            case r: VlanBridgeRequest => e.idToVlanBridge
            case r: BridgeRequest => e.idToBridge
            case r: ChainRequest => e.idToChain
            case r: ConditionListRequest => e.idToConditionList
            case r: PortRequest => e.idToPort
            case r: RouterRequest => e.idToRouter
            case r: PortSetHolderRequest =>
                if (e.idToBridge.contains(request.id)) e.idToBridge
                else if (e.idToVlanBridge.contains(request.id)) e.idToVlanBridge
                else new immutable.HashMap[UUID, Any]
        }

        deviceMap.get(request.id) match {
            case None => VirtualTopologyActor.getRef(system)
                        .ask(request)(timeLeft milliseconds)
            case Some(device) => Promise.successful(device)
        }
    }
}

class VirtualTopologyActor extends Actor with ActorLogWithoutPath {
    import VirtualTopologyActor._

    private var idToBridge = immutable.Map[UUID, Bridge]()
    private var idToVlanBridge = immutable.Map[UUID, VlanAwareBridge]()
    private var idToChain = immutable.Map[UUID, Chain]()
    private var idToPort = immutable.Map[UUID, Port[_]]()
    private var idToRouter = immutable.Map[UUID, Router]()
    private var traceConditions = immutable.Seq[Condition]()
    private var idToTraceConditions =
                        immutable.Map[UUID, immutable.Seq[Condition]](
                            TraceConditionsManager.uuid -> traceConditions)

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
        everything = null
    }

    private def manageDevice(id: UUID, ctr: UUID => Actor): Unit = {
        if (!managed(id)) {
            log.info("Build a manager for device {}", id)
            context.actorOf(Props(ctr(id)), name = id.toString)
            managed.add(id)
            idToUnansweredClients.put(id, mutable.Set[ActorRef]())
            idToSubscribers.put(id, mutable.Set[ActorRef]())
        }
    }

    private def deviceRequested(id: UUID,
                                idToDevice: immutable.Map[UUID, Any],
                                update: Boolean) {
        if (idToDevice.contains(id))
            sender.tell(idToDevice(id))
        else {
            log.debug("Adding requester to unanswered clients")
            idToUnansweredClients(id).add(sender)
        }
        if (update) {
            log.debug("Adding requester {} to subscribed clients for {}",
                      sender, id)
            idToSubscribers(id).add(sender)
        }
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
        everything = Everything(idToBridge, idToVlanBridge, idToChain,
                                idToPort, idToRouter, idToTraceConditions)
    }

    private def unsubscribe(id: UUID, actor: ActorRef): Unit = {
        def remove(setOption: Option[mutable.Set[ActorRef]]) = setOption match {
            case Some(actorSet) => actorSet.remove(actor)
            case None =>
        }
        remove(idToUnansweredClients.get(id))
        remove(idToSubscribers.get(id))
    }

    private def serveBridgeRequest(id: UUID, update: Boolean) {
        log.debug("Bridge {} requested with update={}", id, update)
        manageDevice(id, (x: UUID) =>
                          new BridgeManager(x, clusterClient, config))
        deviceRequested(id, idToBridge, update)
    }

    private def serveVlanBridgeRequest(id: UUID, update: Boolean) {
        log.debug("VlanBridge {} requested with update={}", id, update)
        manageDevice(id, (x: UUID) =>
                          new VlanAwareBridgeManager(x, clusterClient, config))
        deviceRequested(id, idToVlanBridge, update)
    }

    def receive = {

        case PortSetHolderRequest(id, update) =>
            if (idToBridge.contains(id)) {
                log.debug("PortSetHolderRequest for ye olde bridge")
                serveBridgeRequest(id, update)
            } else if (idToVlanBridge.contains(id)) {
                log.debug("PortSetHolderRequest for vlan-bridge")
                serveVlanBridgeRequest(id, update)
            } else {
                log.warning("PortSetHolderRequest with unknown device {}", id)
            }
        case VlanBridgeRequest(id, update) => serveVlanBridgeRequest(id, update)
        case BridgeRequest(id, update) => serveBridgeRequest(id, update)
        case ConditionListRequest(id, update) =>
            log.debug("ConditionList {} requested with update={}", id, update)
            manageDevice(id, (x: UUID) =>
                                 new TraceConditionsManager(x, clusterClient))
            deviceRequested(id, idToTraceConditions, update)
        case ChainRequest(id, update) =>
            log.debug("Chain {} requested with update={}", id, update)
            manageDevice(id, (x: UUID) => new ChainManager(x, clusterClient))
            deviceRequested(id, idToChain, update)
        case PortRequest(id, update) =>
            log.debug("Port {} requested with update={}", id, update)
            manageDevice(id, (x: UUID) => new PortManager(x, clusterClient))
            deviceRequested(id, idToPort, update)
        case RouterRequest(id, update) =>
            log.debug("Router {} requested with update={}", id, update)
            manageDevice(id,
                (x: UUID) => new RouterManager(x, clusterClient, config))
            deviceRequested(id, idToRouter, update)
        case BridgeUnsubscribe(id) => unsubscribe(id, sender)
        case VlanBridgeUnsubscribe(id) => unsubscribe(id, sender)
        case ChainUnsubscribe(id) => unsubscribe(id, sender)
        case PortUnsubscribe(id) => unsubscribe(id, sender)
        case RouterUnsubscribe(id) => unsubscribe(id, sender)
        case vlanBridge: VlanAwareBridge =>
            log.debug("Received a Vlan Bridge for {}", vlanBridge.id)
            idToVlanBridge = idToVlanBridge.+((vlanBridge.id, vlanBridge))
            updated(vlanBridge.id, vlanBridge)
        case bridge: Bridge =>
            log.debug("Received a Bridge for {}", bridge.id)
            idToBridge = idToBridge.+((bridge.id, bridge))
            updated(bridge.id, bridge)
        case chain: Chain =>
            log.debug("Received a Chain for {}", chain.id)
            idToChain = idToChain.+((chain.id, chain))
            updated(chain.id, chain)
        case port: Port[_] =>
            log.debug("Received a Port for {}", port.id)
            idToPort = idToPort.+((port.id, port))
            updated(port.id, port)
        case router: Router =>
            log.debug("Received a Router for {}", router.id)
            idToRouter = idToRouter.+((router.id, router))
            updated(router.id, router)
        case TraceConditionsManager.TriggerUpdate(conditions) =>
            log.debug("TraceConditions updated to {}", conditions)
            traceConditions = conditions.asScala.toList
            idToTraceConditions = immutable.Map[UUID, immutable.Seq[Condition]](
                TraceConditionsManager.uuid -> traceConditions)
            updated(TraceConditionsManager.uuid, traceConditions)
            // We know the DDA should always get an update to the trace
            // conditions.  For some reason the ChainRequest(update=true)
            // message from the DDA doesn't get the sender properly set.
            DeduplicationActor.getRef().tell(traceConditions)
        case invalidation: InvalidateFlowsByTag =>
            FlowController.getRef() ! invalidation
    }
}
