// Copyright 2012 Midokura Inc.

package com.midokura.midolman

import collection.JavaConversions._
import collection.mutable.{HashMap, MultiMap, Set}
import config.MidolmanConfig
import datapath.ErrorHandlingCallback

import com.midokura.sdn.dp.{FlowMatch, Flow, Datapath, Packet}

import com.midokura.sdn.flows.{FlowManagerHelper, WildcardMatches, FlowManager, WildcardFlow}
import com.midokura.sdn.dp.flows.FlowAction
import javax.inject.Inject
import com.midokura.netlink.protos.OvsDatapathConnection
import com.midokura.netlink.Callback
import com.midokura.netlink.exceptions.NetlinkException
import akka.actor._
import collection.immutable
import com.midokura.util.functors.{Callback1, Callback0}
import akka.util.Duration
import java.util.concurrent.TimeUnit

object FlowController extends Referenceable {
    val Name = "FlowController"

    case class AddWildcardFlow(flow: WildcardFlow, packet: Option[Packet],
                               flowRemovalCallbacks: Set[Callback0],
                               tags: immutable.Set[AnyRef])

    case class RemoveWildcardFlow(flow: WildcardFlow)

    case class RemoveFlow(flow: Flow)

    case class SendPacket(data: Array[Byte], actions: List[FlowAction[_]])

    case class DiscardPacket(packet: Packet)

    case class InvalidateFlowsByTag(tag: AnyRef)

    case class CheckFlowExpiration()

    case class WildcardFlowAdded(f: WildcardFlow)

    case class WildcardFlowRemoved(f: WildcardFlow)
}


class FlowController extends Actor with ActorLogging {

    import FlowController._

    var datapath: Datapath = null
    var maxDpFlows = 0
    var dpFlowRemoveBatchSize = 0

    @Inject
    var midolmanConfig: MidolmanConfig = null

    private val dpMatchToPendedPackets: MultiMap[FlowMatch, Packet] =
        new HashMap[FlowMatch, Set[Packet]] with MultiMap[FlowMatch, Packet]

    @Inject
    var datapathConnection: OvsDatapathConnection = null

    var flowManager: FlowManager = null

    val tagToFlows: MultiMap[AnyRef, WildcardFlow] =
        new HashMap[AnyRef, Set[WildcardFlow]]
            with MultiMap[AnyRef, WildcardFlow]
    val flowToTags: MultiMap[WildcardFlow, AnyRef] =
        new HashMap[WildcardFlow, Set[AnyRef]]
            with MultiMap[WildcardFlow, AnyRef]

    val flowExpirationCheckInterval: Duration = Duration(50, TimeUnit.MILLISECONDS)

    override def preStart() {
        super.preStart()

        maxDpFlows = midolmanConfig.getDatapathMaxFlowCount

        flowManager = new FlowManager(new FlowManagerInfoImpl(), maxDpFlows)

        // schedule next check for flow expiration after 20 ms and then after
        // every flowExpirationCheckInterval ms
        context.system.scheduler.schedule(Duration(20, TimeUnit.MILLISECONDS),
            flowExpirationCheckInterval,
            self,
            CheckFlowExpiration)
    }

    def receive = {
        case DatapathController.DatapathReady(dp) =>
            if (null == datapath) {
                datapath = dp
                installPacketInHook()
                log.info("Datapath hook installed")
            }

        case packetIn(packet) =>
            handlePacketIn(packet)

        case AddWildcardFlow(wildcardFlow, packetOption, flowRemovalCallbacks, tags) =>
            handleNewWildcardFlow(wildcardFlow, packetOption)
            context.system.eventStream.publish(new WildcardFlowAdded(wildcardFlow))


        case DiscardPacket(packet) =>
            dpMatchToPendedPackets.remove(packet.getMatch)

        case InvalidateFlowsByTag(tag) =>
            val flowsOption = tagToFlows.get(tag)
            flowsOption match {
                case None =>
                case Some(flowSet) =>
                    for (wildFlow <- flowSet)
                        removeWildcardFlow(wildFlow)
            }

        case RemoveFlow(flow: Flow) =>
            removeFlow(flow)

        case RemoveWildcardFlow(flow) =>
            log.debug("Removing wcflow {}", flow)
            removeWildcardFlow(flow)
            context.system.eventStream.publish(new WildcardFlowRemoved(flow))


        case SendPacket(data, actions) =>
            if (actions.size > 0) {
                val packet = new Packet().
                    setMatch(new FlowMatch).
                    setData(data).setActions(actions)
                datapathConnection.packetsExecute(datapath, packet,
                    new ErrorHandlingCallback[java.lang.Boolean] {
                        def onSuccess(data: java.lang.Boolean) {}

                        def handleError(ex: NetlinkException, timeout: Boolean) {
                            log.error(ex,
                                "Failed to send a packet {} due to {}", packet,
                                if (timeout) "timeout" else "error")
                        }
                    })
            }
        case CheckFlowExpiration() =>
            log.info("Checking flow expiration")
            flowManager.checkFlowsExpiration()
    }

    /**
     * Internal message posted by the netlink callback hook when a new packet not
     * matching any flows appears on one of the datapath ports.
     *
     * @param packet the packet data
     */
    case class packetIn(packet: Packet)

