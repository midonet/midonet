/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.simulation

import akka.dispatch.{ExecutionContext, Future, Promise}
import akka.dispatch.Future.flow
import akka.util.duration._
import scala.collection.{Map => ROMap, mutable}
import java.util.UUID
import org.slf4j.LoggerFactory

import com.midokura.midolman.simulation.Coordinator._
import com.midokura.midolman.state.zkManagers.BridgeZkManager.BridgeConfig
import com.midokura.midolman.topology.{MacFlowCount,
                                       RemoveFlowCallbackGenerator}
import com.midokura.midonet.cluster.client.MacLearningTable
import com.midokura.packets.{ARP, Ethernet, IntIPv4, IPv4, MAC}
import com.midokura.sdn.flows.WildcardMatch
import com.midokura.util.functors.{Callback0, Callback1}
import akka.actor.ActorSystem


class Bridge(val id: UUID, val greKey: Long,
             val macPortMap: MacLearningTable,
             val flowCount: MacFlowCount, val inFilter: Chain,
             val outFilter: Chain,
             val flowRemovedCallbackGen: RemoveFlowCallbackGenerator,
             val rtrMacToLogicalPortId: ROMap[MAC, UUID],
             val rtrIpToMac: ROMap[IntIPv4, MAC]) extends Device {

    private val log = LoggerFactory.getLogger(classOf[Bridge])

    override def process(ingressMatch: WildcardMatch, packet: Ethernet,
                         packetContext: PacketContext, expiry: Long)
                        (implicit ec: ExecutionContext,
                         actorSystem: ActorSystem)
            : Future[Coordinator.Action] = {
        // Drop the packet if its L2 source is a multicast address.
        if (Ethernet.isMcast(ingressMatch.getEthernetSource))
            Promise.successful(new DropAction)(ec)
        else
            normalProcess(ingressMatch, packet, packetContext, expiry, ec)
    }

    def normalProcess(ingressMatch: WildcardMatch, packet: Ethernet,
                      packetContext: PacketContext, expiry: Long,
                      ec: ExecutionContext) = {
        val srcDlAddress = ingressMatch.getEthernetSource
        val dstDlAddress = ingressMatch.getEthernetDestination

        var outPortID: Future[UUID] = null

        //XXX: Call ingress (pre-bridging) chain

        Ethernet.isMcast(dstDlAddress) match {
          case true =>
            // L2 Multicast
            val nwDst = ingressMatch.getNetworkDestinationIPv4
            if (Ethernet.isBroadcast(dstDlAddress) &&
                    ingressMatch.getEtherType == ARP.ETHERTYPE &&
                    rtrIpToMac.contains(nwDst)) {
                // Forward broadcast ARPs to their routers if we know how.
                val rtrMAC: MAC = rtrIpToMac.get(nwDst).get
                outPortID = Promise.successful(
                                rtrMacToLogicalPortId.get(rtrMAC).get)(ec)
            } else {
                // Not an ARP request for a router's port's address.
                // Flood to materialized ports only.
                log.info("flooding to port set {}", id)
                outPortID = Promise.successful(id)(ec)
            }
          case false =>
            // L2 unicast
            outPortID = rtrMacToLogicalPortId.get(dstDlAddress) match {
                case Some(logicalPort: UUID) =>
                    // dstMAC is a logical port's MAC.
                    Promise.successful(logicalPort)(ec)
                case None =>
                    // Not a logical port's MAC.  Is dst MAC in
                    // macPortMap? (ie, learned)
                    val learnedPort = getPortOfMac(dstDlAddress, expiry, ec)
                    flow {
                        val port = learnedPort()
                        if (port == null) {
                            /* If neither learned nor logical, flood. */
                            id
                        } else
                            port
                    }(ec)
            }
        }

        // Learn the src MAC unless it's a logical port's.
        if (!rtrMacToLogicalPortId.contains(srcDlAddress)) {
            flowCount.increment(srcDlAddress, ingressMatch.getInputPortUUID)
            packetContext.addFlowRemovedCallback(
                        flowRemovedCallbackGen.getCallback(srcDlAddress,
                                ingressMatch.getInputPortUUID))
            // Pass the tag to be used to index the flow
            val tag = (id, srcDlAddress, ingressMatch.getInputPortUUID)
            packetContext.addFlowTag(tag)
            // Any flow invalidations caused by MACs migrating between ports
            // are done by the BridgeManager, which detects them from the
            // flowCount.increment call.
        }

        //XXX: apply egress (post-bridging) chain

        //XXX: Add to traversed elements list if flooding.

        outPortID map { portID: UUID =>
                            new ForwardAction(portID, ingressMatch) }
    }

    private def getPortOfMac(mac: MAC, expiry: Long, ec: ExecutionContext) = {
        val rv = Promise[UUID]()(ec)
        macPortMap.get(mac, new Callback1[UUID] {
            def call(port: UUID) {
                rv.success(port)
            }
        }, expiry)
        rv
    }
}
