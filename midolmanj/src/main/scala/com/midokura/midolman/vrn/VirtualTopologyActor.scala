/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.vrn

import akka.actor.{ActorRef, Props, Actor}
import java.util.UUID
import collection.mutable
import scala.Some

import com.midokura.midolman.state._

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

class VirtualTopologyActor(dir: Directory, zkBasePath: String) extends Actor {
    private val idToBridge = mutable.Map[UUID, Bridge]()
    private val idToChain = mutable.Map[UUID, Chain]()
    private val idToPort = mutable.Map[UUID, Port]()
    private val idToRouter = mutable.Map[UUID, Router]()
    private val idToSubscribers = mutable.Map[UUID, mutable.Set[ActorRef]]()
    private val idToUnansweredClients = mutable.Map[UUID, mutable.Set[ActorRef]]()
    private val managed = mutable.Set[UUID]()

    private val bridgeStateMgr = new BridgeZkManager(dir, zkBasePath)
    private val chainStateMgr = new ChainZkManager(dir, zkBasePath)
    private val portStateMgr = new PortZkManager(dir, zkBasePath)
    private val routerStateMgr = new RouterZkManager(dir, zkBasePath)
    private val ruleStateMgr = new RuleZkManager(dir, zkBasePath)

    private def addDeviceClient(map: mutable.Map[UUID, mutable.Set[ActorRef]],
                        id: UUID, client: ActorRef): Unit = {
        map.get(id) match {
            case Some(actorSet) => actorSet.add(sender)
            case None => map.put(id, mutable.Set[ActorRef]()+sender)
        }
    }

    private def getDevice[T](id: UUID, idToDevice: mutable.Map[UUID, T],
                     ctr: UUID => Actor, update: Boolean): Unit = {
        if ( !managed(id)) {
            context.actorOf(Props(ctr(id)), name = "manager")
            managed.add(id)
        }
        if (idToDevice.contains(id))
            self.tell(idToDevice(id))
        else
            addDeviceClient(idToUnansweredClients, id, sender)
        if (update)
            addDeviceClient(idToSubscribers, id, sender)
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

    def receive = {
        case BridgeRequest(id, update) =>
            getDevice(id, idToBridge,
                (x: UUID) => new BridgeManager(x, bridgeStateMgr), update)
        case ChainRequest(id, update) =>
            getDevice(id, idToChain,
                (x: UUID) =>
                    new ChainManager(x, chainStateMgr, ruleStateMgr), update)
        case PortRequest(id, update) =>
            getDevice(id, idToPort,
                (x: UUID) => new PortManager(x, portStateMgr), update)
        case RouterRequest(id, update) =>
            getDevice(id, idToRouter,
                (x: UUID) => new RouterManager(x, routerStateMgr), update)
        case BridgeUnsubscribe(id) => unsubscribe(id, sender)
        case ChainUnsubscribe(id) => unsubscribe(id, sender)
        case PortUnsubscribe(id) => unsubscribe(id, sender)
        case RouterUnsubscribe(id) => unsubscribe(id, sender)
        case bridge : Bridge => updated(bridge.id, bridge, idToBridge)
        case chain: Chain => updated(chain.id, chain, idToChain)
        case port: Port => updated(port.id, port, idToPort)
        case router: Router => updated(router.id, router, idToRouter)
    }
}
