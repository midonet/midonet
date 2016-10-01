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

import java.util.UUID

import scala.collection.JavaConverters._

import org.midonet.cluster.data.storage._
import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.util.UUIDUtil._

object L2InsertionTranslation {

    private final val PcpInMask = 1 << 13
    private final val PcpOutMask = 2 << 13
    private final val PcpFinalMask = 1 << 15

    private def ensureRedirectChains(tx: Transaction, portId: Commons.UUID)
    : (Chain, Chain) = {
        val port = tx.get(classOf[Port], portId)
        val portBuilder = port.toBuilder

        val inFilter = if (!port.hasL2InsertionInfilterId) {
            val chain: Chain = Chain.newBuilder()
                .setId(UUID.randomUUID.asProto)
                .setName("InboundRedirect" + port.getId.asJava.toString).build
            tx.create(chain)
            portBuilder.setL2InsertionInfilterId(chain.getId)
            chain
        } else {
            tx.get(classOf[Chain], port.getL2InsertionInfilterId)
        }

        val outFilter = if (!port.hasL2InsertionOutfilterId) {
            val chain = Chain.newBuilder()
                .setId(UUID.randomUUID.asProto)
                .setName("OutboundRedirect" + port.getId.asJava.toString).build
            tx.create(chain)
            portBuilder.setL2InsertionOutfilterId(chain.getId)
            chain
        } else {
            tx.get(classOf[Chain], port.getL2InsertionOutfilterId)
        }

        val updatedPort = portBuilder.build()
        if (updatedPort != port) {
            tx.update(portBuilder.build())
        }
        (inFilter, outFilter)
    }

    implicit object InsertOrdering extends Ordering[L2Insertion] {

        // Insertions should be sorted by position, tie-break with ports
        def compare(left: L2Insertion, right: L2Insertion) = {
            var ret: Int = left.getPosition compare right.getPosition
            if (ret == 0) {
                ret = left.getPortId.toString compare right.getPortId.toString
            }
            if (ret == 0) {
                ret = left.getSrvPortId.toString compare right.getSrvPortId.toString
            }
            ret
        }
    }

    private def removeServicePortRules(tx: Transaction, chain: Chain,
                                       insertion: L2Insertion): Chain = {
        tx.delete(classOf[Rule], insertion.getSrvRuleInId)
        tx.delete(classOf[Rule], insertion.getSrvRuleOutId)
        val chainBuilder = chain.toBuilder
        chainBuilder.clearRuleIds().addAllRuleIds(
            chain.getRuleIdsList.asScala.filter(
                (id: Commons.UUID) => {
                    id != insertion.getSrvRuleInId &&
                        id != insertion.getSrvRuleOutId
                }).asJava)
        chainBuilder.build()
    }

    private def addServicePortRules(tx: Transaction,
                                    chain: Chain,
                                    insertionInChain: Chain,
                                    insertionOutChain: Chain,
                                    insertion: L2Insertion)
    : (Chain, L2Insertion) = {
        val ruleInBuilder = Rule.newBuilder()
            .setId(UUID.randomUUID.asProto)
            .setChainId(chain.getId)
            .setType(Rule.Type.JUMP_RULE)
            .setAction(Rule.Action.JUMP)
        ruleInBuilder.getJumpRuleDataBuilder
            .setJumpChainId(insertionInChain.getId)
            .setJumpChainName(insertionInChain.getName)
        ruleInBuilder.getConditionBuilder.setDlSrc(insertion.getMac)
        val ruleOutBuilder = Rule.newBuilder()
            .setId(UUID.randomUUID.asProto)
            .setChainId(chain.getId)
            .setType(Rule.Type.JUMP_RULE)
            .setAction(Rule.Action.JUMP)
        ruleOutBuilder.getJumpRuleDataBuilder
            .setJumpChainId(insertionOutChain.getId)
            .setJumpChainName(insertionOutChain.getName)
        ruleOutBuilder.getConditionBuilder.setDlDst(insertion.getMac)
        // Does this insertion use vlan? Matching on the vlan, including
        // PCP allows correct redirection of traffic between two VMs on
        // the same bridge. The MACs alone are not sufficient.
        if (insertion.getVlan != 0) {
            val vlan = insertion.getVlan
            ruleInBuilder.getConditionBuilder.setVlan(vlan | PcpInMask)
            ruleOutBuilder.getConditionBuilder.setVlan(vlan | PcpOutMask)
        }

        val ruleIn = ruleInBuilder.build()
        val ruleOut = ruleOutBuilder.build()

        tx.create(ruleIn)
        tx.create(ruleOut)

        val modifiedChain = chain.toBuilder
            .addRuleIds(ruleIn.getId)
            .addRuleIds(ruleOut.getId).build()
        val modifiedInsertion = insertion.toBuilder
            .setSrvRuleInId(ruleIn.getId)
            .setSrvRuleOutId(ruleOut.getId).build()

        (modifiedChain, modifiedInsertion)
    }

