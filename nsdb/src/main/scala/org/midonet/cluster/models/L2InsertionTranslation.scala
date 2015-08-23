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

package org.midonet.cluster.models

import java.util.UUID

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

import org.midonet.cluster.data.storage._
import org.midonet.cluster.models.Commons.Condition
import org.midonet.cluster.models.Topology.Rule.{JumpRuleData, RedirRuleData}
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.util.UUIDUtil._

object L2InsertionTranslation {

    private final val Timeout = 5 seconds

    final class FutureOps[T](val future: Future[T]) extends AnyVal {

        def getOrThrow: T = Await.result(future, Timeout)
    }

    implicit def toFutureOps[U](future: Future[U]): FutureOps[U] = {
        new FutureOps(future)
    }

    def setConditions(ruleBuilder: Rule.Builder,
                      fn: (Condition.Builder) => Unit): Rule.Builder = {
        fn(ruleBuilder.getConditionBuilder)
        ruleBuilder
    }

    def ensureRedirectChains(store: Storage,
                             portId: UUID): (Chain, Chain) = {
        var port = store.get(classOf[Port], portId.asProto).getOrThrow
        var ops = List.empty[PersistenceOp]
        if (port.getInboundChainCount == 0) {
            val chain: Chain = Chain.newBuilder()
                .setId(UUID.randomUUID.asProto)
                .setName("InboundRedirect" + port.getId.toString).build
            ops = ops :+ CreateOp(chain)
            port = port.toBuilder.addInboundChain(chain.getId).build
        }
        if (port.getOutboundChainCount == 0) {
            val chain = Chain.newBuilder()
                .setId(UUID.randomUUID.asProto)
                .setName("OutboundRedirect" + port.getId.toString).build
            ops = ops :+ CreateOp(chain)
            port = port.toBuilder.addOutboundChain(chain.getId).build
        }
        if (ops.size > 0) {
            ops = ops :+ UpdateOp(port)
            store.multi(ops)
        }
        (store.get(classOf[Chain], port.getInboundChain(0)).getOrThrow,
            store.get(classOf[Chain], port.getOutboundChain(0)).getOrThrow)
    }

    implicit object InsertOrdering extends Ordering[L2Insertion] {

        // Insertions should be sorted by position, tie-break with ports
        def compare(left: L2Insertion, right: L2Insertion) = {
            var ret: Int = left.getPosition compare right.getPosition
            if (ret == 0) {
                ret = left.getPort.toString compare right.getPort.toString
            }
            if (ret == 0) {
                ret = left.getSrvPort.toString compare right.getSrvPort.toString
            }
            ret
        }
    }

