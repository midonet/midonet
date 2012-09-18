// Copyright 2012 Midokura Inc.

package com.midokura.midolman.simulation

import collection.mutable
import collection.JavaConversions._
import compat.Platform
import java.util.concurrent.TimeoutException
import java.util.UUID

import akka.actor.{ActorRef, ActorSystem}
import akka.dispatch.{Future, ExecutionContext, Promise}
import akka.pattern.ask
import akka.util.duration._

import com.midokura.midolman.{DatapathController, FlowController}
import com.midokura.midolman.FlowController.{AddWildcardFlow, DiscardPacket,
    SendPacket}
import com.midokura.midolman.datapath.{FlowActionOutputToVrnPort,
                                       FlowActionOutputToVrnPortSet}
import com.midokura.midolman.rules.RuleResult.{Action => RuleAction}
import com.midokura.midolman.topology._
import com.midokura.midolman.topology.VirtualTopologyActor.{BridgeRequest,
    ChainRequest, RouterRequest, PortRequest}
import com.midokura.midonet.cluster.client._
import com.midokura.packets.{Ethernet, TCP, UDP}
import com.midokura.sdn.dp.flows._
import com.midokura.sdn.flows.{WildcardFlow, WildcardMatch}


object Coordinator {
    trait Action

    // ErrorDropAction is used to signal that the Drop is being requested
    // because of an error, not because the virtual topology justifies it.
    // The resulting Drop rule may be temporary to allow retrying.
    case class ErrorDropAction() extends Action
    case class DropAction() extends Action
    // NotIPv4Action implies a DROP flow. However, it differs from DropAction
    // in that the installed flow match can have all fields >L2 wildcarded.
    // TODO(pino): make the installed flow computation smarter so that it
    // TODO:       wildcards any field that wasn't used by the simulation. Then
    // TODO:       remove NotIPv4Action
    case class NotIPv4Action() extends Action
    case class ConsumedAction() extends Action
    trait ForwardAction extends Action
    case class ToPortAction(outPort: UUID) extends ForwardAction
    case class ToPortSetAction(portSetID: UUID) extends ForwardAction

    trait Device {
        /**
         * Process a packet described by the given match object. Note that the
         * Ethernet packet is the one originally ingressed the virtual network
         * - it does not reflect the changes made by other devices' handling of
         * the packet (whereas the match object does).
         *
         * @param pktMatch The wildcard match that describes the packet's
         * fields at the time it ingresses the device. This match contains the
         * UUID of the ingress port; it can be accessed via getInputPortUUID.
         * @param packet The original packet that ingressed the virtual network,
         * which may be different from the packet that actually arrives at this
         * device.
         * @param pktContext The context for the simulation of this packet's
         * traversal of the virtual network. Use the context to subscribe
         * for notifications on the removal of any resulting flows, or to tag
         * any resulting flows for indexing.
         * @param expiry The expiration time for processing this packet, i.e.
         * terminate processing when Platform.currentTime >= expiry.
         * @param ec the Coordinator actor's execution context.
         * @return An instance of Action that reflects what the device would do
         * after handling this packet (e.g. drop it, consume it, forward it).
         */
        def process(pktMatch: WildcardMatch, packet: Ethernet,
                    pktContext: PacketContext, expiry: Long)
                   (implicit ec: ExecutionContext,
                    actorSystem: ActorSystem): Future[Action]
    }

    def expiringAsk(actor: ActorRef, message: Any, expiry: Long)
                   (implicit ec: ExecutionContext): Future[Any] = {
        val timeLeft = expiry - Platform.currentTime
        if (timeLeft <= 0)
            Promise.failed(new TimeoutException)
        else
            actor.ask(message)(timeLeft milliseconds)
    }
}

/**
 * Coordinator object to simulate one packet traversing the virtual network.
 *
 * @param origMatch
 * @param origEthernetPkt
 * @param cookie
 * @param generatedPacketEgressPort Only used if this packet was generated
 *                                  by a virtual device. It's the ID of
 *                                  the port via which the packet
 *                                  egresses the device.
 * @param ec
 * @param actorSystem
 */