    private def buildInsertionRules(tx: Transaction,
                                    previous: Option[L2Insertion],
                                    cur: L2Insertion,
                                    inChainId: Commons.UUID,
                                    outChainId: Commons.UUID)
    : (Commons.UUID, Commons.UUID) = {
        val portId = cur.getPortId
        val ruleInBuilder = Rule.newBuilder()
            .setId(UUID.randomUUID.asProto)
            .setChainId(inChainId)
            .setType(Rule.Type.L2TRANSFORM_RULE)
            .setAction(Rule.Action.REDIRECT)
        ruleInBuilder.getTransformRuleDataBuilder
            .setTargetPortId(cur.getSrvPortId)
            .setIngress(false)
            .setFailOpen(cur.getFailOpen)
        ruleInBuilder.getConditionBuilder.setDlSrc(cur.getMac)

        val ruleOutBuilder = Rule.newBuilder()
            .setId(UUID.randomUUID.asProto)
            .setChainId(outChainId)
            .setType(Rule.Type.L2TRANSFORM_RULE)
            .setAction(Rule.Action.REDIRECT)
        ruleOutBuilder.getTransformRuleDataBuilder
            .setTargetPortId(cur.getSrvPortId)
            .setIngress(false)
            .setFailOpen(cur.getFailOpen)
        ruleOutBuilder.getConditionBuilder.setDlDst(cur.getMac)

        if (cur.getVlan != 0) {// Does this insertion use vlan?
            val vlan = cur.getVlan
            ruleInBuilder.getTransformRuleDataBuilder
                .setPushVlan(vlan | PcpInMask)
            ruleOutBuilder.getTransformRuleDataBuilder
                .setPushVlan(vlan | PcpOutMask)
        }
        previous match {
            case None =>
                // First insertion. Avoid re-matching these rules by
                // placing them last. The inbound rule can also match
                // on input port.
                ruleInBuilder.getConditionBuilder.addInPortIds(portId)
            case Some(i) =>
                // This follows another insertion. We need to match
                // on its srvPort and we *may* need to pop its vlan
                ruleInBuilder.getConditionBuilder.addInPortIds(i.getSrvPortId)
                ruleOutBuilder.getConditionBuilder.addInPortIds(i.getSrvPortId)

                // Did the previous insertion use vlan?
                if (i.getVlan != 0) {
                    val vlan = i.getVlan
                    ruleInBuilder.getTransformRuleDataBuilder.setPopVlan(true)
                    ruleInBuilder.getConditionBuilder.setVlan(vlan | PcpInMask)
                    ruleOutBuilder.getTransformRuleDataBuilder.setPopVlan(true)
                    ruleOutBuilder.getConditionBuilder.setVlan(vlan | PcpOutMask)
                }
        }
        val ruleIn = ruleInBuilder.build()
        val ruleOut = ruleOutBuilder.build()

        tx.create(ruleIn)
        tx.create(ruleOut)

        (ruleIn.getId, ruleOut.getId)
    }

