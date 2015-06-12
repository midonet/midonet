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
import org.midonet.cluster.models.Topology.Rule.{RedirRuleData, JumpRuleData}
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.util.UUIDUtil._

/**
 * Created by Pino on 13/6/15.
 */
object L2InsertionTranslation {
    private final val Timeout = 5 seconds

    final class FutureOps[T](val future: Future[T]) extends AnyVal {
        def getOrThrow: T = Await.result(future, Timeout)
    }

    implicit def toFutureOps[U](future: Future[U]): FutureOps[U] = {
        new FutureOps(future)
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
            if (ret == 0)
                ret = left.getPort.toString compare right.getPort.toString
            if (ret == 0)
                ret = left.getSrvPort.toString compare right.getSrvPort.toString
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
                    removed.get.getSrvPort != added.get.getSrvPort)
                    return false
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
        if (port.getSrvInsertionsCount > 0 || srvPort.getInsertionsCount > 0)
            return false

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
            val ruleIn = Rule.newBuilder().setId(UUID.randomUUID.asProto)
                .setChainId(inSrvChain.getId)
                .setType(Rule.Type.JUMP_RULE)
                .setAction(Rule.Action.JUMP)
                .setJumpRuleData(
                    JumpRuleData.newBuilder
                        .setJumpTo(inChain.getId)
                        .setJumpChainName(inChain.getName).build)
                .setDlSrc(added.get.getMac)
                .build
            val ruleOut = Rule.newBuilder().setId(UUID.randomUUID.asProto)
                .setChainId(inSrvChain.getId)
                .setType(Rule.Type.JUMP_RULE)
                .setAction(Rule.Action.JUMP)
                .setJumpRuleData(
                    JumpRuleData.newBuilder
                        .setJumpTo(outChain.getId)
                        .setJumpChainName(outChain.getName).build)
                .setDlDst(added.get.getMac)
                .build
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
                if (insertionWithSamePortsAlreadyExists)
                    return false
                insertions = insertions :+ added.get
            case UpdateOp(i, _) =>
                // Remove the original L2Insertion
                insertions = insertions.filter(_.getId != removed.get.getId)
                if (insertionWithSamePortsAlreadyExists)
                    return false
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
                ruleIn = Rule.newBuilder().setId(UUID.randomUUID.asProto)
                    .setChainId(inChain.getId)
                    .setDlSrc(x.getMac)
                    .setPushVlan(x.getVlan)
                    .setType(Rule.Type.REDIRECT_RULE)
                    .setAction(Rule.Action.REDIRECT)
                    .setRedirRuleData(
                        RedirRuleData.newBuilder
                            .setTargetPort(x.getSrvPort)
                            .setIngress(false)
                            .setFailOpen(x.getFailOpen).build)
                    .build
                ruleOut = Rule.newBuilder().setId(UUID.randomUUID.asProto)
                    .setChainId(outChain.getId)
                    .setDlDst(x.getMac)
                    .setPushVlan(x.getVlan)
                    .setType(Rule.Type.REDIRECT_RULE)
                    .setAction(Rule.Action.REDIRECT)
                    .setRedirRuleData(
                        RedirRuleData.newBuilder
                            .setTargetPort(x.getSrvPort)
                            .setIngress(false)
                            .setFailOpen(x.getFailOpen).build)
                    .build
                previous match {
                    case None =>
                        // This is the first insertion, match no vlan for both
                        // inbound and outbound. Also, in the inbound case
                        // avoid re-matching this rule when the packet returns
                        // from the last service - by matching the original
                        // ingress port to the inspected port.
                        ruleIn = ruleIn.toBuilder
                            .setNoVlan(true).setOrigInPort(portId).build
                        ruleOut = ruleOut.toBuilder.setNoVlan(true).build
                    case Some(i) =>
                        // This follows another insertion. We need to match
                        // on its srvPort and we need to pop its vlan
                        ruleIn = ruleIn.toBuilder.setOrigInPort(i.getSrvPort)
                            .setVlan(i.getVlan).setPopVlan(true).build
                        // TODO: set the vlan PCP differenty for in/outbound
                        ruleOut = ruleOut.toBuilder.setOrigInPort(i.getSrvPort)
                            .setVlan(i.getVlan).setPopVlan(true).build
                }
                previous = Some(x)
                ops = ops :+ CreateOp(ruleIn)
                inChain = inChain.toBuilder.addRuleIds(ruleIn.getId).build
                ops = ops :+ CreateOp(ruleOut)
                outChain = outChain.toBuilder.addRuleIds(ruleOut.getId).build
            })
        // Finally, add rules to handle the return from the last service port
        if (previous.isDefined) {
            val last = previous.get
            // This rule matches during evaluation of the last service port's
            // inbound chain. Important to pop the VLAN here to avoid
            // matching again after the INGRESS redirect, because the
            // inspected port's inbound chain will be traversed.
            ruleIn = Rule.newBuilder().setId(UUID.randomUUID.asProto)
                .setChainId(inChain.getId)
                .setDlSrc(last.getMac)
                .setOrigInPort(last.getSrvPort)
                .setVlan(last.getVlan)
                .setPopVlan(true)
                .setType(Rule.Type.REDIRECT_RULE)
                .setAction(Rule.Action.REDIRECT)
                .setRedirRuleData(
                    RedirRuleData.newBuilder
                        .setTargetPort(portId)
                        .setIngress(true)
                        .setFailOpen(false).build())
                .build
            // This rule matches during evaluation of the last service port's
            // inbound chain. It will redirect out of the inspected port
            // (EGRESS redirects don't traverse the target port's chains.
            ruleOut = Rule.newBuilder().setId(UUID.randomUUID.asProto)
                .setChainId(outChain.getId)
                .setDlDst(last.getMac)
                .setOrigInPort(last.getSrvPort)
                .setVlan(last.getVlan)
                .setPopVlan(true)
                .setType(Rule.Type.REDIRECT_RULE)
                .setAction(Rule.Action.REDIRECT)
                .setRedirRuleData(
                    RedirRuleData.newBuilder
                        .setTargetPort(portId)
                        .setIngress(false)
                        .setFailOpen(false).build())
                .build
            ops = ops :+ CreateOp(ruleIn)
            inChain = inChain.toBuilder.addRuleIds(ruleIn.getId).build
            ops = ops :+ CreateOp(ruleOut)
            outChain = outChain.toBuilder.addRuleIds(ruleOut.getId).build
        }
        ops = ops ++ Seq(UpdateOp(inSrvChain), UpdateOp(inChain), UpdateOp(outChain))
        store.multi(ops)
        true
    }
}
