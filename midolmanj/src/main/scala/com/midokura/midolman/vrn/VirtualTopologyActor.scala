/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.vrn

import akka.actor.{ActorRef, Props, Actor}
import java.util.UUID
import collection.mutable

/*
 * VirtualTopologyActor's clients use these messages to request the most recent
 * state of a device and, optionally, notifications when the state changes.
 */
sealed trait DeviceRequest
case class PortRequest(id: UUID, update: Boolean) extends DeviceRequest
case class BridgeRequest(id: UUID, update: Boolean) extends DeviceRequest
case class RouterRequest(id: UUID, update: Boolean) extends DeviceRequest
case class ChainRequest(id: UUID, update: Boolean) extends DeviceRequest

class VirtualTopologyActor extends Actor {
    private val idToBridge = mutable.Map[UUID, Bridge]()
    private val idToChain = mutable.Map[UUID, Chain]()
    private val idToPort = mutable.Map[UUID, Port]()
    private val idToRouter = mutable.Map[UUID, Router]()
    private val idToSubscribers = mutable.Map[UUID, mutable.Set[ActorRef]]()
    private val idToUnansweredClients = mutable.Map[UUID, mutable.Set[ActorRef]]()
    private val managed = mutable.Set[UUID]()

    private def addDeviceClient(map: mutable.Map[UUID, mutable.Set[ActorRef]],
                        id: UUID, client: ActorRef): Unit = {
        val clients = map.get(id)
        clients match {
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

    def receive = {
        case BridgeRequest(id, update) => self.tell()
            getDevice(id, idToBridge, (x: UUID) => new BridgeManager(x), update)
        case ChainRequest(id, update) =>
            getDevice(id, idToChain, (x: UUID) => new ChainManager(x), update)
        case PortRequest(id, update) =>
            getDevice(id, idToPort, (x: UUID) => new PortManager(x), update)
        case RouterRequest(id, update) =>
            getDevice(id, idToRouter, (x: UUID) => new RouterManager(x), update)
        case bridge : Bridge => updated(bridge.id, bridge, idToBridge)
        case chain: Chain => updated(chain.id, chain, idToChain)
        case port: Port => updated(port.id, port, idToPort)
        case router: Router => updated(router.id, router, idToRouter)
    }
}
