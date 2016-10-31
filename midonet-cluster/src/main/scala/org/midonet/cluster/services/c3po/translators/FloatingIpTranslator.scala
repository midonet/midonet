/*
 * Copyright 2015 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.cluster.services.c3po.translators

import scala.collection.JavaConverters._
import scala.collection.breakOut

import org.midonet.cluster.data.storage.model.Fip64Entry
import org.midonet.cluster.data.storage.{ReadOnlyStorage, StateTableStorage, Transaction}
import org.midonet.cluster.models.Commons.{IPVersion, UUID}
import org.midonet.cluster.models.Neutron.{FloatingIp, NeutronNetwork, NeutronPort, NeutronRouter, NeutronSubnet}
import org.midonet.cluster.models.Topology.{Chain, Port, Router, Rule}
import org.midonet.cluster.services.c3po.translators.PortManager.routerInterfacePortPeerId
import org.midonet.cluster.services.c3po.translators.RouteManager._
import org.midonet.cluster.services.c3po.translators.RouterTranslator.tenantGwPortId
import org.midonet.cluster.util.UUIDUtil.fromProto
import org.midonet.cluster.util.{IPSubnetUtil, UUIDUtil}
import org.midonet.packets.{IPAddr, IPv4Addr, IPv6Addr, MAC}

/** Provides a Neutron model translator for FloatingIp. */
class FloatingIpTranslator(protected val readOnlyStorage: ReadOnlyStorage,
                           protected val stateTableStorage: StateTableStorage)
        extends Translator[FloatingIp] with ChainManager
                with RouteManager
                with RouterManager
                with RuleManager {

    import FloatingIpTranslator._

    implicit val storage: ReadOnlyStorage = readOnlyStorage

    override protected def translateCreate(tx: Transaction,
                                           fip: FloatingIp): OperationList = {
        // If a port is not assigned, there's nothing to do.
        if (fip.hasPortId) {
            associateFip(tx, fip)
        }
        List()
    }

    override protected def translateDelete(tx: Transaction,
                                           fip: FloatingIp): OperationList = {
        if (fip.hasPortId) {
            disassociateFip(tx, fip)
        }
        List()
    }

    override protected def translateUpdate(tx: Transaction,
                                           fip: FloatingIp): OperationList = {
        val oldFip = tx.get(classOf[FloatingIp], fip.getId)
        if ((!oldFip.hasPortId && !fip.hasPortId) ||
            (oldFip.hasPortId && fip.hasPortId &&
                    oldFip.getPortId == fip.getPortId &&
                    oldFip.getRouterId == fip.getRouterId)) {
            // FIP's portId and routerId are both unchanged. Do nothing.
        } else if (oldFip.hasPortId && !fip.hasPortId) {
            // FIP is un-associated from the port.
            disassociateFip(tx, oldFip)
        } else if (!oldFip.hasPortId && fip.hasPortId) {
            // FIP is newly associated.
            associateFip(tx, fip)
        } else {
            if (!isIPv6(oldFip) && !isIPv6(fip)) {
                val fipAddrStr = fip.getFloatingIpAddress.getAddress
                val newPortPair = getFipRtrPortId(tx, fip.getRouterId,
                                                  fipAddrStr)
                if (oldFip.getRouterId != fip.getRouterId) {
                    val oldPortPair = getFipRtrPortId(tx, oldFip.getRouterId,
                                                      fipAddrStr)
                    removeArpEntry(tx, fip, oldPortPair.nwPortId)
                    addArpEntry(tx, fip, newPortPair.nwPortId)
                }

                removeNatRules(tx, oldFip)
                addNatRules(tx, fip, newPortPair.rtrPortId,
                            newPortPair.isGwPort)
            } else {
                disassociateFip(tx, oldFip)
                associateFip(tx, fip)
            }
        }
        List()
    }

    private def fipArpEntryPath(tx: Transaction, fip: FloatingIp,
                                gwPortId: UUID): String = {
        val gwPort = tx.get(classOf[NeutronPort], gwPortId)
        stateTableStorage.bridgeArpEntryPath(
            gwPort.getNetworkId,
            IPv4Addr(fip.getFloatingIpAddress.getAddress),
            MAC.fromString(gwPort.getMacAddress))
    }

    /* Generates a CreateNode Op for FIP IP and Router GW port. */
    private def addArpEntry(tx: Transaction, fip: FloatingIp,
                            gwPortId: UUID): Unit = {
        tx.createNode(fipArpEntryPath(tx, fip, gwPortId))
    }

    /* Generate Create Ops for SNAT and DNAT for the floating IP address. */
    private def addNatRules(tx: Transaction, fip: FloatingIp,
                            rtrPortId: UUID, isGwPort: Boolean): Unit = {
        val rId = fip.getRouterId
        val iChainId = inChainId(rId)
        val oChainId = outChainId(rId)
        val snatRule = Rule.newBuilder
            .setId(fipSnatRuleId(fip.getId))
            .setType(Rule.Type.NAT_RULE)
            .setAction(Rule.Action.ACCEPT)
            .setFipPortId(fip.getPortId)
            .setCondition(anyFragCondition
                              .setNwSrcIp(IPSubnetUtil.fromAddr(
                                              fip.getFixedIpAddress)))
            .setNatRuleData(natRuleData(fip.getFloatingIpAddress, dnat = false,
                                        dynamic = false))
            .build()
        val snatExactRule = snatRule.toBuilder
            .setId(fipSnatExactRuleId(fip.getId))
            .setCondition(anyFragCondition
                              .addOutPortIds(rtrPortId)
                              .setNwSrcIp(IPSubnetUtil.fromAddr(
                                              fip.getFixedIpAddress)))
            .build()

        val dnatRule = Rule.newBuilder
            .setId(fipDnatRuleId(fip.getId))
            .setType(Rule.Type.NAT_RULE)
            .setAction(Rule.Action.ACCEPT)
            .setFipPortId(fip.getPortId)
            .setCondition(anyFragCondition
                              .setNwDstIp(IPSubnetUtil.fromAddr(
                                              fip.getFloatingIpAddress)))
            .setNatRuleData(natRuleData(fip.getFixedIpAddress, dnat = true,
                                        dynamic = false))
            .build()

        val inChain = tx.get(classOf[Chain], iChainId)
        val outChain = tx.get(classOf[Chain], oChainId)
        val floatSnatExactChain = tx.get(classOf[Chain],
                                         floatSnatExactChainId(rId))
        val floatSnatChain = tx.get(classOf[Chain], floatSnatChainId(rId))

        tx.create(snatRule)
        tx.create(snatExactRule)
        tx.create(dnatRule)

        tx.update(prependRules(inChain, dnatRule.getId))
        tx.update(prependRules(floatSnatExactChain, snatExactRule.getId))
        if (isGwPort) {
            tx.update(prependRules(floatSnatChain, snatRule.getId))
        } else {
            tx.update(appendRule(floatSnatChain, snatRule.getId))

            // Note: this rule can be per FIP-processing router ports,
            // not per FIP.  however, currently there's no scalable way to
            // find FIPs handled by the same router port.
            val skipSnatRule = Rule.newBuilder
                .setId(fipSkipSnatRuleId(fip.getId))
                .setChainId(skipSnatChainId(rId))
                .setType(Rule.Type.LITERAL_RULE)
                .setAction(Rule.Action.ACCEPT)
                .setFipPortId(fip.getPortId)
                .setCondition(anyFragCondition.addInPortIds(rtrPortId))
                .build()

            tx.create(skipSnatRule)
        }
    }

    /** Return both port IDs (network and router) of the gateway or interface
      * between the router routerId and the network whose subnet contains the
      * floating IP fipAddrStr.
      */
    private case class PortPair(nwPortId: UUID, rtrPortId: UUID,
                                isGwPort: Boolean)
    private def getFipRtrPortId(tx: Transaction, routerId: UUID,
                                fipAddrStr: String)
    : PortPair = {
        val fipAddr = IPAddr.fromString(fipAddrStr)
        val nRouter = tx.get(classOf[NeutronRouter], routerId)

        // It will usually be the gateway port, so check that before scanning
        // the router's other ports.
        if (nRouter.hasGwPortId) {
            val nwGwPortId = nRouter.getGwPortId
            val rGwPortId = tenantGwPortId(nwGwPortId)
            val rGwPort = tx.get(classOf[Port], rGwPortId)
            val peerPort = tx.get(classOf[NeutronPort], rGwPort.getPeerId)
            val net = tx.get(classOf[NeutronNetwork], peerPort.getNetworkId)
            val subs = tx.getAll(classOf[NeutronSubnet],
                                 net.getSubnetsList.asScala)
            for (sub <- subs) {
                val subnet = IPSubnetUtil.fromProto(sub.getCidr)
                if (subnet.containsAddress(fipAddr))
                    return PortPair(nwGwPortId, tenantGwPortId(nwGwPortId), true)
            }
        }

        /*
         * Get all the topology we might need up front. This reduces the
         * number of calls to zookeeper because bulk gets (getAll) take only
         * one trip, whereas getting each object individually could result
         * in thousands of trips to zookeeper if there are many ports on a
         * network
         */
        val mRouter = tx.get(classOf[Router], routerId)

        def makeMap[T](clazz: Class[T], ids: Seq[UUID]): Map[UUID, T] =
            ids.zip(tx.getAll(clazz, ids))(breakOut)

        val rPortIds = mRouter.getPortIdsList.asScala
        val rPorts = tx.getAll(classOf[Port], rPortIds)
        val nPortIds = rPorts.filter(_.hasPeerId).map(_.getPeerId)
        val nPortMap = makeMap(classOf[NeutronPort], nPortIds)
        val nNetMap = makeMap(classOf[NeutronNetwork],
            nPortMap.values.map(_.getNetworkId)(breakOut))
        val subIds = nNetMap.values.flatMap(_.getSubnetsList.asScala)(breakOut)
        val nSubMap = makeMap(classOf[NeutronSubnet], subIds)

        def hasSubWithAddr(portId: UUID, addr: IPAddr): Boolean = {
            try {
                val port = nPortMap(portId)
                val net = nNetMap(port.getNetworkId)
                val subs = net.getSubnetsList.asScala.map(nSubMap(_))
                subs.exists { sub =>
                    IPSubnetUtil.fromProto(sub.getCidr).containsAddress(addr)
                }
            } catch {
                case e: NoSuchElementException => false
            }
        }


        // The FIP didn't belong to the router's gateway port's subnets, so
        // we need to scan all of its other ports.
        for (rPort <- rPorts if rPort.getId != nRouter.getGwPortId &&
                                rPort.hasPortSubnet && rPort.hasPeerId) {
            if (rPort.hasPeerId && hasSubWithAddr(rPort.getPeerId, fipAddr)) {
                // routerInterfacePortPeerId() is an involution; applying it to
                // the network port ID gives the peer router port ID, and
                // vice-versa.
                val nwPortId = routerInterfacePortPeerId(rPort.getId)

                // Make sure the corresponding NeutronPort exists.
                if (tx.exists(classOf[NeutronPort], nwPortId))
                    return PortPair(nwPortId, rPort.getId, false)
            }
        }

        throw new IllegalStateException(
            s"Router ${UUIDUtil.fromProto(routerId)} has no port whose subnet" +
            s"contains $fipAddrStr")
    }

    private def associateFip4(tx: Transaction, fip: FloatingIp): Unit = {
        val routerId = fip.getRouterId
        checkOldRouterTranslation(tx, routerId)
        val pp = getFipRtrPortId(tx, routerId,
                                 fip.getFloatingIpAddress.getAddress)
        addArpEntry(tx, fip, pp.nwPortId)
        addNatRules(tx, fip, pp.rtrPortId, pp.isGwPort)
    }

    private def disassociateFip4(tx: Transaction, fip: FloatingIp): Unit = {
        val pp = getFipRtrPortId(tx, fip.getRouterId,
                                 fip.getFloatingIpAddress.getAddress)
        removeArpEntry(tx, fip, pp.nwPortId)
        removeNatRules(tx, fip)
    }

    /* Since DeleteNode is idempotent, it is fine if the path does not exist. */
    private def removeArpEntry(tx: Transaction, fip: FloatingIp,
                               gwPortId: UUID): Unit = {
        tx.deleteNode(fipArpEntryPath(tx, fip, gwPortId))
    }

    /* Since Delete is idempotent, it is fine if those rules don't exist. */
    private def removeNatRules(tx: Transaction, fip: FloatingIp): Unit = {
        tx.delete(classOf[Rule], fipSnatRuleId(fip.getId), ignoresNeo = true)
        tx.delete(classOf[Rule], fipSnatExactRuleId(fip.getId),
                  ignoresNeo = true)
        tx.delete(classOf[Rule], fipDnatRuleId(fip.getId), ignoresNeo = true)
        tx.delete(classOf[Rule], fipReverseDnatRuleId(fip.getId),
                  ignoresNeo = true)
        tx.delete(classOf[Rule], fipSkipSnatRuleId(fip.getId),
                  ignoresNeo = true)
    }

    private def associateFip(tx: Transaction, fip: FloatingIp): Unit = {
        if (isIPv6(fip)) {
            associateFip6(tx, fip)
        } else {
            associateFip4(tx, fip)
        }
    }

    private def disassociateFip(tx: Transaction, fip: FloatingIp): Unit = {
        if (isIPv6(fip)) {
            disassociateFip6(tx, fip)
        } else {
            disassociateFip4(tx, fip)
        }
    }

    @throws[IllegalArgumentException]
    private def associateFip6(tx: Transaction, fip: FloatingIp): Unit = {
        tx.createNode(stateTableStorage.fip64EntryPath(fipToFip64Entry(fip)))
    }

    @throws[IllegalArgumentException]
    private def disassociateFip6(tx: Transaction, fip: FloatingIp): Unit = {
        tx.deleteNode(stateTableStorage.fip64EntryPath(fipToFip64Entry(fip)))
    }
}

object FloatingIpTranslator {

    private def isIPv6(fip: FloatingIp): Boolean =
        fip.hasFloatingIpAddress &&
        fip.getFloatingIpAddress.getVersion == IPVersion.V6

    @throws[IllegalArgumentException]
    private def fipToFip64Entry(fip: FloatingIp): Fip64Entry = {
        if (!fip.hasFixedIpAddress
            || !fip.getFixedIpAddress.hasVersion
            ||  fip.getFixedIpAddress.getVersion != IPVersion.V4) {
            throw new IllegalArgumentException(
                "Only IPv4 fixed-addresses are supported")
        }

        // not sure if this is possible but doesn't hurt to check
        if (!fip.hasRouterId) {
            throw new IllegalArgumentException("Router ID must be provided")
        }

        Fip64Entry(IPv4Addr(fip.getFixedIpAddress.getAddress),
                   IPv6Addr(fip.getFloatingIpAddress.getAddress),
                   fip.getPortId,
                   fip.getRouterId)
    }
}