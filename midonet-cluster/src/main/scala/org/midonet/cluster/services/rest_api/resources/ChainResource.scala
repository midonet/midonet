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

import org.midonet.cluster.rest_api.annotation.{AllowCreate, AllowDelete, AllowGet, AllowList, _}
import org.midonet.cluster.rest_api.models.{Chain, _}
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource._

@ApiResource(version = 1)
@Path("chains")
@RequestScoped
@AllowGet(Array(APPLICATION_CHAIN_JSON,
                APPLICATION_JSON))
@AllowList(Array(APPLICATION_CHAIN_COLLECTION_JSON,
                 APPLICATION_JSON))
@AllowCreate(Array(APPLICATION_CHAIN_JSON,
                   APPLICATION_JSON))
@AllowDelete
class ChainResource @Inject()(resContext: ResourceContext)
    extends MidonetResource[Chain](resContext) {

    @Path("{id}/rules")
    def rules(@PathParam("id") id: UUID): ChainRuleResource = {
        new ChainRuleResource(id, resContext)
    }

    protected override def listFilter(chains: Seq[Chain]): Seq[Chain] = {
        val tenantId = resContext.uriInfo.getQueryParameters
                                         .getFirst("tenant_id")
        if (tenantId eq null) chains
        else chains filter { _.tenantId == tenantId }
    }

    protected override def deleteFilter(chainId: String): Seq[Multi] = {
        val chain = getResource(classOf[Chain], chainId)
        // Deletes the jump rules that reference a chain.
        for (ruleId <- chain.jumpRuleIds.asScala)
            yield Delete(classOf[Rule], ruleId)
    }

}
