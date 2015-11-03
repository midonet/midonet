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
import javax.ws.rs._
import javax.ws.rs.core.MediaType.APPLICATION_JSON

import scala.collection.JavaConverters._

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.rest_api.annotation.{AllowCreate, AllowDelete, AllowGet, AllowList, ApiResource}
import org.midonet.cluster.rest_api.models.{Chain, JumpRule, Rule}
import org.midonet.cluster.rest_api.{BadRequestHttpException, NotFoundHttpException}
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource._

@ApiResource(version = 1, template = "ruleTemplate")
@Path("rules")
@RequestScoped
@AllowGet(Array(APPLICATION_RULE_JSON_V2,
                APPLICATION_JSON))
@AllowDelete
class RuleResource @Inject()(resContext: ResourceContext)
    extends MidonetResource[Rule](resContext) {

    protected override def getFilter(rule: Rule): Rule = {
        val chain = getResource(classOf[Chain], rule.chainId)
        rule.position = chain.ruleIds.indexOf(rule.id) + 1
        rule
    }

    protected override def deleteFilter(ruleId: String): Seq[Multi] = {
        val rule = getResource(classOf[Rule], ruleId)
        val chain = getResource(classOf[Chain], rule.chainId)
        if (chain.ruleIds.remove(rule.id)) {
            Seq(Update(chain))
        } else {
            throw new NotFoundHttpException("Rule chain not found")
        }
    }

}

@RequestScoped
@AllowList(Array(APPLICATION_RULE_COLLECTION_JSON_V2,
                 APPLICATION_JSON))
@AllowCreate(Array(APPLICATION_RULE_JSON_V2,
                   APPLICATION_JSON))
class ChainRuleResource @Inject()(chainId: UUID, resContext: ResourceContext)
    extends MidonetResource[Rule](resContext) {

    protected override def listIds: Seq[Any] = {
        getResource(classOf[Chain], chainId).ruleIds.asScala
    }

    protected override def listFilter(rules: Seq[Rule]): Seq[Rule] = {
        for (index <- rules.indices) rules(index).position = index + 1
        rules
    }

    protected override def createFilter(rule: Rule): Seq[Multi] = {
        rule.create(chainId)

        val chain = getResource(classOf[Chain], chainId)
        rule.setBaseUri(resContext.uriInfo.getBaseUri)
        if (rule.position <= 0 || rule.position > chain.ruleIds.size() + 1) {
            throw new BadRequestHttpException("Position exceeds number " +
                                              "of rules in chain")
        }
        chain.ruleIds.add(rule.position - 1, rule.id)

        updateJumpChain(rule)(Seq(Update(chain)))
    }

    private def updateJumpChain(rule: Rule)(ops: Seq[Multi]): Seq[Multi] = {
        rule match {
            case jumpRule: JumpRule if jumpRule.jumpChainId ne null=>
                val chain = getResource(classOf[Chain], jumpRule.jumpChainId)
                chain.jumpRuleIds.add(rule.id)
                ops :+ Update(chain)
            case jumpRule: JumpRule =>
                throw new BadRequestHttpException("Jump rule missing chain identifier")
            case _ => ops
        }
    }

}