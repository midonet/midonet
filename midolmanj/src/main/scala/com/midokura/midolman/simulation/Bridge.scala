/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.simulation

import akka.dispatch.{Await, ExecutionContext, Promise}
import akka.util.duration._
import scala.collection.mutable
import java.util.UUID
import org.slf4j.LoggerFactory

import com.midokura.midolman.state.zkManagers.BridgeZkManager.BridgeConfig
import com.midokura.midonet.cluster.client.MacLearningTable
import com.midokura.packets.{ARP, Ethernet, IntIPv4, IPv4, MAC}
import com.midokura.sdn.flows.WildcardMatch
import com.midokura.midolman.simulation.Coordinator._
import com.midokura.util.functors.{Callback0, Callback1}
import com.midokura.midolman.topology.{RemoveFlowCallbackGenerator, MacFlowCount}


class Bridge(val id: UUID, val cfg: BridgeConfig,
             val macPortMap: MacLearningTable, val flowCount: MacFlowCount,
             val inFilter: Chain, val outFilter: Chain,
             val flowRemovedCallbackGen : RemoveFlowCallbackGenerator,
             val rtrMacToLogicalPortId : mutable.Map[MAC, UUID],
             val rtrIpToMac : mutable.Map[IntIPv4, MAC]
                ) extends Device {

    private val log = LoggerFactory.getLogger(classOf[Bridge])

    override def hashCode = id.hashCode()

    override def equals(other: Any) = other match {
        case that: Bridge =>
            (that canEqual this) &&
                (this.id == that.id) && (this.cfg == that.cfg) &&
                (this.inFilter == that.inFilter) &&
                (this.outFilter == that.outFilter) &&
                (this.macPortMap == that.macPortMap) &&
                (this.flowCount == that.flowCount)
        case _ =>
            false
    }

    def canEqual(other: Any) = other.isInstanceOf[Bridge]

    override def process(ingressMatch: WildcardMatch,
                         packet: Ethernet,
                         packetContext: PacketContext,
                         ec: ExecutionContext)
            : Coordinator.Action = {
        val srcDlAddress = ingressMatch.getEthernetSource
        val dstDlAddress = ingressMatch.getEthernetDestination

        // Drop the packet if its L2 source is a multicast address.
        if (Ethernet.isMcast(srcDlAddress))
            return new DropAction()

        var matchOut: WildcardMatch = null // TODO
        var outPortID: UUID = null

        //XXX: Call ingress (pre-bridging) chain

        if (Ethernet.isMcast(dstDlAddress)) {
            // L2 Multicast
            val nwDst = ingressMatch.getNetworkDestination
            if (Ethernet.isBroadcast(dstDlAddress) &&
                    ingressMatch.getEtherType == ARP.ETHERTYPE &&
                    rtrIpToMac.contains(nwDst)) {
                // Forward broadcast ARPs to their routers if we know how.
                val rtrMAC: MAC = rtrIpToMac.get(nwDst).get
                outPortID = rtrMacToLogicalPortId.get(rtrMAC).get
            } else {
                // Not an ARP request for a router's port's address.
                // Flood to materialized ports only.
                log.info("flooding to port set {}", id)
                outPortID = id
            }
        } else {
            // L2 unicast
            // Is dst MAC in macPortMap? (learned)
            outPortID = getPortOfMac(dstDlAddress, ec)
            if (outPortID == null) {
                // Is dst MAC a logical port's MAC?
                rtrMacToLogicalPortId.get(dstDlAddress) match {
                    case Some(port: UUID) => outPortID = port
                    case None =>
                        // If neither learned nor logical, flood.
                        outPortID = id
                }
            }
        }

        // Learn the src MAC unless it's a logical port's.
        if (!rtrMacToLogicalPortId.contains(srcDlAddress)) {
            flowCount.increment(srcDlAddress, ingressMatch.getInputPortUUID)
            val oldPortID = getPortOfMac(srcDlAddress, ec)
            if (ingressMatch.getInputPortUUID != oldPortID) {
                log.debug("MAC {} moved from port {} to {}.",
                    Array[Object](srcDlAddress, oldPortID,
                                  ingressMatch.getInputPortUUID))
                // The flows that reflect the old MAC port entry will be removed
                // by the BridgeManager
                macPortMap.add(srcDlAddress, ingressMatch.getInputPortUUID)
                packetContext.addFlowRemovedCallback(
                    flowRemovedCallbackGen.getCallback(srcDlAddress,
                        ingressMatch.getInputPortUUID))
                // Pass the tag to be used to index the flow
                val tag = (id, srcDlAddress,ingressMatch.getInputPortUUID)
                packetContext.addFlowTag(tag)
            }
        }

        //XXX: apply egress (post-bridging) chain

        //XXX: Add to traversed elements list if flooding.

        return new ForwardAction(outPortID)
    }

    private def getPortOfMac(mac: MAC, ec: ExecutionContext): UUID = {
        val rv = Promise[UUID]()(ec)
        macPortMap.get(mac, new Callback1[UUID] {
            def call(port: UUID) {
                rv.complete(Right(port))
            }
        })
        Await.result(rv, 1 minute)
    }
}
