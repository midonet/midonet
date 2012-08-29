/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.simulation

import akka.dispatch.{Await, ExecutionContext, Future, Promise}
import akka.dispatch.Future.flow
import akka.util.duration._
import scala.collection.mutable
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


class Bridge(val id: UUID, val cfg: BridgeConfig,
             val macPortMap: MacLearningTable, val flowCount: MacFlowCount,
             val inFilter: Chain, val outFilter: Chain,
             val flowRemovedCallbackGen: RemoveFlowCallbackGenerator,
             val rtrMacToLogicalPortId: mutable.Map[MAC, UUID],
             val rtrIpToMac: mutable.Map[IntIPv4, MAC]) extends Device {

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
            : Future[Coordinator.Action] = {
        // Drop the packet if its L2 source is a multicast address.
        if (Ethernet.isMcast(ingressMatch.getEthernetSource))
            Promise.successful(new DropAction)(ec)
        else
            normalProcess(ingressMatch, packet, packetContext, ec)
    }

    def normalProcess(ingressMatch: WildcardMatch, packet: Ethernet,
                      packetContext: PacketContext, ec: ExecutionContext) = {
        val srcDlAddress = ingressMatch.getEthernetSource
        val dstDlAddress = ingressMatch.getEthernetDestination

        var matchOut: WildcardMatch = null // TODO
        var outPortID: Future[UUID] = null

        //XXX: Call ingress (pre-bridging) chain

        Ethernet.isMcast(dstDlAddress) match {
          case true =>
            // L2 Multicast
            val nwDst = ingressMatch.getNetworkDestination
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
            // Is dst MAC in macPortMap? (learned)
            val learnedPort = getPortOfMac(dstDlAddress, ec)
            outPortID = flow {
                val port = learnedPort()
                if (port == null) {
                    // Is dst MAC a logical port's MAC?
                    rtrMacToLogicalPortId.get(dstDlAddress) match {
                        case Some(logicalPort: UUID) => logicalPort
                        /* If neither learned nor logical, flood. */
                        case None => id
                    }
                } else
                    port
            }(ec)
        }

        // Learn the src MAC unless it's a logical port's.
        if (!rtrMacToLogicalPortId.contains(srcDlAddress)) {
            flowCount.increment(srcDlAddress, ingressMatch.getInputPortUUID)
            getPortOfMac(srcDlAddress, ec) onSuccess {
              case oldPort: UUID =>
                if (oldPort != ingressMatch.getInputPortUUID) {
                    log.debug("MAC {} moved from port {} to {}.",
                        Array[Object](srcDlAddress, oldPort,
                                      ingressMatch.getInputPortUUID))
                    // The flows that reflect the old MAC port entry will be
                    // removed by the BridgeManager
                    macPortMap.add(srcDlAddress, ingressMatch.getInputPortUUID)
                    packetContext.synchronized {
                        if (!packetContext.isFrozen) {
                            packetContext.addFlowRemovedCallback(
                                flowRemovedCallbackGen.getCallback(srcDlAddress,
                                        ingressMatch.getInputPortUUID))
                            // Pass the tag to be used to index the flow
                            val tag = (id, srcDlAddress,ingressMatch.getInputPortUUID)
                            packetContext.addFlowTag(tag)
                        }
                    }
                }
            }
        }

        //XXX: apply egress (post-bridging) chain

        //XXX: Add to traversed elements list if flooding.

        outPortID map { portID: UUID =>
                            new ForwardAction(portID, ingressMatch) }
    }

    private def getPortOfMac(mac: MAC, ec: ExecutionContext) = {
        val rv = Promise[UUID]()(ec)
        macPortMap.get(mac, new Callback1[UUID] {
            def call(port: UUID) {
                rv.success(port)
            }
        })
        rv
    }
}