    def updateInsertions(store: Storage, op: PersistenceOp): Boolean = {
        // Get the insertion in question
        var removed: Option[L2Insertion] = None
        var added: Option[L2Insertion] = None
        var ops = Seq.empty[PersistenceOp]
        val (portId, srvPortId) = op match {
            case CreateOp(i) =>
                added = Some(i.asInstanceOf[L2Insertion])
                // Don't add the create operation yet... it needs references
                // to the corresponding Jump rules for the service port.
                (added.get.getPort, added.get.getSrvPort)
            case UpdateOp(i, _) =>
                added = Some(i.asInstanceOf[L2Insertion])
                // Don't add this create operation yet... it needs modification
                removed = Some(store.get(
                    classOf[L2Insertion], added.get.getId).getOrThrow)
                // Add the delete operation for this old object. Zoom will do
                // the right thing for the delete/add with identical UUID
                ops = ops :+ DeleteOp(classOf[L2Insertion], added.get.getId)
                // Don't allow changing the ports
                if (removed.get.getPort != added.get.getPort ||
                    removed.get.getSrvPort != added.get.getSrvPort) {
                    return false
                }
                (added.get.getPort, added.get.getSrvPort)
            case DeleteOp(_, id, ignoreMissing) =>
                try {
                    removed = Some(
                        store.get(classOf[L2Insertion], id).getOrThrow)
                }
                catch {
                    case e: Exception =>
                        ignoreMissing match {
                            case true => return true
                            case false => return false
                        }
                }
                // Add the delete operation for this insertion.
                ops = ops :+ op
                (removed.get.getPort, removed.get.getSrvPort)
            case _ =>
                return false
        }

        var (inChain, outChain) = ensureRedirectChains(store, portId)
        var (inSrvChain, _) = ensureRedirectChains(store, srvPortId)

        // Get the inspected and service port
        var port: Port = store.get(classOf[Port], portId).getOrThrow
        var srvPort: Port = store.get(classOf[Port], srvPortId).getOrThrow

        // The inspected port should not already be a service
        // The service port should not already be inspected
        if (port.getSrvInsertionsCount > 0 || srvPort.getInsertionsCount > 0) {
            return false
        }

        // Clear all the rules from the inspected port's chains
        ops = ops ++ inChain.getRuleIdsList.asScala.map {
            DeleteOp(classOf[Rule], _)
        }
        ops = ops ++ outChain.getRuleIdsList.asScala.map {
            DeleteOp(classOf[Rule], _)
        }
        inChain = inChain.toBuilder.clearRuleIds().build
        outChain = outChain.toBuilder.clearRuleIds().build

        // Delete the service port's 2 ingress rules
        if (removed.isDefined) {
            ops = ops :+ DeleteOp(classOf[Rule], removed.get.getSrvRuleIn)
            var index = inSrvChain.getRuleIdsList
                .indexOf(removed.get.getSrvRuleIn)
            inSrvChain = inSrvChain.toBuilder.removeRuleIds(index).build
            ops = ops :+ DeleteOp(classOf[Rule], removed.get.getSrvRuleOut)
            index = inSrvChain.getRuleIdsList.indexOf(removed.get.getSrvRuleOut)
            inSrvChain = inSrvChain.toBuilder.removeRuleIds(index).build
        }
        // Add the service port's 2 ingress rule
        if (added.isDefined) {
            var ruleIn = setConditions(Rule.newBuilder(),
            { cond => cond.setDlSrc(added.get.getMac)})
                .setId(UUID.randomUUID.asProto)
                .setChainId(inSrvChain.getId)
                .setType(Rule.Type.JUMP_RULE)
                .setAction(Rule.Action.JUMP)
                .setJumpRuleData(
                    JumpRuleData.newBuilder
                        .setJumpChainId(inChain.getId)
                        .setJumpChainName(inChain.getName).build)
                .build
            var ruleOut = setConditions(Rule.newBuilder(),
            { c => c.setDlDst(added.get.getMac)})
                .setId(UUID.randomUUID.asProto)
                .setChainId(inSrvChain.getId)
                .setType(Rule.Type.JUMP_RULE)
                .setAction(Rule.Action.JUMP)
                .setJumpRuleData(
                    JumpRuleData.newBuilder
                        .setJumpChainId(outChain.getId)
                        .setJumpChainName(outChain.getName).build)
                .build
            // Does this insertion use vlan? Matching on the vlan, including
            // PCP allows correct redirection of traffic between two VMs on
            // the same bridge. The MACs alone are not sufficient.
            added.get.getVlan match {
                case 0 =>
                case v =>
                    ruleIn = setConditions(ruleIn.toBuilder,
                    { c => c.setVlan(v | (1 << 13))}).build
                    ruleOut = setConditions(ruleOut.toBuilder,
                    { c => c.setVlan(v | (2 << 13))}).build
            }
            ops = ops :+ CreateOp(ruleIn)
            ops = ops :+ CreateOp(ruleOut)
            inSrvChain = inSrvChain.toBuilder.addRuleIds(ruleIn.getId).build
            inSrvChain = inSrvChain.toBuilder.addRuleIds(ruleOut.getId).build
            added = Some(added.get.toBuilder
                             .setSrvRuleIn(ruleIn.getId)
                             .setSrvRuleOut(ruleOut.getId).build)
            // Now that we've set the references to the Jump rules, add the op
            ops = ops :+ CreateOp(added.get)
        }

        // Get all the insertions for the inspected port
        var insertions = store.getAll(
            classOf[L2Insertion],
            port.getInsertionsList.asScala).getOrThrow

        def insertionWithSamePortsAlreadyExists = {
            insertions.find(
                x => x.getPort == portId && x.getSrvPort == srvPortId).isDefined
        }

        // Update this list of insertions for translation. Note that the
        // persistence operation was already added at the top.
        op match {
            case CreateOp(i) =>
                // Don't allow two insertions with same port and srvPort
                if (insertionWithSamePortsAlreadyExists) {
                    return false
                }
                insertions = insertions :+ added.get
            case UpdateOp(i, _) =>
                // Remove the original L2Insertion
                insertions = insertions.filter(_.getId != removed.get.getId)
                if (insertionWithSamePortsAlreadyExists) {
                    return false
                }
                // Now add the new insertion
                insertions = insertions :+ added.get
            case DeleteOp(_, id, _) =>
                // Remove the deleted insertion
                insertions = insertions.filter(_.getId != removed.get.getId)
            case _ =>
                return false
        }
        // Now sort the insertions
        insertions = insertions.sorted
        var previous: Option[L2Insertion] = None
        var ruleIn, ruleOut: Rule = null
        // Now recompile all the insertions into new inspected port rules
        insertions.foreach(
            x => {
                ruleIn = setConditions(Rule.newBuilder(),
                { c => c.setDlSrc(x.getMac)})
                    .setId(UUID.randomUUID.asProto)
                    .setChainId(inChain.getId)
                    .setType(Rule.Type.REDIRECT_RULE)
                    .setAction(Rule.Action.REDIRECT)
                    .setRedirRuleData(
                        RedirRuleData.newBuilder
                            .setTargetPort(x.getSrvPort)
                            .setIngress(false)
                            .setFailOpen(x.getFailOpen).build)
                    .build
                ruleOut = setConditions(Rule.newBuilder(),
                { c => c.setDlDst(x.getMac)})
                    .setId(UUID.randomUUID.asProto)
                    .setChainId(outChain.getId)
                    .setType(Rule.Type.REDIRECT_RULE)
                    .setAction(Rule.Action.REDIRECT)
                    .setRedirRuleData(
                        RedirRuleData.newBuilder
                            .setTargetPort(x.getSrvPort)
                            .setIngress(false)
                            .setFailOpen(x.getFailOpen).build)
                    .build
                // Does this insertion use vlan?
                x.getVlan match {
                    case 0 =>
                    case v =>
                        ruleIn = ruleIn.toBuilder
                            .setPushVlan(v | (1 << 13)).build
                        ruleOut = ruleOut.toBuilder
                            .setPushVlan(v | (2 << 13)).build
                }
                previous match {
                    case None =>
                        // First insertion. Avoid re-matching these rules by
                        // placing them last. The inbound rule can also match
                        // on input port.
                        ruleIn = setConditions(ruleIn.toBuilder,
                        { c => c.addInPortIds(portId)}).build
                    case Some(i) =>
                        // This follows another insertion. We need to match
                        // on its srvPort and we *may* need to pop its vlan
                        ruleIn = setConditions(ruleIn.toBuilder, { c =>
                            c.addInPortIds(i.getSrvPort)
                        })
                            .build
                        ruleOut = setConditions(ruleOut.toBuilder, { c =>
                            c.addInPortIds(i.getSrvPort)
                        })
                            .build
                        // Did the previous insertion use vlan?
                        i.getVlan match {
                            case 0 =>
                            case v =>
                                ruleIn = setConditions(ruleIn.toBuilder,
                                { c => c.setVlan(v | (1 << 13))})
                                    .setPopVlan(true).build
                                ruleOut = setConditions(ruleOut.toBuilder,
                                { c => c.setVlan(v | (2 << 13))})
                                    .setPopVlan(true).build
                        }

                }
                previous = Some(x)
                ops = ops :+ CreateOp(ruleIn)
                inChain = inChain.toBuilder.addRuleIds(0, ruleIn.getId).build
                ops = ops :+ CreateOp(ruleOut)
                outChain = outChain.toBuilder.addRuleIds(0, ruleOut.getId).build
            })
        // Finally, add rules to handle the return from the last service port
        if (previous.isDefined) {
            val last = previous.get
            // This rule matches during evaluation of the last service port's
            // inbound chain. It will redirect into the inspected port and
            // therefore this rule's chain will be re-evaluated. This rule
            // won't match on re-insertion because we compare input port to
            // the last service port.
            ruleIn = setConditions(Rule.newBuilder(),
            { c => c.setDlSrc(last.getMac).addInPortIds(last.getSrvPort)})
                .setId(UUID.randomUUID.asProto)
                .setChainId(inChain.getId)
                .setPushVlan(1 << 15)
                .setType(Rule.Type.REDIRECT_RULE)
                .setAction(Rule.Action.REDIRECT)
                .setRedirRuleData(
                    RedirRuleData.newBuilder
                        .setTargetPort(portId)
                        .setIngress(true)
                        .setFailOpen(false).build())
                .build
            // This rule matching the special vlan is necessary to avoid
            // re-matching the rule that starts the redirection chain and allows
            // that one not to check "no vlan". The implementation therefore
            // supports redirecting vlan-tagged packets.
            val ruleIn2 = setConditions(Rule.newBuilder(),
            { c => c.setDlSrc(last.getMac).addInPortIds(portId)
                .setVlan(1 << 15)
            })
                .setId(UUID.randomUUID.asProto)
                .setChainId(inChain.getId)
                .setPopVlan(true)
                .setType(Rule.Type.LITERAL_RULE)
                .setAction(Rule.Action.ACCEPT)
                .build
            // This rule matches during evaluation of the last service port's
            // inbound chain. It will redirect out of the inspected port and
            // the packet will not re-traverse the inspected port's chains.
            ruleOut = setConditions(Rule.newBuilder(),
            { c => c.setDlDst(last.getMac).addInPortIds(last.getSrvPort)})
                .setId(UUID.randomUUID.asProto)
                .setChainId(outChain.getId)
                .setType(Rule.Type.REDIRECT_RULE)
                .setAction(Rule.Action.REDIRECT)
                .setRedirRuleData(
                    RedirRuleData.newBuilder
                        .setTargetPort(portId)
                        .setIngress(false)
                        .setFailOpen(false).build())
                .build
            // Did the previous insertion use vlan?
            last.getVlan match {
                case 0 =>
                case v =>
                    ruleIn = setConditions(ruleIn.toBuilder, { c => c
                        .setVlan(v | (1 << 13))
                    })
                        .setPopVlan(true).build
                    ruleOut = setConditions(ruleOut.toBuilder, { c => c
                        .setVlan(v | (2 << 13))
                    })
                        .setPopVlan(true).build
            }
            ops = ops :+ CreateOp(ruleIn)
            ops = ops :+ CreateOp(ruleIn2)
            inChain = inChain.toBuilder.addRuleIds(0, ruleIn2.getId)
                .addRuleIds(0, ruleIn.getId).build
            ops = ops :+ CreateOp(ruleOut)
            outChain = outChain.toBuilder.addRuleIds(0, ruleOut.getId).build
        }
        ops = ops ++
              Seq(UpdateOp(inSrvChain), UpdateOp(inChain), UpdateOp(outChain))
        store.multi(ops)
        true
    }
}
