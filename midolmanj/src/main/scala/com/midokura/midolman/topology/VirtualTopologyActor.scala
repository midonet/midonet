/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.topology

import collection.mutable
import scala.Some
import akka.actor.{Actor, ActorLogging, ActorRef, Props, SupervisorStrategy}
import java.util.UUID
import com.google.inject.Inject

import com.midokura.midolman.Referenceable
import com.midokura.midolman.config.MidolmanConfig
import com.midokura.midolman.guice.MidolmanActorsModule
import com.midokura.midolman.simulation.{Bridge, Chain, Router}
import com.midokura.midonet.cluster.Client
import com.midokura.midonet.cluster.client.Port

object VirtualTopologyActor extends Referenceable {
    override val Name: String = "VirtualTopologyActor"

    /*
     * VirtualTopologyActor's clients use these messages to request the most
     * recent state of a device and, optionally, notifications when the state
     * changes.
     */
    sealed trait DeviceRequest

    case class PortRequest(id: UUID, update: Boolean) extends DeviceRequest

    case class BridgeRequest(id: UUID, update: Boolean) extends DeviceRequest

    case class RouterRequest(id: UUID, update: Boolean) extends DeviceRequest

    case class ChainRequest(id: UUID, update: Boolean) extends DeviceRequest

    // The VPN Actor sends this message to the Virtual Topology Actor to
    // register VPNs. The VTA subsequently will try to 'lock' VPNs on
    // behalf of the local host ID and send notifications of any acquired
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

    case class RouterUnsubscribe(id: UUID) extends Unsubscribe
}

class VirtualTopologyActor extends Actor with ActorLogging {
    import VirtualTopologyActor._
    // dir: Directory, zkBasePath: String, val hostIp: IntIPv4

    // XXX TODO(pino): get the local host ID for VPN locking.

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
    override val supervisorStrategy: SupervisorStrategy = null

    @Inject
    val clusterClient: Client = null

    //@Inject
    //var greZK: TunnelZkManager = null
    @Inject
    val config: MidolmanConfig = null

    private def manageDevice(id: UUID, ctr: UUID => Actor): Unit = {
        if (!managed(id)) {
            log.info("Build a manager for device {}", id)
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
            log.debug("Adding requester to unanswered clients")
            idToUnansweredClients(id).add(sender)
        }
        if (update) {
            log.debug("Adding requester to subscribed clients")
            idToSubscribers(id).add(sender)
        }
    }

    private def updated[T](id: UUID, device: T,
                           idToDevice: mutable.Map[UUID, T]): Unit = {
        idToDevice.put(id, device)
        for (client <- idToSubscribers(id)) {
            log.debug("Send subscriber the device update for {}", device)
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
    }

    private def unsubscribe(id: UUID, actor: ActorRef): Unit = {
        def remove(setOption: Option[mutable.Set[ActorRef]]) = setOption match {
            case Some(actorSet) => actorSet.remove(actor)
            case None =>;
        }
        remove(idToUnansweredClients.get(id))
        remove(idToSubscribers.get(id))
    }

    def receive = {
        case BridgeRequest(id, update) =>
            log.debug("Bridge {} requested with update={}", id, update)
            manageDevice(id, (x: UUID) => new BridgeManager(x, clusterClient))
            deviceRequested(id, idToBridge, update)
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
        case ChainUnsubscribe(id) => unsubscribe(id, sender)
        case PortUnsubscribe(id) => unsubscribe(id, sender)
        case RouterUnsubscribe(id) => unsubscribe(id, sender)
        case bridge: Bridge =>
            log.debug("Received a Bridge for {}", bridge.id)
            updated(bridge.id, bridge, idToBridge)
        case chain: Chain =>
            log.debug("Received a Chain for {}", chain.id)
            updated(chain.id, chain, idToChain)
        case port: Port[_] =>
            log.debug("Received a Port for {}", port.id)
            updated(port.id, port, idToPort)
        case router: Router =>
            log.debug("Received a Router for {}", router.id)
            updated(router.id, router, idToRouter)
    }
}