class Coordinator(val origMatch: WildcardMatch,
                  val origEthernetPkt: Ethernet,
                  val cookie: Option[Int],
                  val generatedPacketEgressPort: Option[UUID],
                  val expiry: Long)
                 (implicit val ec: ExecutionContext,
                  val actorSystem: ActorSystem) {
    import Coordinator._
    val log = akka.event.Logging(actorSystem, "Coordinator.simulate")
    val datapathController = DatapathController.getRef(actorSystem)
    val flowController = FlowController.getRef(actorSystem)
    val virtualTopologyManager = VirtualTopologyActor.getRef(actorSystem)
    val TEMPORARY_DROP_MILLIS = 5 * 1000
    val IDLE_EXPIRATION_MILLIS = 60 * 1000
    val MAX_DEVICES_TRAVERSED = 12

    // Used to detect loops: devices simulated (with duplicates).
    var numDevicesSimulated = 0
    val devicesSimulated = mutable.Map[UUID, Int]()
    val modifMatch = origMatch.clone()
    val modifEthPkt = Ethernet.deserialize(origEthernetPkt.serialize())
    val pktContext = new PacketContext(cookie)

    private def dropFlow(temporary: Boolean) {
        // If the packet is from the datapath, install a temporary Drop flow.
        // Note: a flow with no actions drops matching packets.
        cookie match {
            case Some(_) =>
                val hardExp = if (temporary) TEMPORARY_DROP_MILLIS else 0
                datapathController.tell(
                    AddWildcardFlow(
                        new WildcardFlow()
                            .setHardExpirationMillis(hardExp)
                            .setMatch(origMatch),
                        cookie, null, null, null))
            case None => // Internally-generated packet. Do nothing.
        }
    }

    /**
     * Simulate the packet moving through the virtual topology. The packet
     * begins its journey through the virtual topology in one of these ways:
     * 1) it ingresses an exterior port of a virtual device (in which case the
     * packet arrives via the datapath switch from an entity outside the
     * virtual topology).
     * 2) it egresses an interior port of a virtual device (in which case the
     * packet was generated by that virtual device).
     *
     * In case 1, the match object for the packet was computed by the
     * FlowController and must contain an inPortID. If a wildcard flow is
     * eventually installed in the FlowController, the match will be a subset
     * of the match originally provided to the simulation. Note that in this
     * case the generatedPacketEgressPort argument should be null and will be
     * ignored.
     *
     * In case 2, the match object for the packet was computed by the Device
     * that emitted the packet and must not contain an inPortID. If the packet
     * is not dropped, it will eventually result in a packet being emitted
     * from one or more of the datapath ports. However, a flow is never
     * installed as a result of such a simulation. Note that in this case the
     * generatedPacketEgressPort argument must not be null.
     *
     * When this method completes, it may send a message to the Datapath
     * Controller to install a flow or send a packet.
     */
    def simulate() {
        log.info("Simulate a packet.")

        generatedPacketEgressPort match {
            case None => // This is a packet from the datapath
                origMatch.getInputPortUUID match {
                    case null =>
                        log.error(
                            "Coordinator cannot simulate a flow that " +
                            "NEITHER egressed a virtual device's interior " +
                            "port NOR ingressed a virtual device's exterior " +
                            "port. Match: %s; Packet: %s".format(
                                origMatch.toString, origEthernetPkt.toString))
                        dropFlow(true)

                    case id: UUID =>
                        packetIngressesPort(id, true)
                }

            case Some(egressID) =>
                origMatch.getInputPortUUID match {
                    case null =>
                        packetEgressesPort(egressID)

                    case id: UUID =>
                        log.error(
                            "Coordinator cannot simulate a flow that " +
                            "BOTH egressed a virtual device's interior " +
                            "port AND ingressed a virtual device's exterior " +
                            "port. Match: %s; Packet: %s".format(
                                origMatch.toString, origEthernetPkt.toString))
                        dropFlow(true)
                }
        }
    }

    private def packetIngressesDevice(port: Port[_]) {
        var deviceFuture: Future[Any] = null
        port match {
            case _: BridgePort[_] =>
                deviceFuture = expiringAsk(virtualTopologyManager,
                    BridgeRequest(port.deviceID, false), expiry)
            case _: RouterPort[_] =>
                deviceFuture = expiringAsk(virtualTopologyManager,
                    RouterRequest(port.deviceID, false), expiry)
            case _ =>
                log.error("Port {} belongs to device {} which is neither a " +
                          "bridge or router!", port, port.deviceID)
                dropFlow(true)
                return
        }
        deviceFuture.onComplete {
            case Left(err) => dropFlow(true)
            case Right(deviceReply) =>
                if (!deviceReply.isInstanceOf[Device]) {
                    log.error("VirtualTopologyManager didn't return a device!")
                    dropFlow(true)
                } else {
                    pktContext.setInputPort(port.id)
                              .setIngressFE(port.deviceID)
                    numDevicesSimulated += 1
                    devicesSimulated.put(port.deviceID, numDevicesSimulated)

                    handleActionFuture(deviceReply.asInstanceOf[Device].process(
                        modifMatch, modifEthPkt, pktContext, expiry))
                }
        }
    }

    private def handleActionFuture(actionF: Future[Action]) {
        actionF.onComplete {
            case Left(err) =>
                log.error("Error instead of Action - {}", err)
                dropFlow(true)
            case Right(action) =>
                log.info("Received action: {}", action)
                action match {
                    case ToPortSetAction(portSetID) =>
                        emit(portSetID, true)

                    case ToPortAction(outPortID) =>
                        packetEgressesPort(outPortID)

                    case _: ConsumedAction =>
                        cookie match {
                            case None => // Do nothing.
                            case Some(_) =>
                                flowController.tell(DiscardPacket(cookie))
                        }

                    case _: ErrorDropAction =>
                        cookie match {
                            case None => // Do nothing.
                            case Some(_) =>
                                // Drop the flow temporarily
                                dropFlow(true)
                        }

                    case _: DropAction =>
                        cookie match {
                            case None => // Do nothing.
                            case Some(_) =>
                                dropFlow(false)
                                // TODO(pino): do we need the tags+callbacks?
                        }

                    case _: NotIPv4Action =>
                        cookie match {
                            case None => // Do nothing.
                            case Some(_) =>
                                pktContext.freeze()
                                val notIPv4Match =
                                    (new WildcardMatch()
                                        .setInputPortUUID(origMatch.getInputPortUUID)
                                        .setEthernetSource(origMatch.getEthernetSource)
                                        .setEthernetDestination(
                                            origMatch.getEthernetDestination)
                                        .setEtherType(origMatch.getEtherType))
                                datapathController.tell(
                                    AddWildcardFlow(
                                        new WildcardFlow()
                                            .setMatch(notIPv4Match),
                                        cookie, origEthernetPkt.serialize(),
                                        pktContext.getFlowRemovedCallbacks(),
                                        pktContext.getFlowTags()))
                                // TODO(pino): Connection-tracking blob?
                        }


                    case _ =>
                        log.error("Device returned unexpected action!")
                        dropFlow(true)
                } // end action match
        } // end onComplete
    }

    private def packetIngressesPort(portID: UUID, getPortGroups: Boolean) {
        // Avoid loops - simulate at most X devices.
        if (numDevicesSimulated >= MAX_DEVICES_TRAVERSED) {
            dropFlow(true)
            return
        }

        // Get the RCU port object and start simulation.
        expiringAsk(virtualTopologyManager, PortRequest(portID, false),
                    expiry) onComplete {
            case Left(err) => dropFlow(true)
            case Right(portReply) => portReply match {
                case port: Port[_] =>
                    if (getPortGroups &&
                            port.isInstanceOf[ExteriorPort[_]]) {
                        pktContext.setPortGroups(
                            port.asInstanceOf[ExteriorPort[_]].portGroups)
                    }

                    applyPortFilter(port, port.inFilterID,
                                    packetIngressesDevice _)
                case _ =>
                    log.error("VirtualTopologyManager didn't return a port!")
                    dropFlow(true)
            }
        }
    }

    def applyPortFilter(port: Port[_], filterID: UUID,
                        thunk: (Port[_]) => Unit) {
        if (filterID == null)
            thunk(port)
        else expiringAsk(virtualTopologyManager, ChainRequest(filterID, false),
                         expiry) onComplete {
            case Left(err) => dropFlow(true)
            case Right(chainReply) => chainReply match {
                case chain: Chain =>
                    pktContext.setInputPort(null).setOutputPort(null)
                    val result = Chain.apply(chain, pktContext, modifMatch,
                                             port.id, true)
                    if (result.action == RuleAction.ACCEPT) {
                        //XXX(jlm,pino): Replace the pktContext's match with
                        // result.pmatch.asInstanceOf[WildcardMatch]
                        //XXX modifMatch = result.pmatch
                        thunk(port)
                    } else if (result.action == RuleAction.DROP ||
                               result.action == RuleAction.REJECT) {
                        dropFlow(false)
                    } else {
                        log.error("Port filter {} returned {}, not ACCEPT, " +
                                  "DROP, or REJECT.", filterID, result.action)
                        dropFlow(true)
                    }
                case _ =>
                    log.error("VirtualTopologyActor didn't return a chain!")
                    dropFlow(true)
            }
        }
    }

    /**
     * Simulate the packet egressing a virtual port. This is NOT intended
     * for output-ing to PortSets.
     * @param portID
     */
    private def packetEgressesPort(portID: UUID) {
        expiringAsk(virtualTopologyManager, PortRequest(portID, false),
                    expiry) onComplete {
            case Left(err) => dropFlow(true)
            case Right(portReply) => portReply match {
                case port: Port[_] =>
                    applyPortFilter(port, port.outFilterID, {
                        case _: ExteriorPort[_] =>
                            emit(portID, false)
                        case interiorPort: InteriorPort[_] =>
                            packetIngressesPort(interiorPort.peerID, false)
                        case _ =>
                            log.error(
                                "Port {} neither interior nor exterior port",
                                port)
                            dropFlow(true)
                    })
                case _ =>
                    log.error("VirtualTopologyManager didn't return a port!")
                    dropFlow(true)
            }
        }
    }

    /**
     * Complete the simulation by emitting the packet from the specified
     * virtual port (NOT a PortSet). If the packet was internall generated
     * this will do a SendPacket, otherwise it will do an AddWildcardFlow.
     * @param outputID The ID of the virtual port or PortSet from which the
     *                 packet must be emitted.
     */
    private def emit(outputID: UUID, isPortSet: Boolean) {
        val actions = actionsFromMatchDiff(origMatch, modifMatch)
        isPortSet match {
            case false =>
                actions.append(new FlowActionOutputToVrnPort(outputID))
            case true =>
                actions.append(new FlowActionOutputToVrnPortSet(outputID))
        }

        cookie match {
            case None =>
                datapathController.tell(
                    SendPacket(origEthernetPkt.serialize(), actions.toList))
            case Some(_) =>
                pktContext.freeze()
                val wFlow = new WildcardFlow()
                    .setMatch(origMatch)
                    .setActions(actions.toList)
                    .setIdleExpirationMillis(IDLE_EXPIRATION_MILLIS)
                datapathController.tell(
                    AddWildcardFlow(
                        wFlow, cookie, origEthernetPkt.serialize(),
                        pktContext.getFlowRemovedCallbacks(),
                        pktContext.getFlowTags()))
        }
    }

    private def actionsFromMatchDiff(orig: WildcardMatch,
                                     modif: WildcardMatch)
    : mutable.ListBuffer[FlowAction[_]] = {
        val actions = mutable.ListBuffer[FlowAction[_]]()
        if (!orig.getEthernetSource.equals(modif.getEthernetSource) ||
            !orig.getEthernetDestination.equals(modif.getEthernetDestination)) {
            actions.append(FlowActions.setKey(FlowKeys.ethernet(
                modif.getDataLayerSource, modif.getDataLayerDestination)))
        }
        if (!orig.getNetworkSourceIPv4.equals(modif.getNetworkSourceIPv4) ||
            !orig.getNetworkDestinationIPv4.equals(
                modif.getNetworkDestinationIPv4)) {
            actions.append(FlowActions.setKey(
                FlowKeys.ipv4(
                    modif.getNetworkDestination,
                    modif.getNetworkSource,
                    modif.getNetworkProtocol)
                //.setFrag(?)
                .setProto(modif.getNetworkProtocol)
                .setTos(modif.getNetworkTypeOfService)
                .setTtl(modif.getNetworkTTL)
            ))
        }
        if (!orig.getTransportSourceObject.equals(
            modif.getTransportSourceObject) ||
            !orig.getTransportDestinationObject.equals(
            modif.getTransportDestinationObject)) {
            val actSetKey = FlowActions.setKey(null)
            actions.append(actSetKey)
            modif.getNetworkProtocol match {
                case TCP.PROTOCOL_NUMBER =>
                    actSetKey.setFlowKey(FlowKeys.tcp(
                        modif.getTransportSource,
                        modif.getTransportDestination))
                case UDP.PROTOCOL_NUMBER =>
                    actSetKey.setFlowKey(FlowKeys.udp(
                        modif.getTransportSource,
                        modif.getTransportDestination))
            }
        }
        return actions
    }
} // end Coordinator class
