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

import org.midonet.cluster.services.c3po.midonet._
import org.midonet.cluster.data.storage.ReadOnlyStorage
import org.midonet.cluster.models.Commons.{RuleDirection, UUID}
import org.midonet.cluster.models.Neutron.SecurityGroupRule
import org.midonet.cluster.models.Topology.{Chain, Rule}
import org.midonet.midolman.topology.ChainManager
import org.midonet.util.concurrent.toFutureOps

class SecurityGroupRuleTranslator(storage: ReadOnlyStorage)
    extends NeutronTranslator[SecurityGroupRule] with ChainManager {

    /*
     * Need to create the rule, but also need to add it to the security
     * group chain that it is assigned to.
     */
    protected override def translateCreate(sgr: SecurityGroupRule)
    : MidoOpList = {

        val chainId = sgr.getDirection match {
            case RuleDirection.INGRESS => outChainId(sgr.getSecurityGroupId)
            case _ => inChainId(sgr.getSecurityGroupId)
        }

        val chain = storage.get(classOf[Chain], chainId).await()

        val ruleIds = chain.getRuleIdsList.asScala ++ List(sgr.getId)

        val updatedChain = Chain.newBuilder()
                                .setId(chainId)
                                .setName(chain.getName)
                                .addAllRuleIds(ruleIds.asJava)
                                .build()

        List(Create(SecurityGroupRuleManager.translate(sgr)),
             Update(updatedChain))
    }

    protected override def translateUpdate(newSgr: SecurityGroupRule)
    : MidoOpList = {
        throw new IllegalArgumentException(
            "SecurityGroupRule update not supported.")
    }

    /*
     * Need to delete the rule, but also need to delete it from the security
     * group chain that it is assigned to.
     */
    protected override def translateDelete(sgrId: UUID) : MidoOpList = {
        val rule = storage.get(classOf[Rule], sgrId).await()
        val chain = storage.get(classOf[Chain], rule.getChainId).await()

        val ruleIds = chain.getRuleIdsList.asScala.filter(_ != sgrId)

        val updatedChain = Chain.newBuilder()
                            .setId(chain.getId)
                            .setName(chain.getName)
                            .addAllRuleIds(ruleIds.asJava)
                            .build()

        List(Delete(classOf[Rule], rule.getId), Update(updatedChain))
    }
}