    private def buildEndRules(tx: Transaction,
                              endInsertion: L2Insertion,
                              inChainId: Commons.UUID,
                              outChainId: Commons.UUID)
    : (Seq[Commons.UUID], Commons.UUID) = {
        val portId = endInsertion.getPortId
        // This rule matches during evaluation of the last service port's
        // inbound chain. It will redirect into the inspected port and
        // therefore this rule's chain will be re-evaluated. This rule
        // won't match on re-insertion because we compare input port to
        // the last service port.
        val ruleInBuilder = Rule.newBuilder()
            .setId(UUID.randomUUID.asProto)
            .setChainId(inChainId)
            .setType(Rule.Type.L2TRANSFORM_RULE)
            .setAction(Rule.Action.REDIRECT)
        ruleInBuilder.getConditionBuilder
            .setDlSrc(endInsertion.getMac)
            .addInPortIds(endInsertion.getSrvPortId)
        ruleInBuilder.getTransformRuleDataBuilder
            .setTargetPortId(portId)
            .setIngress(true)
            .setFailOpen(false)
            .setPushVlan(PcpFinalMask)

        // This rule matching the special vlan is necessary to avoid
        // re-matching the rule that starts the redirection chain and allows
        // that one not to check "no vlan". The implementation therefore
        // supports redirecting vlan-tagged packets.
        val ruleInBuilder2 = Rule.newBuilder()
            .setId(UUID.randomUUID.asProto)
            .setChainId(inChainId)
            .setType(Rule.Type.L2TRANSFORM_RULE)
            .setAction(Rule.Action.ACCEPT)
        ruleInBuilder2.getConditionBuilder
            .setDlSrc(endInsertion.getMac).addInPortIds(portId)
            .setVlan(PcpFinalMask)
        ruleInBuilder2.getTransformRuleDataBuilder
            .setPopVlan(true)

        // This rule matches during evaluation of the last service port's
        // inbound chain. It will redirect out of the inspected port and
        // the packet will not re-traverse the inspected port's chains.
        val ruleOutBuilder = Rule.newBuilder()
            .setId(UUID.randomUUID.asProto)
            .setChainId(outChainId)
            .setType(Rule.Type.L2TRANSFORM_RULE)
            .setAction(Rule.Action.REDIRECT)
        ruleOutBuilder.getConditionBuilder
            .setDlDst(endInsertion.getMac)
            .addInPortIds(endInsertion.getSrvPortId)
        ruleOutBuilder.getTransformRuleDataBuilder
            .setTargetPortId(portId)
            .setIngress(false)
            .setFailOpen(false)

        // Did the previous insertion use vlan?
        if (endInsertion.getVlan != 0) {
            val vlan = endInsertion.getVlan

            ruleInBuilder.getConditionBuilder.setVlan(vlan | PcpInMask)
            ruleInBuilder.getTransformRuleDataBuilder.setPopVlan(true)

            ruleOutBuilder.getConditionBuilder.setVlan(vlan | PcpOutMask)
            ruleOutBuilder.getTransformRuleDataBuilder.setPopVlan(true)
        }
        val ruleIn = ruleInBuilder.build()
        val ruleIn2 = ruleInBuilder2.build()
        val ruleOut = ruleOutBuilder.build()

        tx.create(ruleIn)
        tx.create(ruleIn2)
        tx.create(ruleOut)

        (Seq(ruleIn.getId, ruleIn2.getId), ruleOut.getId)
    }

    private def buildInsertionChains(tx: Transaction,
                                     inChain: Chain, outChain: Chain,
                                     insertions: Seq[L2Insertion])
    : (Chain, Chain) = {
        val inChainBuilder = inChain.toBuilder
        val outChainBuilder = outChain.toBuilder

        type Accumulator = (Option[L2Insertion], Seq[PersistenceOp])
        // Now recompile all the insertions into new inspected port rules
        val lastInsertion =
            insertions.foldLeft[Option[L2Insertion]](None)(
                (previous: Option[L2Insertion], current: L2Insertion) => {
                    val (ruleInId, ruleOutId) =
                        buildInsertionRules(tx, previous, current,
                                            inChain.getId, outChain.getId)
                    inChainBuilder.addRuleIds(0, ruleInId)
                    outChainBuilder.addRuleIds(0, ruleOutId)

                    Some(current)
                })

        // Finally, add rules to handle the return from the last service port
        lastInsertion match {
            case Some(insertion) =>
                val (rulesIn, ruleOut) = buildEndRules(
                    tx, insertion, inChain.getId, outChain.getId)
                rulesIn.foreach { inChainBuilder.addRuleIds(0, _) }
                outChainBuilder.addRuleIds(0, ruleOut)
            case None =>
        }

        (inChainBuilder.build(), outChainBuilder.build())
    }

    private def cleanChain(tx: Transaction, chain: Chain): Chain = {
        chain.getRuleIdsList.asScala.foreach {
            tx.delete(classOf[Rule], _)
        }
        chain.toBuilder.clearRuleIds().build()
    }

    private def insertionWithSamePortsAlreadyExists(
        insertions: Seq[L2Insertion], newInsertion: L2Insertion): Boolean = {
        insertions.exists(i => i.getId != newInsertion.getId &&
                               i.getPortId == newInsertion.getPortId &&
                               i.getSrvPortId == newInsertion.getSrvPortId)
    }

    private def getAndValidatePorts(tx: Transaction,
                                    insertion: L2Insertion): (Port, Port) = {
        val portId = insertion.getPortId
        val srvPortId = insertion.getSrvPortId

        val port: Port = tx.get(classOf[Port], portId)
        if (port.getSrvInsertionIdsCount > 0) {
            throw new IllegalArgumentException(
                s"Protected port $portId can't have a service")
        }

        val srvPort: Port = tx.get(classOf[Port], srvPortId)
        if (srvPort.getInsertionIdsCount > 0) {
            throw new IllegalArgumentException(
                s"Service port $srvPortId can't have an insertion")
        }
        (port, srvPort)
    }

