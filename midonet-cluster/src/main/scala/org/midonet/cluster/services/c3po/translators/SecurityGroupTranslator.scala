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

import com.google.protobuf.Message

import org.midonet.cluster.data.storage.{ReadOnlyStorage, Transaction}
import org.midonet.cluster.models.Commons.{RuleDirection, UUID}
import org.midonet.cluster.models.Neutron.{SecurityGroup, SecurityGroupRule}
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
    : OperationList = {

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

        List()
    }

    protected override def translateUpdate(tx: Transaction,
                                           newSecurityGroup: SecurityGroup)
    : OperationList = {
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

        List()
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
    : List[Operation[_ <: Message]] = {
        // Delete associated SecurityGroupRules.
        securityGroup.getSecurityGroupRulesList.asScala.foreach { rule =>
            tx.delete(classOf[SecurityGroupRule], rule.getId, ignoresNeo = true)
        }
        tx.delete(classOf[Chain], inChainId(securityGroup.getId), ignoresNeo = true)
        tx.delete(classOf[Chain], outChainId(securityGroup.getId), ignoresNeo = true)
        tx.delete(classOf[IPAddrGroup], securityGroup.getId, ignoresNeo = true)
        tx.delete(classOf[SecurityGroup], securityGroup.getId, ignoresNeo = true)
        List()
    }

}

object SecurityGroupTranslator {
    def ingressChainName(sgId: UUID) =
        "OS_SG_" + UUIDUtil.toString(sgId) + "_" + RuleDirection.INGRESS

    def egressChainName(sgId: UUID) =
        "OS_SG_" + UUIDUtil.toString(sgId) + "_" + RuleDirection.EGRESS
}
