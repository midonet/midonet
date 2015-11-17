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

import javax.ws.rs.core.MediaType.APPLICATION_JSON
import javax.ws.rs.{Path, PathParam}

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.rest_api.BadRequestHttpException
import org.midonet.cluster.rest_api.annotation._
import org.midonet.cluster.rest_api.models.LoadBalancer
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource.{Multi, ResourceContext}

@ApiResource(version = 1, name = "loadBalancers", template = "loadBalancerTemplate")
@Path("load_balancers")
@RequestScoped
@AllowGet(Array(APPLICATION_LOAD_BALANCER_JSON,
                APPLICATION_JSON))
@AllowList(Array(APPLICATION_LOAD_BALANCER_COLLECTION_JSON,
                 APPLICATION_JSON))
@AllowCreate(Array(APPLICATION_LOAD_BALANCER_JSON,
                   APPLICATION_JSON))
@AllowUpdate(Array(APPLICATION_LOAD_BALANCER_JSON,
                   APPLICATION_JSON))
@AllowDelete
class LoadBalancerResource @Inject()(resContext: ResourceContext)
    extends MidonetResource[LoadBalancer](resContext) {

    @Path("{id}/pools")
    def pools(@PathParam("id") id: UUID): LoadBalancerPoolResource = {
        new LoadBalancerPoolResource(id, resContext)
    }

    @Path("{id}/vips")
    def vips(@PathParam("id") id: UUID): LoadBalancerVipResource = {
        new LoadBalancerVipResource(id, resContext)
    }

    protected override def updateFilter(to: LoadBalancer, from: LoadBalancer)
    : Seq[Multi] = {
        if (to.routerId != from.routerId) {
            throw new BadRequestHttpException("Router cannot be modified")
        }
        to.update(from)
        Seq.empty
    }

}
