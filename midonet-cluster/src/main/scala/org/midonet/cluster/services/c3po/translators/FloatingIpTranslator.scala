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

import org.midonet.cluster.data.storage.{ReadOnlyStorage, StateTableStorage}
import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.Neutron.{FloatingIp, NeutronPort, NeutronRouter}
import org.midonet.cluster.models.Topology.{Chain, Port, Router, Rule}
import org.midonet.cluster.services.c3po.NeutronTranslatorManager._
import org.midonet.cluster.util.UUIDUtil.fromProto
import org.midonet.cluster.util.{IPSubnetUtil, UUIDUtil}
import org.midonet.packets.{IPAddr, IPv4Addr, MAC}
import org.midonet.util.concurrent.toFutureOps

/** Provides a Neutron model translator for FloatingIp. */
class FloatingIpTranslator(protected val readOnlyStorage: ReadOnlyStorage,
                           protected val stateTableStorage: StateTableStorage)
        extends Translator[FloatingIp] with ChainManager
                with RouteManager
                with RuleManager
                with StateTableManager {
    import PortManager.routerInterfacePortPeerId
    import RouterTranslator.tenantGwPortId
    import org.midonet.cluster.services.c3po.translators.RouteManager._

    implicit val storage: ReadOnlyStorage = readOnlyStorage

    override protected def translateCreate(fip: FloatingIp): OperationList = {
        // If a port is not assigned, there's nothing to do.
        if (!fip.hasPortId) List() else associateFipOps(fip)
    }

    override protected def translateDelete(fip: FloatingIp): OperationList = {
        if (!fip.hasPortId) List() else disassociateFipOps(fip)
    }

    override protected def translateUpdate(fip: FloatingIp): OperationList = {
        val oldFip = storage.get(classOf[FloatingIp], fip.getId).await()
        if ((!oldFip.hasPortId && !fip.hasPortId) ||
            (oldFip.hasPortId && fip.hasPortId &&
                    oldFip.getPortId == fip.getPortId &&
                    oldFip.getRouterId == fip.getRouterId)) {
            // FIP's portId and routerId are both unchanged. Do nothing.
            List()
        } else if (oldFip.hasPortId && !fip.hasPortId) {
            // FIP is un-associated from the port.
            disassociateFipOps(oldFip)
        } else if (!oldFip.hasPortId && fip.hasPortId) {
            // FIP is newly associated.
            associateFipOps(fip)
        } else {
            val fipAddrStr = fip.getFloatingIpAddress.getAddress
            val newPortPair = getFipRtrPortId(fip.getRouterId, fipAddrStr)
            val midoOps = new OperationListBuffer
            if (oldFip.getRouterId != fip.getRouterId) {
                val oldPortPair = getFipRtrPortId(oldFip.getRouterId, fipAddrStr)
                midoOps += removeArpEntry(fip, oldPortPair.nwPortId)
                midoOps += addArpEntry(fip, newPortPair.nwPortId)
            }

            midoOps ++= removeNatRules(oldFip)
            midoOps ++= addNatRules(fip, newPortPair.rtrPortId)
            midoOps.toList
        }
    }

    private def fipArpEntryPath(fip: FloatingIp, gwPortId: UUID): String = {
        val gwPort = storage.get(classOf[NeutronPort], gwPortId).await()
        stateTableStorage.bridgeArpEntryPath(
            gwPort.getNetworkId,
            IPv4Addr(fip.getFloatingIpAddress.getAddress),
            MAC.fromString(gwPort.getMacAddress))
    }

    /* Generates a CreateNode Op for FIP IP and Router GW port. */
    private def addArpEntry(fip: FloatingIp, gwPortId: UUID) =
        CreateNode(fipArpEntryPath(fip, gwPortId))

    /* Generate Create Ops for SNAT and DNAT for the floating IP address. */
    private def addNatRules(fip: FloatingIp, rtrPortId: UUID): OperationList = {
        val iChainId = inChainId(fip.getRouterId)
        val oChainId = outChainId(fip.getRouterId)
        val snatRule = Rule.newBuilder
            .setId(fipSnatRuleId(fip.getId))
            .setType(Rule.Type.NAT_RULE)
            .setAction(Rule.Action.ACCEPT)
            .setFipPortId(fip.getPortId)
            .setCondition(anyFragCondition
                              .addOutPortIds(rtrPortId)
                              .setNwSrcIp(IPSubnetUtil.fromAddr(
                                              fip.getFixedIpAddress)))
            .setNatRuleData(natRuleData(fip.getFloatingIpAddress, dnat = false,
                                        dynamic = false))
            .build()
        val dnatRule = Rule.newBuilder
            .setId(fipDnatRuleId(fip.getId))
            .setType(Rule.Type.NAT_RULE)
            .setAction(Rule.Action.ACCEPT)
            .setFipPortId(fip.getPortId)
            .setCondition(anyFragCondition
                              .addInPortIds(rtrPortId)
                              .setNwDstIp(IPSubnetUtil.fromAddr(
                                              fip.getFloatingIpAddress)))
            .setNatRuleData(natRuleData(fip.getFixedIpAddress, dnat = true,
                                        dynamic = false))
            .build()

        val reverseIcmpDnatRule = Rule.newBuilder
            .setId(fipReverseDnatRuleId(fip.getId))
            .setType(Rule.Type.NAT_RULE)
            .setAction(Rule.Action.CONTINUE)
            .setFipPortId(fip.getPortId)
            .setCondition(anyFragCondition
                              .addOutPortIds(rtrPortId)
                              .setIcmpDataDstIp(IPSubnetUtil.fromAddr(
                                                    fip.getFixedIpAddress)))
            .setNatRuleData(natRuleData(fip.getFloatingIpAddress, dnat = true,
                                        dynamic = false))
            .build()

        val inChain = storage.get(classOf[Chain], iChainId).await()
        val outChain = storage.get(classOf[Chain], oChainId).await()

        val ops = new OperationListBuffer
        ops += Create(snatRule)
        ops += Create(dnatRule)
        ops += Create(reverseIcmpDnatRule)

        // When an update changes the FIP's association from one port to another
        // behind the same router, the DNAT and SNAT rules' IDs will already be
        // in the chains' rule ID lists, because the changes that disassociated
        // the FIP from the old port are part of the same Neutron operation
        // (see removeNatRules()) and haven't been persisted yet. We need to
        // avoid duplicate entries in the chains' rule ID lists.
        //
        // We do still need to update the chains even though we're not actually
        // changing them, because the rule deletion operations created in
        // removeNatRules() will clear those rules' IDs from the chains' rule
        // ID lists due to the Zoom bindings between Rule.chain_id and
        // Chain.rule_ids.
        def prependIfAbsent(chain: Chain, rule: UUID): Chain = {
            if (!chain.getRuleIdsList.contains(rule)) {
                prependRule(chain, rule)
            } else {
                chain
            }
        }
        ops += Update(prependIfAbsent(inChain, dnatRule.getId))

        // add snat and reverseicmp to outchain if they aren't already there
        ops += Update(Seq(snatRule.getId, reverseIcmpDnatRule.getId).foldLeft(outChain)(prependIfAbsent))

        ops.toList
    }

    /** Return both port IDs (network and router) of the gateway or interface
      * between the router routerId and the network whose subnet contains the
      * floating IP fipAddrStr.
      */
    private case class PortPair(nwPortId: UUID, rtrPortId: UUID)
    private def getFipRtrPortId(routerId: UUID, fipAddrStr: String)
    : PortPair = {
        val fipAddr = IPAddr.fromString(fipAddrStr)
        val nRouter = storage.get(classOf[NeutronRouter], routerId).await()

        // It will usually be the gateway port, so check that before scanning
        // the router's other ports.
        val rGwPortIdOpt = if (nRouter.hasGwPortId) {
            val nwGwPortId = nRouter.getGwPortId
            val rGwPortId = tenantGwPortId(nwGwPortId)
            val rGwPort = storage.get(classOf[Port], rGwPortId).await()
            val subnet = IPSubnetUtil.fromProto(rGwPort.getPortSubnet)
            if (subnet.containsAddress(fipAddr))
                return PortPair(nwGwPortId, tenantGwPortId(nwGwPortId))
            Some(rGwPortId)
        } else None

        // The FIP didn't belong to the router's gateway port's subnet, so
        // we need to scan all of its other ports.
        val mRouter = storage.get(classOf[Router], routerId).await()
        val portIds = mRouter.getPortIdsList.asScala -- rGwPortIdOpt
        val rPorts = storage.getAll(classOf[Port], portIds).await()
        for (rPort <- rPorts if rPort.hasPortSubnet) {
            val subnet = IPSubnetUtil.fromProto(rPort.getPortSubnet)
            if (subnet.containsAddress(fipAddr)) {
                // routerInterfacePortPeerId() is an involution; applying it to
                // the network port ID gives the peer router port ID, and
                // vice-versa.
                val nwPortId = routerInterfacePortPeerId(rPort.getId)

                // Make sure the corresponding NeutronPort exists.
                if (storage.exists(classOf[NeutronPort], nwPortId).await())
                    return PortPair(nwPortId, rPort.getId)

                // Midonet-only port's CIDR conflicts with a Neutron port's.
                val rPortJUuid = UUIDUtil.fromProto(rPort.getId)
                val subnet = IPSubnetUtil.fromProto(rPort.getPortSubnet)
                log.warn(
                    s"Midonet router port $rPortJUuid does not have a " +
                    s"corresponding Neutron port, but its subnet, $subnet, " +
                    s"contains the Neutron floating IP $fipAddrStr.")
            }
        }

        throw new IllegalStateException(
            s"Router ${UUIDUtil.fromProto(routerId)} has no port whose subnet" +
            s"contains $fipAddrStr.")
    }

    private def associateFipOps(fip: FloatingIp): OperationList = {
        val pp = getFipRtrPortId(fip.getRouterId,
                                 fip.getFloatingIpAddress.getAddress)
        addArpEntry(fip, pp.nwPortId) +: addNatRules(fip, pp.rtrPortId)
    }

    private def disassociateFipOps(fip: FloatingIp): OperationList = {
        val pp = getFipRtrPortId(fip.getRouterId,
                                 fip.getFloatingIpAddress.getAddress)
        removeArpEntry(fip, pp.nwPortId) +: removeNatRules(fip)
    }

    /* Since DeleteNode is idempotent, it is fine if the path does not exist. */
    private def removeArpEntry(fip: FloatingIp, gwPortId: UUID) =
        DeleteNode(fipArpEntryPath(fip, gwPortId))

    /* Since Delete is idempotent, it is fine if those rules don't exist. */
    private def removeNatRules(fip: FloatingIp): OperationList = {
        List(Delete(classOf[Rule], fipSnatRuleId(fip.getId)),
             Delete(classOf[Rule], fipDnatRuleId(fip.getId)),
             Delete(classOf[Rule], fipReverseDnatRuleId(fip.getId)))
    }
}