    def translateInsertionDelete(tx: Transaction,
                                 insertionId: Commons.UUID,
                                 ignoreMissing: Boolean = false): Unit = {
        val insertion = try {
            tx.get(classOf[L2Insertion], insertionId)
        } catch {
            case e: Exception =>
                ignoreMissing match {
                    case true => return
                    case false => throw new IllegalArgumentException(
                        s"Insertion $insertionId doesn't exist")
                }
        }

        val portId = insertion.getPortId
        val srvPortId = insertion.getSrvPortId

        // ensure that ports have the required chains
        val (inChain, outChain) = ensureRedirectChains(tx, portId)
        val (inSrvChain, _) = ensureRedirectChains(tx, srvPortId)

        // clean service port rules
        val cleanedSrvChain = removeServicePortRules(tx, inSrvChain, insertion)
        tx.update(cleanedSrvChain)

        // clean chains
        val cleanedInChain = cleanChain(tx, inChain)
        val cleanedOutChain = cleanChain(tx, outChain)

        // build insertion list
        val (port, _) = getAndValidatePorts(tx, insertion)
        val insertions = tx.getAll(
            classOf[L2Insertion], port.getInsertionIdsList.asScala)
            .filter(_.getId != insertion.getId)
            .sorted

        // translate insertion list to chain
        val (inChainModified, outChainModified) =
            buildInsertionChains(tx, cleanedInChain, cleanedOutChain, insertions)

        tx.update(inChainModified)
        tx.update(outChainModified)
        tx.delete(classOf[L2Insertion], insertionId, ignoresNeo = true)
    }

    def translateInsertionUpdate(tx: Transaction,
                                 insertion: L2Insertion): Unit = {
        val portId = insertion.getPortId
        val srvPortId = insertion.getSrvPortId

        // ensure that ports have the required chains
        val (inChain, outChain) = ensureRedirectChains(tx, portId)
        val (inSrvChain, _) = ensureRedirectChains(tx, srvPortId)

        // update service port rules
        val oldInsertion = tx.get(classOf[L2Insertion], insertion.getId)
        // Don't allow changing the ports
        if (oldInsertion.getPortId != insertion.getPortId ||
                oldInsertion.getSrvPortId != insertion.getSrvPortId) {
            throw new IllegalArgumentException(
                "Port/SrvPort can't be changed for an insertion")
        }

        val cleanedSrvChain =
            removeServicePortRules(tx, inSrvChain, oldInsertion)
        val (modifiedSrvChain, modifiedInsertion) =
            addServicePortRules(tx, cleanedSrvChain, inChain, outChain, insertion)

        tx.update(modifiedSrvChain)
        tx.update(modifiedInsertion)

        // clean chains
        val cleanedInChain = cleanChain(tx, inChain)
        val cleanedOutChain = cleanChain(tx, outChain)

        // build insertion list
        val (port, _) = getAndValidatePorts(tx, insertion)

        val existingInsertions = tx.getAll(
            classOf[L2Insertion], port.getInsertionIdsList.asScala)
            .filter(_.getId != insertion.getId)
        if (insertionWithSamePortsAlreadyExists(existingInsertions,
                                                insertion)) {
            throw new IllegalArgumentException(
                s"Insertion for same port/srvPort pair already exists")
        }
        val insertions = (existingInsertions :+ insertion).sorted

        // translate insertion list to chain
        val (inChainModified, outChainModified) =
            buildInsertionChains(tx, cleanedInChain, cleanedOutChain, insertions)

        tx.update(inChainModified)
        tx.update(outChainModified)
    }

    def translateInsertionCreate(tx: Transaction,
                                 insertion: L2Insertion): Unit = {
        val portId = insertion.getPortId
        val srvPortId = insertion.getSrvPortId

        // ensure that ports have the required chains
        val (inChain, outChain) = ensureRedirectChains(tx, portId)
        val (inSrvChain, _) = ensureRedirectChains(tx, srvPortId)

        // add service port rules for insertion
        val (modifiedSrvChain, modifiedInsertion) =
            addServicePortRules(tx, inSrvChain, inChain, outChain, insertion)

        tx.update(modifiedSrvChain)
        tx.create(modifiedInsertion)

        // clean chains
        val cleanedInChain = cleanChain(tx, inChain)
        val cleanedOutChain = cleanChain(tx, outChain)

        // build insertion list
        val (port, _) = getAndValidatePorts(tx, insertion)

        val insertions = tx.getAll(
            classOf[L2Insertion], port.getInsertionIdsList.asScala)
        if (insertionWithSamePortsAlreadyExists(insertions, insertion)) {
            throw new IllegalArgumentException(
                s"Insertion for same port/srvPort pair already exists")
        }

        // translate insertion list to chain
        val (inChainModified, outChainModified) =
            buildInsertionChains(tx, cleanedInChain, cleanedOutChain, insertions)

        tx.update(inChainModified)
        tx.update(outChainModified)
    }
}
