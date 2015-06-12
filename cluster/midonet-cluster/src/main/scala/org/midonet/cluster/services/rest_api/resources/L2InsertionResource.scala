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

package org.midonet.cluster.services.rest_api.resources

import java.util.UUID

import javax.ws.rs.core.MediaType.APPLICATION_JSON
import javax.ws.rs.core.{Response, UriInfo}
import javax.xml.ws.handler.PortInfo

import scala.util.Sorting

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import scala.collection.JavaConverters._

import org.midonet.cluster.util.UUIDUtil._
import org.midonet.cluster.data.storage.{CreateOp, UpdateOp,
    DeleteOp, PersistenceOp}
import org.midonet.cluster.models.Topology.Rule.JumpRuleData
import org.midonet.cluster.models.Topology.Rule.RedirRuleData
import org.midonet.cluster.models.Topology.{Port => TopoPort,
    L2Insertion => TopoInsert, Chain => TopoChain, Rule => TopoRule}
import org.midonet.cluster.rest_api.annotation._
import org.midonet.cluster.rest_api.models.{UriResource, L2Insertion}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource._

@RequestScoped
@AllowGet(Array(APPLICATION_L2INSERTION_JSON,
                APPLICATION_JSON))
@AllowList(Array(APPLICATION_L2INSERTION_COLLECTION_JSON,
                 APPLICATION_JSON))
@AllowCreate(Array(APPLICATION_L2INSERTION_JSON,
                   APPLICATION_JSON))
@AllowUpdate(Array(APPLICATION_L2INSERTION_JSON,
                   APPLICATION_JSON))
