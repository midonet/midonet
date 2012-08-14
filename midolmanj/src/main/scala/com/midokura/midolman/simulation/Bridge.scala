/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.simulation

import scala.collection.mutable
import java.util.UUID
import com.midokura.midolman.state.zkManagers.BridgeZkManager.BridgeConfig
import com.midokura.packets.{MAC, IntIPv4, ARP, Ethernet, IPv4}
import org.slf4j.LoggerFactory
import com.midokura.midolman.vrn._
import scala.Some


class Bridge(val id: UUID, val cfg: BridgeConfig,
             val inFilter: Chain, val outFilter: Chain) extends Device {

    private val log = LoggerFactory.getLogger(classOf[Bridge])

    private val rtrMacToLogicalPortId = new mutable.HashMap[MAC, UUID]()
    private val rtrIpToMac = new mutable.HashMap[IntIPv4, MAC]()
    private val macPortMap = new mutable.HashMap[MAC, UUID]() //XXX: No

    override def hashCode = id.hashCode()

    override def equals(other: Any) = other match {
        case that: Bridge =>
            (that canEqual this) &&
                (this.id == that.id) && (this.cfg == that.cfg) && (this.inFilter == that.inFilter) &&
                (this.outFilter == that.outFilter)
        case _ =>
            false
    }

    def canEqual(other: Any) = other.isInstanceOf[Bridge]

    override def process(ingress: PacketContext): ProcessResult = {
        val srcDlAddress = new MAC(ingress.mmatch.getDataLayerSource)
        val dstDlAddress = new MAC(ingress.mmatch.getDataLayerDestination)

        // Drop the packet if its L2 source is a multicast address.
        if (Ethernet.isMcast(srcDlAddress))
            return new DropResult()

        var matchOut = ingress.mmatch.clone
        var outPortID: UUID = null

        //XXX: Call ingress (pre-bridging) chain

        if (Ethernet.isMcast(dstDlAddress)) {
            // L2 Multicast
            val nwDst = new IntIPv4(ingress.mmatch.getNetworkDestination)
            if (Ethernet.isBroadcast(dstDlAddress) &&
                ingress.mmatch.getDataLayerType == ARP.ETHERTYPE &&
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
            macPortMap.get(dstDlAddress) match {
                case Some(port: UUID) => outPortID = port
                case None =>
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
            increaseMacPortFlowCount(srcDlAddress, ingress.port)
            //XXX: Flow Removal notifications so we can dec the flow count
        }

        //XXX: apply egress (post-bridging) chain

        // XXX: Add to traversed elements list if flooding.

        return new ForwardResult(new PortMatch(outPortID, matchOut))
    }

    private def increaseMacPortFlowCount(mac: MAC, port: UUID) {
        //XXX
    }
}
