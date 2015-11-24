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
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

import org.midonet.cluster.data.storage._
import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.util.UUIDUtil._

object L2InsertionTranslation {

    private final val Timeout = 15 seconds
    private final val PcpInMask = 1 << 13
    private final val PcpOutMask = 2 << 13
    private final val PcpFinalMask = 1 << 15

    private final class FutureOps[T](val future: Future[T]) extends AnyVal {

        def getOrThrow: T = Await.result(future, Timeout)
    }

    private implicit def toFutureOps[U](future: Future[U]): FutureOps[U] = {
        new FutureOps(future)
    }

    private def ensureRedirectChains(store: Storage, portId: Commons.UUID)
            : (Chain, Chain, Seq[PersistenceOp]) = {
        val port = store.get(classOf[Port], portId).getOrThrow
        val portBuilder = port.toBuilder
        var ops = Seq.empty[PersistenceOp]

        val inFilter = if (!port.hasL2InsertionInfilterId) {
            val chain: Chain = Chain.newBuilder()
                .setId(UUID.randomUUID.asProto)
                .setName("InboundRedirect" + port.getId.asJava.toString).build
            ops = ops :+ CreateOp(chain)
            portBuilder.setL2InsertionInfilterId(chain.getId)
            chain
        } else store.get(classOf[Chain], port.getL2InsertionInfilterId).getOrThrow

        val outFilter = if (!port.hasL2InsertionOutfilterId) {
            val chain = Chain.newBuilder()
                .setId(UUID.randomUUID.asProto)
                .setName("OutboundRedirect" + port.getId.asJava.toString).build
            ops = ops :+ CreateOp(chain)
            portBuilder.setL2InsertionOutfilterId(chain.getId)
            chain
        } else store.get(classOf[Chain], port.getL2InsertionOutfilterId).getOrThrow

        if (ops.nonEmpty) {
            ops = ops :+ UpdateOp(portBuilder.build())
        }
        (inFilter, outFilter, ops)
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

    private def removeServicePortRules(chain: Chain, insertion: L2Insertion)
            : (Chain, Seq[PersistenceOp]) = {
        val ops = Seq(DeleteOp(classOf[Rule], insertion.getSrvRuleInId),
                      DeleteOp(classOf[Rule], insertion.getSrvRuleOutId))
        val chainBuilder = chain.toBuilder
        chainBuilder.clearRuleIds().addAllRuleIds(
            chain.getRuleIdsList.asScala.filter(
                (id: Commons.UUID) => {
                    id != insertion.getSrvRuleInId &&
                        id != insertion.getSrvRuleOutId
                }).asJava)
        (chainBuilder.build(), ops)
    }

    private def addServicePortRules(chain: Chain,
                                    insertionInChain: Chain,
                                    insertionOutChain: Chain,
                                    insertion: L2Insertion)
            : (Chain, L2Insertion, Seq[PersistenceOp]) = {
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
        val ops = Seq(CreateOp(ruleIn), CreateOp(ruleOut))
        val modifiedChain = chain.toBuilder
            .addRuleIds(ruleIn.getId)
            .addRuleIds(ruleOut.getId).build()
        val modifiedInsertion = insertion.toBuilder
            .setSrvRuleInId(ruleIn.getId)
            .setSrvRuleOutId(ruleOut.getId).build()

        (modifiedChain, modifiedInsertion, ops)
    }

    private def buildInsertionRules(previous: Option[L2Insertion],
                                    cur: L2Insertion,
                                    inChainId: Commons.UUID,
                                    outChainId: Commons.UUID)
            : (Commons.UUID, Commons.UUID, Seq[PersistenceOp]) = {
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

        (ruleIn.getId, ruleOut.getId, Seq(CreateOp(ruleIn), CreateOp(ruleOut)))
    }

    private def buildEndRules(endInsertion: L2Insertion,
                              inChainId: Commons.UUID,
                              outChainId: Commons.UUID)
            : (Seq[Commons.UUID], Commons.UUID, Seq[PersistenceOp]) = {
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

        (Seq(ruleIn.getId, ruleIn2.getId), ruleOut.getId,
         Seq(CreateOp(ruleIn), CreateOp(ruleIn2), CreateOp(ruleOut)))
    }

    private def buildInsertionChains(inChain: Chain, outChain: Chain,
                                     insertions: Seq[L2Insertion])
            : (Chain, Chain, Seq[PersistenceOp]) = {
        val inChainBuilder = inChain.toBuilder
        val outChainBuilder = outChain.toBuilder

        type Accumulator = (Option[L2Insertion], Seq[PersistenceOp])
        // Now recompile all the insertions into new inspected port rules
        val (lastInsertion, rulesOps) =
            insertions.foldLeft[Accumulator]((None, Seq.empty[PersistenceOp]))(
                (previous: Accumulator, cur: L2Insertion) => {
                    val (ruleInId, ruleOutId, ruleOps) =
                        buildInsertionRules(previous._1, cur,
                                            inChain.getId, outChain.getId)
                    inChainBuilder.addRuleIds(0, ruleInId)
                    outChainBuilder.addRuleIds(0, ruleOutId)

                    (Some(cur), previous._2 ++ ruleOps)
                })

        // Finally, add rules to handle the return from the last service port
        val endRuleOps = lastInsertion match {
            case Some(insertion) =>
                val (rulesIn, ruleOut, ops) = buildEndRules(
                    insertion, inChain.getId, outChain.getId)
                rulesIn.foreach { inChainBuilder.addRuleIds(0, _) }
                outChainBuilder.addRuleIds(0, ruleOut)
                ops
            case None => Seq.empty[PersistenceOp]
        }

        (inChainBuilder.build(), outChainBuilder.build(),
         rulesOps ++ endRuleOps)
    }

    private def cleanChain(chain: Chain): (Chain, Seq[PersistenceOp]) = {
        val ops = chain.getRuleIdsList.asScala.map {
            DeleteOp(classOf[Rule], _)
        }
        (chain.toBuilder.clearRuleIds().build(), ops)
    }

    private def insertionWithSamePortsAlreadyExists(
        insertions: Seq[L2Insertion], newInsertion: L2Insertion): Boolean = {
        insertions.exists(x => x.getPortId == newInsertion.getPortId &&
                               x.getSrvPortId == newInsertion.getSrvPortId)
    }

    private def getAndValidatePorts(store: Storage,
                                    insertion: L2Insertion): (Port, Port) = {
        val portId = insertion.getPortId
        val srvPortId = insertion.getSrvPortId

        val port: Port = store.get(classOf[Port], portId).getOrThrow
        if (port.getSrvInsertionIdsCount > 0) {
            throw new IllegalArgumentException(
                s"Protected port $portId can't have a service")
        }

        val srvPort: Port = store.get(classOf[Port], srvPortId).getOrThrow
        if (srvPort.getInsertionIdsCount > 0) {
            throw new IllegalArgumentException(
                s"Service port $srvPortId can't have an insertion")
        }
        (port, srvPort)
    }

    def translateInsertionDelete(store: Storage,
                                 insertionId: Commons.UUID,
                                 ignoreMissing: Boolean = false): Unit = {
        val insertion = try {
            store.get(classOf[L2Insertion], insertionId).getOrThrow
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
        val (inChain, outChain, ensureChainOps) =
            ensureRedirectChains(store, portId)
        val (inSrvChain, _, ensureSrvChainOps) =
            ensureRedirectChains(store, srvPortId)

        // clean service port rules
        val (cleanedSrvChain, removeSrvChainOps) =
            removeServicePortRules(inSrvChain, insertion)
        val serviceChainOps = removeSrvChainOps ++
            Seq(UpdateOp(cleanedSrvChain))

        // clean chains
        val (cleanedInChain, cleanInChainOps) = cleanChain(inChain)
        val (cleanedOutChain, cleanOutChainOps) = cleanChain(outChain)

        // build insertion list
        val (port, _) = getAndValidatePorts(store, insertion)
        val insertions = store.getAll(
            classOf[L2Insertion], port.getInsertionIdsList.asScala)
            .getOrThrow
            .filter(_.getId != insertion.getId)
            .sorted

        // translate insertion list to chain
        val (inChainModified, outChainModified, insertionOps) =
            buildInsertionChains(cleanedInChain, cleanedOutChain, insertions)

        val ops = ensureChainOps ++ ensureSrvChainOps ++ serviceChainOps ++
            cleanInChainOps ++ cleanOutChainOps ++ insertionOps ++
            Seq(UpdateOp(inChainModified), UpdateOp(outChainModified),
                DeleteOp(classOf[L2Insertion], insertionId, ignoreMissing))

        store.multi(ops)
    }

    def translateInsertionUpdate(store: Storage,
                                 insertion: L2Insertion): Unit = {
        val portId = insertion.getPortId
        val srvPortId = insertion.getSrvPortId

        // ensure that ports have the required chains
        val (inChain, outChain, ensureChainOps) =
            ensureRedirectChains(store, portId)
        val (inSrvChain, _, ensureSrvChainOps) =
            ensureRedirectChains(store, srvPortId)

        // update service port rules
        val oldInsertion = store.get(classOf[L2Insertion],
                                     insertion.getId).getOrThrow
        // Don't allow changing the ports
        if (oldInsertion.getPortId != insertion.getPortId ||
                oldInsertion.getSrvPortId != insertion.getSrvPortId) {
            throw new IllegalArgumentException(
                "Port/SrvPort can't be changed for an insertion")
        }

        val (cleanedSrvChain, removeSrvChainOps) =
            removeServicePortRules(inSrvChain, oldInsertion)
        val (modifiedSrvChain, modifiedInsertion, addSrvChainOps) =
            addServicePortRules(cleanedSrvChain, inChain, outChain, insertion)

        val serviceChainOps = removeSrvChainOps ++ addSrvChainOps ++
            Seq(UpdateOp(modifiedSrvChain), UpdateOp(modifiedInsertion))

        // clean chains
        val (cleanedInChain, cleanInChainOps) = cleanChain(inChain)
        val (cleanedOutChain, cleanOutChainOps) = cleanChain(outChain)

        // build insertion list
        val (port, _) = getAndValidatePorts(store, insertion)

        val existingInsertions = store.getAll(
            classOf[L2Insertion], port.getInsertionIdsList.asScala)
            .getOrThrow
            .filter(_.getId != insertion.getId)
        if (insertionWithSamePortsAlreadyExists(existingInsertions,
                                                insertion)) {
            throw new IllegalArgumentException(
                s"Insertion for same port/srvPort pair already exists")
        }
        val insertions = (existingInsertions :+ insertion).sorted

        // translate insertion list to chain
        val (inChainModified, outChainModified, insertionOps) =
            buildInsertionChains(cleanedInChain, cleanedOutChain, insertions)

        val ops = ensureChainOps ++ ensureSrvChainOps ++ serviceChainOps ++
            cleanInChainOps ++ cleanOutChainOps ++ insertionOps ++
            Seq(UpdateOp(inChainModified), UpdateOp(outChainModified))

        store.multi(ops)
    }

    def translateInsertionCreate(store: Storage,
                                 insertion: L2Insertion): Unit = {
        val portId = insertion.getPortId
        val srvPortId = insertion.getSrvPortId

        // ensure that ports have the required chains
        val (inChain, outChain, ensureChainOps) =
            ensureRedirectChains(store, portId)
        val (inSrvChain, _, ensureSrvChainOps) =
            ensureRedirectChains(store, srvPortId)

        // add service port rules for insertion
        val (modifiedSrvChain, modifiedInsertion, addSrvChainOps) =
            addServicePortRules(inSrvChain, inChain, outChain, insertion)

        val serviceChainOps = addSrvChainOps ++ Seq(UpdateOp(modifiedSrvChain),
                                                    CreateOp(modifiedInsertion))

        // clean chains
        val (cleanedInChain, cleanInChainOps) = cleanChain(inChain)
        val (cleanedOutChain, cleanOutChainOps) = cleanChain(outChain)

        // build insertion list
        val (port,_) = getAndValidatePorts(store, insertion)

        val existingInsertions = store.getAll(
            classOf[L2Insertion], port.getInsertionIdsList.asScala).getOrThrow
        if (insertionWithSamePortsAlreadyExists(existingInsertions,
                                                insertion)) {
            throw new IllegalArgumentException(
                s"Insertion for same port/srvPort pair already exists")
        }
        val insertions = (existingInsertions :+ insertion).sorted

        // translate insertion list to chain
        val (inChainModified, outChainModified, insertionOps) =
            buildInsertionChains(cleanedInChain, cleanedOutChain, insertions)

        val ops = ensureChainOps ++ ensureSrvChainOps ++ serviceChainOps ++
            cleanInChainOps ++ cleanOutChainOps ++ insertionOps ++
            Seq(UpdateOp(inChainModified), UpdateOp(outChainModified))

        store.multi(ops)
    }
}