@AllowDelete
class L2InsertionResource @Inject()(backend: MidonetBackend, uriInfo: UriInfo)
    extends MidonetResource[L2Insertion](backend, uriInfo) {

    def ensureRedirectChains(portId: UUID): (TopoChain, TopoChain) = {
        var port = backend.store.get(classOf[TopoPort], portId.asProto).getOrThrow
        var ops = List.empty[PersistenceOp]
        if (port.getInboundChainCount == 0) {
            val chain: TopoChain = TopoChain.newBuilder()
                .setId(UUID.randomUUID.asProto)
                .setName("InboundRedirect"+port.getId).build
            ops = ops :+ CreateOp(chain)
            port = port.toBuilder.addInboundChain(chain.getId).build
        }
        if (port.getOutboundChainCount == 0) {
            val chain = TopoChain.newBuilder()
                .setId(UUID.randomUUID.asProto)
                .setName("OutboundRedirect"+port.getId).build
            ops = ops :+ CreateOp(chain)
            port = port.toBuilder.addOutboundChain(chain.getId).build
        }
        if (ops.size > 0) {
            ops = ops :+ UpdateOp(port)
            backend.store.multi(ops)
        }
        (backend.store.get(classOf[TopoChain], port.getInboundChain(0)).getOrThrow,
            backend.store.get(classOf[TopoChain], port.getOutboundChain(0)).getOrThrow)
    }

    implicit object InsertOrdering extends Ordering[TopoInsert] {
        // Insertions should be sorted by position, tie-break with ports
        def compare(left: TopoInsert, right: TopoInsert) = {
            var ret: Int = left.getPosition compare right.getPosition
            if (ret == 0)
                ret = left.getPort.toString compare right.getPort.toString
            if (ret == 0)
                ret = left.getSrvPort.toString compare right.getSrvPort.toString
            ret
        }
    }

    def updateInsertions(op: PersistenceOp,
                         res: Response = Response.ok().build()): Response = {
        // Get the insertion in question
        var removed: Option[TopoInsert] = None
        var added: Option[TopoInsert] = None
        var ops = Seq.empty[PersistenceOp]
        val (portId, srvPortId) = op match {
            case CreateOp(i) =>
                added = Some(i.asInstanceOf[TopoInsert])
                // Don't add this create operation yet... it needs modification
                (added.get.getPort, added.get.getSrvPort)
            case UpdateOp(i, _) =>
                added = Some(i.asInstanceOf[TopoInsert])
                // Don't add this create operation yet... it needs modification
                removed = Some(backend.store.get(
                    classOf[TopoInsert], added.get.getId).getOrThrow)
                // Add the delete operation for this old object. Zoom will do
                // the right thing for the delete/add with identical UUID
                ops = ops :+ DeleteOp(classOf[TopoInsert], added.get.getId)
                // Don't allow changing the ports
                if (removed.get.getPort != added.get.getPort ||
                    removed.get.getSrvPort != added.get.getSrvPort)
                    return Response.status(Response.Status.PRECONDITION_FAILED).build()
                (added.get.getPort, added.get.getSrvPort)
            case DeleteOp(_, id, ignoreMissing) =>
                try {
                    removed = Some(backend.store.get(classOf[TopoInsert], id).getOrThrow)
                }
                catch {
                    case e: Exception =>
                        ignoreMissing match {
                            case true => return Response.ok.build()
                            case false => return Response.status(Response.Status.NOT_FOUND).build()
                        }
                }
                // Add the delete operation for this insertion.
                ops = ops :+ op
                (removed.get.getPort, removed.get.getSrvPort)
            case _ =>
                return Response.serverError().build
        }

        var (inChain, outChain) = ensureRedirectChains(portId)
        var (inSrvChain, _) = ensureRedirectChains(srvPortId)

        // Get the inspected and service port
        var port: TopoPort = backend.store.get(classOf[TopoPort], portId).getOrThrow
        var srvPort: TopoPort = backend.store.get(classOf[TopoPort], srvPortId).getOrThrow

        // The inspected port should not already be a service
        // The service port should not already be inspected
        if (port.getSrvInsertionsCount > 0 || srvPort.getInsertionsCount > 0)
            return Response.status(Response.Status.PRECONDITION_FAILED).build()

        // Clear all the rules from the inspected port's chains
        ops = ops ++ inChain.getRuleIdsList.asScala.map{ DeleteOp(classOf[TopoRule], _) }
        ops = ops ++ outChain.getRuleIdsList.asScala.map{ DeleteOp(classOf[TopoRule], _) }
        inChain = inChain.toBuilder.clearRuleIds().build()
        outChain = outChain.toBuilder.clearRuleIds().build()

        // Delete the service port's 2 ingress rules
        if (removed.isDefined) {
            ops = ops :+ DeleteOp(classOf[TopoRule], removed.get.getSrvRuleIn)
            var index = inSrvChain.getRuleIdsList.indexOf(removed.get.getSrvRuleIn)
            inSrvChain = inSrvChain.toBuilder.removeRuleIds(index).build()
            ops = ops :+ DeleteOp(classOf[TopoRule], removed.get.getSrvRuleOut)
            index = inSrvChain.getRuleIdsList.indexOf(removed.get.getSrvRuleOut)
            inSrvChain = inSrvChain.toBuilder.removeRuleIds(index).build()
        }
        // Add the service port's 2 ingress rule
        if (added.isDefined) {
            val ruleIn = TopoRule.newBuilder().setId(UUID.randomUUID.asProto)
                .setChainId(inSrvChain.getId)
                .setType(TopoRule.Type.LITERAL_RULE)
                .setAction(TopoRule.Action.JUMP)
                .setJumpRuleData(
                    JumpRuleData.newBuilder
                        .setJumpTo(inChain.getId)
                        .setJumpChainName(inChain.getName).build)
                .setDlSrc(added.get.getMac)
                .build
            ops = ops :+ CreateOp(ruleIn)
            inSrvChain = inSrvChain.toBuilder.addRuleIds(ruleIn.getId).build()
            var ruleOut = TopoRule.newBuilder().setId(UUID.randomUUID.asProto)
                .setChainId(inSrvChain.getId)
                .setType(TopoRule.Type.LITERAL_RULE)
                .setAction(TopoRule.Action.JUMP)
                .setJumpRuleData(
                    JumpRuleData.newBuilder
                        .setJumpTo(outChain.getId)
                        .setJumpChainName(outChain.getName).build)
                .setDlDst(added.get.getMac)
                .build
            ops = ops :+ CreateOp(ruleOut)
            inSrvChain = inSrvChain.toBuilder.addRuleIds(ruleOut.getId).build()
            added = Some(added.get.toBuilder
                             .setSrvRuleIn(ruleIn.getId)
                             .setSrvRuleOut(ruleOut.getId).build)
        }

        // Get all the insertions for the inspected port
        var insertions = backend.store.getAll(
            classOf[TopoInsert],
            port.getInsertionsList.asScala).getOrThrow

        def insertionWithSamePortsAlreadyExists = {
            insertions.find(
                x => x.getPort == portId && x.getSrvPort == srvPortId).isDefined
        }

        // Update this list of insertions for translation. Note that the
        // persistence operation was already added at the top.
        // While we're at it, we update the service port's rules
        op match {
            case CreateOp(i) =>
                // Don't allow two insertions with same port and srvPort
                if (insertionWithSamePortsAlreadyExists)
                    return Response.status(Response.Status.PRECONDITION_FAILED).build()
                insertions = insertions :+ added.get
            case UpdateOp(i, _) =>
                // Remove the original L2Insertion
                insertions = insertions.filter(_.getId == added.get.getId)
                if (insertionWithSamePortsAlreadyExists)
                    return Response.status(Response.Status.PRECONDITION_FAILED).build()
                // Now add the new insertion
                insertions = insertions :+ added.get
            case DeleteOp(_, id, _) =>
                insertions = insertions.filter(_.getId == removed.get.getId)
            case _ =>
                return Response.serverError().build
        }
        // Now sort the insertions
        insertions = insertions.sorted
        var previous: Option[TopoInsert] = None
        var ruleIn, ruleIn2, ruleOut: TopoRule = null
        // Now recompile all the insertions into new inspected port rules
        insertions.foreach(
            x => {
                ruleIn = TopoRule.newBuilder().setId(UUID.randomUUID.asProto)
                    .setChainId(inChain.getId)
                    .setDlSrc(x.getMac)
                    .setPushVlan(x.getVlan)
                    .setType(TopoRule.Type.REDIRECT_RULE)
                    .setAction(TopoRule.Action.REDIRECT)
                    .setRedirRuleData(
                        RedirRuleData.newBuilder
                            .setTargetPort(x.getSrvPort)
                            .setIngress(false)
                            .setFailOpen(x.getFailOpen).build())
                    .build
                ruleOut = TopoRule.newBuilder().setId(UUID.randomUUID.asProto)
                    .setChainId(outChain.getId)
                    .setDlDst(x.getMac)
                    .setPushVlan(x.getVlan)
                    .setType(TopoRule.Type.REDIRECT_RULE)
                    .setAction(TopoRule.Action.REDIRECT)
                    .setRedirRuleData(
                        RedirRuleData.newBuilder
                            .setTargetPort(x.getSrvPort)
                            .setIngress(false)
                            .setFailOpen(x.getFailOpen).build())
                    .build
                previous match {
                    case None =>
                        // This is the first insertion, match no vlan
                        ruleIn.toBuilder.setNoVlan(true).build
                        ruleOut.toBuilder.setNoVlan(true).build
                    case Some(i) =>
                        // This follows another insertion. We need to match
                        // on its srvPort and we need to pop its vlan
                        ruleIn.toBuilder.addInPortIds(i.getSrvPort)
                            .setPopVlan(true).build
                        ruleOut.toBuilder.addInPortIds(i.getSrvPort)
                            .setPopVlan(true).build
                }
                previous = Some(x)
                ops = ops :+ CreateOp(ruleIn)
                inSrvChain = inChain.toBuilder.addRuleIds(ruleIn.getId).build()
                ops = ops :+ CreateOp(ruleOut)
                inSrvChain = outChain.toBuilder.addRuleIds(ruleOut.getId).build()
            })
        // Finally, add rules to handle the return from the last service port
        if (previous.isDefined) {
            val last = previous.get
            // This rule matches during evaluation of the last service port's
            // inbound chain. Important to pop the VLAN here to avoid
            // matching again after the INGRESS redirect, because the
            // inspected port's inbound chain will be traversed.
            ruleIn = TopoRule.newBuilder().setId(UUID.randomUUID.asProto)
                .setChainId(inChain.getId)
                .setDlSrc(last.getMac)
                .addInPortIds(last.getSrvPort)
                .setPopVlan(true) // Need to pop here in order to avoid matching again after re-ingressing the inspected port
                .setType(TopoRule.Type.REDIRECT_RULE)
                .setAction(TopoRule.Action.REDIRECT)
                .setRedirRuleData(
                    RedirRuleData.newBuilder
                        .setTargetPort(portId)
                        .setIngress(true)
                        .setFailOpen(false).build())
                .build
            // This rule matches during evaluation of the inspected port's
            // inbound chain AFTER the last service returns the packet.
            // It's important to match on the original ingress port because
            // no-vlan is also expected by the very first rule (before
            // any redirects are executed.
            ruleIn2 = TopoRule.newBuilder().setId(UUID.randomUUID.asProto)
                .setChainId(inChain.getId)
                .setDlSrc(last.getMac)
                .addInPortIds(last.getSrvPort)
                .setNoVlan(true)
                .setType(TopoRule.Type.LITERAL_RULE)
                .setAction(TopoRule.Action.ACCEPT)
                .build
            // This rule matches during evaluation of the last service port's
            // inbound chain. Note that EGRESS redirects don't traverse
            // the target port's chains.
            ruleOut = TopoRule.newBuilder().setId(UUID.randomUUID.asProto)
                .setChainId(outChain.getId)
                .setDlDst(last.getMac)
                .setPopVlan(true)
                .setType(TopoRule.Type.REDIRECT_RULE)
                .setAction(TopoRule.Action.REDIRECT)
                .setRedirRuleData(
                    RedirRuleData.newBuilder
                        .setTargetPort(portId)
                        .setIngress(false)
                        .setFailOpen(false).build())
                .build
            ops = ops :+ CreateOp(ruleIn)
            ops = ops :+ CreateOp(ruleIn2)
            inSrvChain = inChain.toBuilder
                .addRuleIds(ruleIn.getId).addRuleIds(ruleIn2.getId).build()
            ops = ops :+ CreateOp(ruleOut)
            inSrvChain = outChain.toBuilder.addRuleIds(ruleOut.getId).build()
        }
        ops = ops ++ Seq(UpdateOp(inSrvChain), UpdateOp(inChain), UpdateOp(outChain))
        backend.store.multi(ops)
        Response.ok().build()
    }

    override protected def createResource[U >: Null <: UriResource]
                                         (resource: U) = {
        updateInsertions(CreateOp(toProto(resource)))
    }

    override protected def updateResource[U >: Null <: UriResource]
                                         (resource: U,
                                          res: Response) = {
        Response.status(Response.Status.FORBIDDEN).build()
    }

    override protected def deleteResource[U >: Null <: UriResource]
                                         (clazz: Class[U], id: Any,
                                          res: Response) = {
        updateInsertions(DeleteOp(clazz, id), res = res)
    }

    protected override def updateFilter = (to: L2Insertion, from: L2Insertion) => {
        to.update(from)
    }

}
