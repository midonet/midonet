/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.topology

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import collection.mutable
import java.util.UUID
import javax.inject.Inject

import com.midokura.midolman.Referenceable
import com.midokura.midolman.config.MidolmanConfig
import com.midokura.midolman.guice.ComponentInjectorHolder
import com.midokura.midolman.simulation.{Bridge, Chain, Router}
import com.midokura.midonet.cluster.Client
import com.midokura.midonet.cluster.client.Port
import com.midokura.packets.IntIPv4


object VirtualTopologyActor extends Referenceable {
    val Name: String = "VirtualTopologyActor"

    /*
    * VirtualTopologyActor's clients use these messages to request the most recent
    * state of a device and, optionally, notifications when the state changes.
    */
    sealed trait DeviceRequest

    case class PortRequest(id: UUID, update: Boolean) extends DeviceRequest

    /**
     * This message sent to the VTA requests a port's list of BGPs. The response
     * is a sequence of BGPLink read-copy-update objects. Each BGP object
     * includes the owner port identifier for the convenience of the caller.
     * When a BGPLink on a port is deleted, the VTA will inform the
     * subscriber by sending a BGPLinkDeleted message.
     * @param portID
     * @param update
     */
    case class BGPListRequest(portID: UUID, update: Boolean)

    /**
     * Sent by the VTA to subscribers when a BGP configuration is removed from
     * a port's list of BGP links.
     * @param bgpID
     */
    case class BGPLinkDeleted(bgpID: UUID)

    case class BridgeRequest(id: UUID, update: Boolean) extends DeviceRequest

    case class RouterRequest(id: UUID, update: Boolean) extends DeviceRequest

    case class ChainRequest(id: UUID, update: Boolean) extends DeviceRequest

    // The VPN Actor sends this message to the Virtual Topology Actor to register
    // interest in managing VPNs. The VTA subsequently will try to 'lock' VPNs
    // on behalf of the local host ID and send notifications of any acquired
    // VPN locks to the local VPNManager.
    case class RegisterVPNHandler()

    // The Virtual Topology Actor sends this message to the VPNmanager whenever
    // a lock is acquired/released to manage a VPN.
    case class AcquiredLockOnVPN(vpnID: UUID, acquired: Boolean)

    // Clients send this message to the VTA to requests the configuration
    // of a VPN. Once ready, the VTA sends back a VPN read-copy-update object.
    case class VPNRequest(id: UUID, update: Boolean)

    sealed trait Unsubscribe

    case class BridgeUnsubscribe(id: UUID) extends Unsubscribe

    case class ChainUnsubscribe(id: UUID) extends Unsubscribe

    case class PortUnsubscribe(id: UUID) extends Unsubscribe

    case class BGPListUnsubscribe(portID: UUID) extends Unsubscribe

    case class RouterUnsubscribe(id: UUID) extends Unsubscribe

}

class VirtualTopologyActor() extends Actor with ActorLogging {
    import VirtualTopologyActor._
    // dir: Directory, zkBasePath: String, val hostIp: IntIPv4

    private val idToBridge = mutable.Map[UUID, Bridge]()
    private val idToChain = mutable.Map[UUID, Chain]()
    private val idToPort = mutable.Map[UUID, Port[_]]()
    private val idToRouter = mutable.Map[UUID, Router]()

    // TODO(pino): unload devices with no subscribers that haven't been used
    // TODO:       in a while.
    private val idToSubscribers = mutable.Map[UUID, mutable.Set[ActorRef]]()
    private val idToUnansweredClients = mutable.Map[UUID, mutable.Set[ActorRef]]()
    private val managed = mutable.Set[UUID]()

    @Inject
    var clusterClient: Client = null

    //@Inject
    //var greZK: GreZkManager = null
    @Inject
    var config: MidolmanConfig = null

    override def preStart() {
        super.preStart()
        // XXX TODO(pino): get the local host ID for VPN locking.
        ComponentInjectorHolder.inject(this)

    }

    private def manageDevice(id: UUID, ctr: UUID => Actor): Unit = {
        if (!managed(id)) {
            log.info("Build a manager for this device.")
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
        else {
            log.info("Adding requester to unanswered clients")
            idToUnansweredClients(id).add(sender)
        }
        if (update) {
            log.info("Adding requester to subscribed clients")
            idToSubscribers(id).add(sender)
        }
    }

    private def updated[T](id: UUID, device: T,
                           idToDevice: mutable.Map[UUID, T]): Unit = {
        idToDevice.put(id, device)
        for (client <- idToSubscribers(id)) {
            log.info("Send subscriber the device update.")
            client ! device
        }
        for (client <- idToUnansweredClients(id))
        // Avoid notifying the subscribed clients twice.
            if (!idToSubscribers(id).contains(client)) {
                log.info("Send unanswered client the device update.")
                client ! device
            }
        idToUnansweredClients(id).clear()
    }

    private def unsubscribe(id: UUID, actor: ActorRef): Unit = {
        def remove(setOption: Option[mutable.Set[ActorRef]]) = setOption match {
            case Some(actorSet) => actorSet.remove(actor)
            case None =>;
        }
        remove(idToUnansweredClients.get(id))
        remove(idToSubscribers.get(id))
    }

    private def portMgrCtor =
        (portId: UUID) => new PortManager(portId,
            IntIPv4.fromString(config.getOpenFlowPublicIpAddress), clusterClient)

    def receive = {
        case BridgeRequest(id, update) =>
            log.info("Bridge requested for {} with update={}", id, update)
            manageDevice(id, (x: UUID) => new BridgeManager(x, clusterClient))
            deviceRequested(id, idToBridge, update)
        case ChainRequest(id, update) =>
            log.info("Chain requested for {} with update={}", id, update)
            manageDevice(id, (x: UUID) =>
                new ChainManager(x))
            deviceRequested(id, idToChain, update)
        case PortRequest(id, update) =>
            log.info("Port requested for {} with update={}", id, update)
            manageDevice(id, portMgrCtor)
            deviceRequested(id, idToPort, update)
        case RouterRequest(id, update) =>
            log.info("Router requested for {} with update={}", id, update)
            manageDevice(id, (x: UUID) =>
                new RouterManager(x, clusterClient))
            deviceRequested(id, idToRouter, update)
        case BGPListRequest =>
        case BGPListUnsubscribe =>
        case BridgeUnsubscribe(id) => unsubscribe(id, sender)
        case ChainUnsubscribe(id) => unsubscribe(id, sender)
        case PortUnsubscribe(id) => unsubscribe(id, sender)
        case RouterUnsubscribe(id) => unsubscribe(id, sender)
        case bridge: Bridge =>
            log.info("Received Bridge")
            updated(bridge.id, bridge, idToBridge)
        case chain: Chain => updated(chain.id, chain, idToChain)
        case port: Port[_] => updated(port.id, port, idToPort)
        case router: Router => updated(router.id, router, idToRouter)
    }
}
