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
import scala.concurrent.Future

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.rest_api.annotation.{AllowCreate, AllowDelete, AllowGet, AllowList}
import org.midonet.cluster.rest_api.models._
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource._

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

    protected override def deleteFilter(chainId: String): Ops = {
        getResource(classOf[Chain], chainId) flatMap { chain =>
            Future.sequence(Seq(deleteJumpRules(chain),
                                clearFromPorts(chain),
                                clearFromBridges(chain),
                                clearFromRouters(chain))) map { _.flatten }
        }
    }

    /** Deletes the jump rules that reference a chain. */
    private def deleteJumpRules(chain: Chain): Ops = {
        val ops = for (ruleId <- chain.jumpRuleIds.asScala)
            yield Delete(classOf[Rule], ruleId)
        Future.successful(ops)
    }

    /** Clears the chain from the ports that reference a chain. */
    private def clearFromPorts(chain: Chain): Ops = {
        clearFromResource(classOf[Port], chain.portIds.asScala) { port =>
            if (port.inboundFilterId == chain.id)
                port.inboundFilterId = null
            if (port.outboundFilterId == chain.id)
                port.outboundFilterId = null
        }
    }

    /** Clears the chain from the bridges that reference a chain. */
    private def clearFromBridges(chain: Chain): Ops = {
        clearFromResource(classOf[Bridge], chain.networkIds.asScala) { bridge =>
            if (bridge.inboundFilterId == chain.id)
                bridge.inboundFilterId = null
            if (bridge.outboundFilterId == chain.id)
                bridge.outboundFilterId = null
        }
    }

    /** Clears the chain from the routers that reference a chain. */
    private def clearFromRouters(chain: Chain): Ops = {
        clearFromResource(classOf[Router], chain.routerIds.asScala) { router =>
            if (router.inboundFilterId == chain.id)
                router.inboundFilterId = null
            if (router.outboundFilterId == chain.id)
                router.outboundFilterId = null
        }
    }

    /** Clears the chain from the resources with the specified identifiers.
      * The method takes a clojure as a second argument list specifying how
      * the filters are cleared for the given resource. */
    private def clearFromResource[T >: Null <: UriResource]
                                 (clazz: Class[T], ids: Seq[UUID])
                                 (clearFilter: (T) => Unit): Ops = {
        val futures = for (id <- ids) yield {
            getResource(clazz, id) map { res =>
                clearFilter(res)
                Seq[Multi](Update(res))
            }
        }
        Future.sequence(futures) map { _.flatten }
    }

}
