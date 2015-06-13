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
import javax.ws.rs.core.UriInfo

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.rest_api.annotation._
import org.midonet.cluster.rest_api.models.Router
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource.ResourceContext

@RequestScoped
@AllowGet(Array(APPLICATION_ROUTER_JSON,
                APPLICATION_ROUTER_JSON_V2,
                APPLICATION_JSON))
@AllowList(Array(APPLICATION_ROUTER_COLLECTION_JSON,
                 APPLICATION_ROUTER_COLLECTION_JSON_V2,
                 APPLICATION_JSON))
@AllowCreate(Array(APPLICATION_ROUTER_JSON,
                   APPLICATION_ROUTER_JSON_V2,
                   APPLICATION_JSON))
@AllowUpdate(Array(APPLICATION_ROUTER_JSON,
                   APPLICATION_ROUTER_JSON_V2,
                   APPLICATION_JSON))
@AllowDelete
class RouterResource @Inject()(resContext: ResourceContext)
    extends MidonetResource[Router](resContext) {

    @Path("{id}/ports")
    def ports(@PathParam("id") id: UUID): RouterPortResource = {
        new RouterPortResource(id, resContext)
    }

    @Path("{id}/routes")
    def routes(@PathParam("id") id: UUID): RouterRouteResource = {
        new RouterRouteResource(id, resContext)
    }

    protected override def listFilter: (Router) => Boolean = {
        val tenantId = resContext.uriInfo.getQueryParameters
                                         .getFirst("tenant_id")
        if (tenantId eq null) (_: Router) => true
        else (r: Router) => r.tenantId == tenantId
    }

    protected override def updateFilter = (to: Router, from: Router) => {
        to.update(from)
    }

}