    private def removeWildcardFlow(wildFlow: WildcardFlow) {
        log.info("removeWildcardFlow - Removing flow {}", wildFlow)
        val removedDpFlowMatches = flowManager.remove(wildFlow)
        if (removedDpFlowMatches != null) {
            for (flowMatch <- removedDpFlowMatches) {
                val flow = new Flow().setMatch(flowMatch)
                removeFlow(flow)
            }
        }
        // TODO(pino): update tagToFlows and flowToTags
    }
    
    private def removeFlow(flow: Flow){
        datapathConnection.flowsDelete(datapath, flow,
            flowManager.getFlowDeleteCallback(flow))
    }



    private def handlePacketIn(packet: Packet) {
        // In case the PacketIn notify raced a flow rule installation, see if
        // the flowManager already has a match.
        val actions = flowManager.getActionsForDpFlow(packet.getMatch)
        if (actions != null) {
            packet.setActions(actions)
            datapathConnection.packetsExecute(datapath, packet,
                new ErrorHandlingCallback[java.lang.Boolean] {
                    def onSuccess(data: java.lang.Boolean) {}

                    def handleError(ex: NetlinkException, timeout: Boolean) {
                        log.error(ex,
                            "Failed to send a packet {} due to {}", packet,
                            if (timeout) "timeout" else "error")
                    }
                })
            return
        }
        // Otherwise, try to create a datapath flow based on an existing
        // wildcard flow.
        val dpFlow = flowManager.createDpFlow(packet.getMatch)
        if (dpFlow != null) {
            datapathConnection.flowsCreate(datapath, dpFlow,
                new ErrorHandlingCallback[Flow] {
                    def onSuccess(data: Flow) {}

                    def handleError(ex: NetlinkException, timeout: Boolean) {
                        log.error(ex,
                            "Failed to install a flow {} due to {}", dpFlow,
                            if (timeout) "timeout" else "error")
                    }
                })
            return
        } else {
            // Otherwise, pass the packetIn up to the next layer for handling.
            // Keep track of these packets so that for every FlowMatch, only
            // one such call goes to the next layer.
            if (dpMatchToPendedPackets.get(packet.getMatch) == None) {
                DatapathController.getRef() !
                    DatapathController.PacketIn(packet,
                        WildcardMatches.fromFlowMatch(packet.getMatch))
            }
            dpMatchToPendedPackets.addBinding(packet.getMatch, packet)
        }
    }

    private def handleNewWildcardFlow(wildcardFlow: WildcardFlow,
                                      packetOption: Option[Packet]) {
        if (!flowManager.add(wildcardFlow)){
            log.error("FlowManager failed to install wildcard flow {}",
                wildcardFlow)
            // TODO(ross): in case of error should we process the packet?
            return
        }
        packetOption match {
            case None =>
            case Some(packet) =>
                flowManager.add(packet.getMatch, wildcardFlow)
                val pendedPackets =
                    dpMatchToPendedPackets.remove(packet.getMatch)
                val dpFlow = new Flow().
                    setMatch(packet.getMatch).
                    setActions(wildcardFlow.getActions)

                datapathConnection.flowsCreate(datapath, dpFlow,
                    flowManager.getFlowCreatedCallback(dpFlow))
                log.debug("Flow created {}", dpFlow.getMatch.toString)

                // Send all pended packets with the same action list (unless
                // the action list is empty, which is equivalent to dropping)
                if (pendedPackets != None
                    && wildcardFlow.getActions.size() > 0) {
                    for (unpendedPacket <- pendedPackets.get) {
                        unpendedPacket.setActions(wildcardFlow.getActions)
                        datapathConnection.packetsExecute(datapath, unpendedPacket,
                            new ErrorHandlingCallback[java.lang.Boolean] {
                                def onSuccess(data: java.lang.Boolean) {}

                                def handleError(ex: NetlinkException, timeout: Boolean) {
                                    log.error(ex,
                                        "Failed to send a packet {} due to {}", packet,
                                        if (timeout) "timeout" else "error")
                                }
                            })
                    }
                }
        }
    }

    private def installPacketInHook() = {
        log.info("Installing packet in handler")
        // TODO: try to make this cleaner (right now we are just waiting for
        // the install future thus blocking the current thread).
        datapathConnection.datapathsSetNotificationHandler(datapath, new Callback[Packet] {
            def onSuccess(data: Packet) {
                self ! packetIn(data)
            }

            def onTimeout() {}

            def onError(e: NetlinkException) {}
        }).get()
    }

    class FlowManagerInfoImpl() extends FlowManagerHelper{
        def removeFlow(flow: Flow) {
            log.debug("Sending myself a message to remove flow {}", flow.toString)
            self ! RemoveFlow(flow)
        }

        def removeWildcardFlow(flow: WildcardFlow) {
            log.debug("Sending myself a message to remove wildcard flow {}", flow.toString)
            self ! RemoveWildcardFlow(flow)
        }

        def getFlow(flowMatch: FlowMatch): Flow = {

            val flowFuture: java.util.concurrent.Future[Flow] =
                datapathConnection.flowsGet(datapath, flowMatch)

            try {
                val kernelFlow: Flow = flowFuture.get
                kernelFlow
            }catch {
                case e: Exception => {
                    log.error("Got an exception when trying to flowsGet()" +
                        "for flow match {}", flowMatch, e)
                    null
                }
            }


        }
    }
}
