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

import org.midonet.cluster.data.storage.Transaction
import org.midonet.cluster.models.Commons.{RuleDirection, UUID}
import org.midonet.cluster.models.Neutron.{NeutronPort, SecurityGroup, SecurityGroupRule}
import org.midonet.cluster.models.Topology.Rule.Type.JUMP_RULE
import org.midonet.cluster.models.Topology.{Chain, IPAddrGroup, Rule}
import org.midonet.cluster.services.c3po.NeutronTranslatorManager.Operation
import org.midonet.cluster.services.c3po.translators.SecurityGroupTranslator._
import org.midonet.cluster.util.UUIDUtil
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.util.StringUtil.indent

class SecurityGroupTranslator
    extends Translator[SecurityGroup] with ChainManager with RuleManager {

    protected override def translateCreate(tx: Transaction,
                                           securityGroup: SecurityGroup)
    : Unit = {

        // Get Neutron rules and divide into egress and ingress rules.
        val neutronRules = securityGroup.getSecurityGroupRulesList.asScala.toList
        val (neutronIngressRules, neutronEgressRules) =
            neutronRules.partition(_.getDirection == RuleDirection.INGRESS)

        val outboundRules = neutronIngressRules.flatMap(
            SecurityGroupRuleManager.translate)
        val inboundRules = neutronEgressRules.flatMap(
            SecurityGroupRuleManager.translate)

        val inboundChainId = inChainId(securityGroup.getId)
        val outboundChainId = outChainId(securityGroup.getId)

        val inboundChain =
            newChain(inboundChainId, egressChainName(securityGroup.getId))
        val outboundChain =
            newChain(outboundChainId, ingressChainName(securityGroup.getId))

        val ipAddrGroup = IPAddrGroup.newBuilder
            .setId(securityGroup.getId)
            .setName(securityGroup.getName)
            .setInboundChainId(inboundChainId)
            .setOutboundChainId(outboundChainId)
            .build()

        tx.create(securityGroup)
        securityGroup.getSecurityGroupRulesList.asScala.foreach(tx.create)
        tx.create(inboundChain)
        tx.create(outboundChain)
        inboundRules.foreach(tx.create)
        outboundRules.foreach(tx.create)
        tx.create(ipAddrGroup)

        if (log.isTraceEnabled) {
            def toString(rules: Seq[Rule]): String = {
                rules.map(indent(_, 4)).mkString("\n")
            }

            log.trace(s"Translated security group: ${securityGroup.getId.asJava} " +
                      s"ipAddrGroup: \n${indent(ipAddrGroup, 4)}\n" +
                      s"inboundChain: \n${indent(inboundChain, 4)}\n" +
                      s"inboundRules: \n${toString(inboundRules)}\n" +
                      s"outboundChain: \n${indent(outboundChain, 4)}\n" +
                      s"outboundRules: \n${toString(outboundRules)}\n")
        }
    }

    protected override def translateUpdate(tx: Transaction,
                                           newSecurityGroup: SecurityGroup)
    : Unit = {
        // Neutron doesn't modify rules via SecurityGroup update, but instead
        // always passes in an empty list of rules. The only property modifiable
        // via a Neutron SecurityGroup update that gets copied to a Midonet
        // object is the name, so we can just update that.

        val oldIpAddrGroup = tx.get(classOf[IPAddrGroup], newSecurityGroup.getId)
        val oldSecurityGroup = tx.get(classOf[SecurityGroup], newSecurityGroup.getId)

        tx.update(oldIpAddrGroup.toBuilder
                      .setName(newSecurityGroup.getName).build())
        tx.update(oldSecurityGroup.toBuilder
                      .setName(newSecurityGroup.getName)
                      .setDescription(newSecurityGroup.getDescription)
                      .build())
    }


    /* Keep the original model as is by default. Override if the model does not
     * need to be maintained, or need some special handling. */
    override protected def retainHighLevelModel(tx: Transaction,
                                                op: Operation[SecurityGroup])
    : List[Operation[SecurityGroup]] = {
        List()
    }

    protected override def translateDelete(tx: Transaction,
                                           securityGroup: SecurityGroup)
    : Unit = {
        // MI-1210: On at least one occasion, Neutron has attempted to delete a
        // SecurityGroup that still had at least one port associated with it, so
        // make sure to clean up any Port references to this SecurityGroup.
        cleanUpPortBackrefs(tx, securityGroup.getId)

        // Delete associated SecurityGroupRules.
        securityGroup.getSecurityGroupRulesList.asScala.foreach { rule =>
            tx.delete(classOf[SecurityGroupRule], rule.getId, ignoresNeo = true)
        }
        tx.delete(classOf[Chain], inChainId(securityGroup.getId), ignoresNeo = true)
        tx.delete(classOf[Chain], outChainId(securityGroup.getId), ignoresNeo = true)
        tx.delete(classOf[IPAddrGroup], securityGroup.getId, ignoresNeo = true)
        tx.delete(classOf[SecurityGroup], securityGroup.getId, ignoresNeo = true)

    }

    private def cleanUpPortBackrefs(tx: Transaction, sgId: UUID): Unit = {
        val sgInChainId = inChainId(sgId)
        val sgOutChainId = outChainId(sgId)
        val ipAddrGrp = tx.get(classOf[IPAddrGroup], sgId)
        for (ipAddrPorts <- ipAddrGrp.getIpAddrPortsList.asScala;
             portId <- ipAddrPorts.getPortIdsList.asScala) {
            log.warn("SecurityGroup {} deleted while still associated with " +
                     "Port {}. This should not happen, but will be handled " +
                     "correctly.", Array[AnyRef](sgId, portId))

            // Get the NeutronPort and remove the reference to this SG.
            val nPort = tx.get(classOf[NeutronPort], portId)
            val idx = nPort.getSecurityGroupsList.indexOf(sgId)
            val bldr = nPort.toBuilder.removeSecurityGroups(idx)
            tx.update(bldr.build())

            // Remove the jump rules from the port's chains.
            val Seq(inChain, outChain) = tx.getAll(
                classOf[Chain], Seq(inChainId(portId), outChainId(portId)))
            val ruleIds = inChain.getRuleIdsList ++ outChain.getRuleIdsList
            val rules = tx.getAll(classOf[Rule], ruleIds)
            for (rule <- rules if rule.getType == JUMP_RULE) {
                val jumpChainId = rule.getJumpRuleData.getJumpChainId
                if (jumpChainId == sgInChainId || jumpChainId == sgOutChainId) {
                    tx.delete(classOf[Rule], rule.getId)
                }
            }
        }
    }
}

object SecurityGroupTranslator {
    def ingressChainName(sgId: UUID) =
        "OS_SG_" + UUIDUtil.toString(sgId) + "_" + RuleDirection.INGRESS

    def egressChainName(sgId: UUID) =
        "OS_SG_" + UUIDUtil.toString(sgId) + "_" + RuleDirection.EGRESS
}
