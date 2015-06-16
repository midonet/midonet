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

import org.midonet.util.StringUtil._

import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer

import com.google.protobuf.Message

import org.midonet.cluster.services.c3po.midonet._
import org.midonet.cluster.data.storage.{NotFoundException, ReadOnlyStorage}
import org.midonet.cluster.models.Commons.{RuleDirection, UUID}
import org.midonet.cluster.models.Neutron.{SecurityGroup, SecurityGroupRule}
import org.midonet.cluster.models.Topology.{Chain, Rule}
import org.midonet.util.concurrent.toFutureOps

class SecurityGroupRuleTranslator(storage: ReadOnlyStorage)
    extends NeutronTranslator[SecurityGroupRule] with SecurityGroupManager {

    /*
     * Need to create the rule, but also need to add it to the security
     * group that it is assigned to.
     */
    protected override def translateCreate(sgr: SecurityGroupRule)
    : List[MidoOp[_ <: Message]] = {

        val sg = try storage.get(classOf[SecurityGroup],
                                 sgr.getSecurityGroupId).await() catch {
            case ex: NotFoundException =>
                return List()
        }

        val ops = new ListBuffer[MidoOp[_ <: Message]]
        var neutronRules = sg.getSecurityGroupRulesList.asScala.toList.filter(
            _.getDirection == sgr.getDirection)
        neutronRules ++= List(sgr)

        val ruleIds = neutronRules.map(_.getId)
        val (chainId, chainName) = sgr.getDirection match {
            case RuleDirection.INGRESS =>
                (ChainManager.outChainId(sg.getId), ingressChainName(sg.getId))
            case _ =>
                (ChainManager.inChainId(sg.getId), egressChainName(sg.getId))
        }

        val chain = createChain(sg, chainId, chainName, ruleIds)

        ops += Create(SecurityGroupRuleManager.translate(sgr))
        ops += Update(chain)
        ops.toList
    }

    protected override def translateUpdate(newSgr: SecurityGroupRule)
    : List[MidoOp[_ <: Message]] = {
        /*
         * This should not actually happen. Make it a NO-OP.
         * TODO: should we actually throw and exception?
         */
        List()
    }

    /*
     * Need to delete the rule, but also need to delete it from the security
     * group that it is assigned to.
     */
    protected override def translateDelete(sgrId: UUID)
    : List[MidoOp[_ <: Message]] = {
        val rule = try storage.get(classOf[Rule], sgrId).await() catch {
            case ex: NotFoundException => return List()
        }
        val chain = try storage.get(classOf[Chain], rule.getChainId).await() catch {
            case ex: NotFoundException => return List()
        }
        val ruleIds = chain.getRuleIdsList.asScala.filter(r => r != sgrId)

        val updatedChain = Chain.newBuilder()
                            .setId(chain.getId)
                            .setName(chain.getName)
                            .addAllRuleIds(ruleIds.asJava)
                            .build()
        val ops = new ListBuffer[MidoOp[_ <: Message]]
        ops += Delete(classOf[Rule], rule.getId)
        ops += Update(updatedChain)
        ops.toList
    }
}
