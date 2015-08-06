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
import javax.ws.rs.core.Response
import javax.ws.rs.core.Response.Status
import javax.ws.rs.{Path, PathParam}

import scala.collection.JavaConverters._
import scala.util.control.NonFatal

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.rest_api.annotation._
import org.midonet.cluster.rest_api.models.{LoadBalancer, Pool, UriResource}
import org.midonet.cluster.rest_api.{InternalServerErrorHttpException, NotFoundHttpException}
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource._

@RequestScoped
@AllowGet(Array(APPLICATION_POOL_JSON,
                APPLICATION_JSON))
@AllowList(Array(APPLICATION_POOL_COLLECTION_JSON,
                 APPLICATION_JSON))
@AllowCreate(Array(APPLICATION_POOL_JSON,
                   APPLICATION_JSON))
@AllowUpdate(Array(APPLICATION_POOL_JSON,
                   APPLICATION_JSON))
@AllowDelete
class PoolResource @Inject()(resContext: ResourceContext)
    extends MidonetResource[Pool](resContext) {

    @Path("{id}/vips")
    def vips(@PathParam("id") id: UUID): PoolVipResource = {
        getResource(classOf[Pool], id).getOrThrow
        new PoolVipResource(id, resContext)
    }

    @Path("{id}/pool_members")
    def members(@PathParam("id") id: UUID): PoolPoolMemberResource = {
        getResource(classOf[Pool], id).getOrThrow
        new PoolPoolMemberResource(id, resContext)
    }

    protected override def updateFilter(to: Pool, from: Pool): Ops = {
        to.update(from)
        NoOps
    }

    protected override def handleDelete = {
        case r: Response if r.getStatus == Status.NOT_FOUND.getStatusCode =>
            OkNoContentResponse
    }

}

@RequestScoped
@AllowList(Array(APPLICATION_POOL_COLLECTION_JSON,
                 APPLICATION_JSON))
@AllowCreate(Array(APPLICATION_POOL_JSON,
                   APPLICATION_JSON))
class LoadBalancerPoolResource @Inject()(loadBalancerId: UUID,
                                         resContext: ResourceContext)
    extends MidonetResource[Pool](resContext) {

    @Path("{id}/pool_members")
    def poolMembers(@PathParam("id") id: UUID): PoolPoolMemberResource = {
        new PoolPoolMemberResource(id, resContext)
    }

    protected override def listIds: Ids = {
        getResource(classOf[LoadBalancer], loadBalancerId) map {
            _.poolIds.asScala
        }
    }

    protected override def createFilter(pool: Pool): Ops = {
        pool.create(loadBalancerId)
        NoOps
    }

}