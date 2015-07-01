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
import javax.ws.rs.core.Response.Status
import javax.ws.rs.core.{Response, UriInfo}

import scala.collection.JavaConverters._

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.rest_api.BadRequestHttpException
import org.midonet.cluster.rest_api.annotation.AllowGet
import org.midonet.cluster.rest_api.models.{Chain, Rule}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource.{ResourceContext, Delete, Create, Update}

@RequestScoped
@AllowGet(Array(APPLICATION_RULE_JSON,
                APPLICATION_RULE_JSON_V2,
                APPLICATION_JSON))
class RuleResource @Inject()(resContext: ResourceContext)
    extends MidonetResource[Rule](resContext) {

    @DELETE
    @Path("{id}")
    override def delete(@PathParam("id") id: String): Response = {
        val ruleId = UUID.fromString(id)
        getResource(classOf[Rule], ruleId).flatMap(rule => {
            getResource(classOf[Chain], rule.chainId)
        }).map(chain => {
            if (chain.ruleIds.remove(ruleId)) {
                multiResource(Seq(Update(chain), Delete(classOf[Rule], ruleId)))
            } else {
                Response.status(Status.NOT_FOUND).build()
            }
        }).getOrThrow
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
        getResource(classOf[Chain], chainId)
            .flatMap(chain => listResources(classOf[Rule],
                                            chain.ruleIds.asScala))
            .getOrThrow
            .asJava
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