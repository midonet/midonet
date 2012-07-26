/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.vrn

import akka.actor.{Props, ActorRef, Actor}
import java.util.UUID
import collection.mutable
import scala.Some

import com.midokura.midolman.state._
import com.midokura.packets.IntIPv4

/*
 * VirtualTopologyActor's clients use these messages to request the most recent
 * state of a device and, optionally, notifications when the state changes.
 */
sealed trait DeviceRequest
case class PortRequest(id: UUID, update: Boolean) extends DeviceRequest
case class BridgeRequest(id: UUID, update: Boolean) extends DeviceRequest
case class RouterRequest(id: UUID, update: Boolean) extends DeviceRequest
case class ChainRequest(id: UUID, update: Boolean) extends DeviceRequest

sealed trait Unsubscribe
case class BridgeUnsubscribe(id: UUID) extends Unsubscribe
case class ChainUnsubscribe(id: UUID) extends Unsubscribe
case class PortUnsubscribe(id: UUID) extends Unsubscribe
case class RouterUnsubscribe(id: UUID) extends Unsubscribe

case class SetPortLocal(id: UUID, local: Boolean)
// These types are used to inform the device that a port is local
case class SetBridgePortLocal(devId: UUID, portId: UUID, local: Boolean)
case class SetRouterPortLocal(devId: UUID, portId: UUID, local: Boolean)

object VirtualTopologyActor {
    val Name:String = "VirtualTopologyActor"
}

class VirtualTopologyActor(dir: Directory, zkBasePath: String,
                           val hostIp: IntIPv4) extends Actor {
    private val idToBridge = mutable.Map[UUID, Bridge]()
    private val idToChain = mutable.Map[UUID, Chain]()
    private val idToPort = mutable.Map[UUID, Port]()
    private val idToRouter = mutable.Map[UUID, Router]()
    // TODO(pino): unload devices with no subscribers that haven't been used
    // TODO:       in a while.
    private val idToSubscribers = mutable.Map[UUID, mutable.Set[ActorRef]]()
    private val idToUnansweredClients = mutable.Map[UUID, mutable.Set[ActorRef]]()
    private val managed = mutable.Set[UUID]()
    // TODO(pino): use localPorts to avoid unloading local ports that have
    // TODO:       no subscribers and haven't been used in a while.
    private val localPorts = mutable.Set[UUID]()

    private val bridgeStateMgr = new BridgeZkManager(dir, zkBasePath)
    private val chainStateMgr = new ChainZkManager(dir, zkBasePath)
    private val portStateMgr = new PortZkManager(dir, zkBasePath)
    private val routerStateMgr = new RouterZkManager(dir, zkBasePath)
    private val routeStateMgr = new RouteZkManager(dir, zkBasePath)
    private val ruleStateMgr = new RuleZkManager(dir, zkBasePath)

    private def manageDevice(id: UUID, ctr: UUID => Actor): Unit = {
        if (!managed(id)) {
            context.actorOf(Props(ctr(id)), name = id.toString())
            managed.add(id)
            idToUnansweredClients.put(id, mutable.Set[ActorRef]())
            idToSubscribers.put(id, mutable.Set[ActorRef]())
        }
    }

    private def deviceRequested[T](id: UUID, idToDevice: mutable.Map[UUID, T],
                                   update: Boolean): Unit = {
        if (idToDevice.contains(id))
            sender.tell(idToDevice(id))
        else
            idToUnansweredClients(id).add(sender)
        if (update)
            idToSubscribers(id).add(sender)
    }

    private def updated[T](id: UUID, device: T,
                           idToDevice: mutable.Map[UUID, T]): Unit = {
        idToDevice.put(id, device)
        for (client <- idToSubscribers(id))
            client != device
        for (client <- idToUnansweredClients(id))
            // Avoid notifying the subscribed clients twice.
            if (!idToSubscribers(id).contains(client))
                client ! device
        idToUnansweredClients(id).clear()
    }

    private def unsubscribe(id: UUID, actor: ActorRef): Unit = {
        def remove(setOption: Option[mutable.Set[ActorRef]]) = setOption match {
            case Some(actorSet) => actorSet.remove(actor)
            case None => ;
        }
        remove(idToUnansweredClients.get(id))
        remove(idToSubscribers.get(id))
    }

    private def portMgrCtor =
        (portId: UUID) => new PortManager(portId, portStateMgr, hostIp)

    def receive = {
        case BridgeRequest(id, update) =>
            manageDevice(id, (x: UUID) => new BridgeManager(x, bridgeStateMgr))
            deviceRequested(id, idToBridge, update)
        case ChainRequest(id, update) =>
            manageDevice(id, (x: UUID) =>
                new ChainManager(x, chainStateMgr, ruleStateMgr))
            deviceRequested(id, idToChain, update)
        case PortRequest(id, update) =>
            manageDevice(id, portMgrCtor)
            deviceRequested(id, idToPort, update)
        case RouterRequest(id, update) =>
            manageDevice(id, (x: UUID) =>
                new RouterManager(x, routerStateMgr, routeStateMgr))
            deviceRequested(id, idToRouter, update)
        case BridgeUnsubscribe(id) => unsubscribe(id, sender)
        case ChainUnsubscribe(id) => unsubscribe(id, sender)
        case PortUnsubscribe(id) => unsubscribe(id, sender)
        case RouterUnsubscribe(id) => unsubscribe(id, sender)
        case bridge : Bridge => updated(bridge.id, bridge, idToBridge)
        case chain: Chain => updated(chain.id, chain, idToChain)
        case port: Port => updated(port.id, port, idToPort)
        case router: Router => updated(router.id, router, idToRouter)
        case portLocalMsg: SetPortLocal =>
            if (localPorts(portLocalMsg.id) != portLocalMsg.local) {
                if (portLocalMsg.local)
                    localPorts.add(portLocalMsg.id)
                else
                    localPorts.remove(portLocalMsg.id)
                manageDevice(portLocalMsg.id, portMgrCtor)
                context.actorFor("./" + portLocalMsg.id.toString())
                    .forward(portLocalMsg)
            }
        case brPortLocalMsg: SetBridgePortLocal =>
            manageDevice(brPortLocalMsg.devId,
                (x: UUID) => new BridgeManager(x, bridgeStateMgr))
            context.actorFor("./" + brPortLocalMsg.devId.toString())
                .forward(brPortLocalMsg)
        case rtrPortLocalMsg: SetRouterPortLocal =>
            manageDevice(rtrPortLocalMsg.devId,
                (x: UUID) =>
                    new RouterManager(x, routerStateMgr, routeStateMgr))
            context.actorFor("./" + rtrPortLocalMsg.devId.toString())
                .forward(rtrPortLocalMsg)
    }
}
