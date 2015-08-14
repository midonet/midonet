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

import java.util.{List => JList, UUID}

import javax.ws.rs._
import javax.ws.rs.core.MediaType.APPLICATION_JSON
import javax.ws.rs.core.Response

import scala.collection.JavaConverters._

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.rest_api.BadRequestHttpException
import org.midonet.cluster.rest_api.annotation.{ApiResource, AllowGet}
import org.midonet.cluster.rest_api.models.{Chain, Rule}
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource.{Create, ResourceContext, Update}

@ApiResource(version = 1)
@Path("rules")
@RequestScoped
@AllowGet(Array(APPLICATION_RULE_JSON,
                APPLICATION_RULE_JSON_V2,
                APPLICATION_JSON))
class RuleResource @Inject()(resContext: ResourceContext)
    extends MidonetResource[Rule](resContext) {

    @GET
    @Path("{id}")
    override def get(@PathParam("id") id: String,
                     @HeaderParam("Accept") accept: String): Rule = {
        val rule = getResource(classOf[Rule], id).getOrThrow
        val chain = getResource(classOf[Chain], rule.chainId).getOrThrow
        rule.position = chain.ruleIds.indexOf(rule.id) + 1
        rule
    }
}

@RequestScoped
class ChainRuleResource @Inject()(chainId: UUID, resContext: ResourceContext)
    extends MidonetResource[Rule](resContext) {

    @GET
    @Produces(Array(APPLICATION_RULE_COLLECTION_JSON,
                    APPLICATION_RULE_COLLECTION_JSON_V2,
                    APPLICATION_JSON))
    override def list(@HeaderParam("Accept") accept: String): JList[Rule] = {
        val rules = getResource(classOf[Chain], chainId)
            .flatMap(chain => listResources(classOf[Rule],
                                            chain.ruleIds.asScala))
            .getOrThrow

        for (i <- 0 until rules.size) rules(i).position = i + 1
        rules.asJava
    }

    @POST
    @Consumes(Array(APPLICATION_RULE_JSON,
                    APPLICATION_RULE_JSON_V2,
                    APPLICATION_JSON))
    override def create(rule: Rule,
                        @HeaderParam("Content-Type") contentType: String)
    : Response = {

        // required here so it fills some default fields which will be checked
        // on validation
        rule.create(chainId)

        throwIfViolationsOn(rule)

        getResource(classOf[Chain], chainId).map(chain => {
            rule.setBaseUri(resContext.uriInfo.getBaseUri)
            if (rule.position <= 0 || rule.position > chain.ruleIds.size() + 1) {
                throw new BadRequestHttpException("Position exceeds number of" +
                                                  "rules in chain")
            }
            chain.ruleIds.add(rule.position - 1, rule.id)
            multiResource(Seq(Create(rule), Update(chain)),
                          Response.created(rule.getUri).build())

        }).getOrThrow
    }
}