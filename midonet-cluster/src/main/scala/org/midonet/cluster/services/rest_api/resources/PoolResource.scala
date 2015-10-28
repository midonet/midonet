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
import javax.ws.rs._

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped
import org.midonet.cluster.models.Topology.Pool.PoolHealthMonitorMappingStatus._
import org.midonet.cluster.models.Topology.{Pool => TopPool}
import org.midonet.cluster.rest_api.ServiceUnavailableHttpException
import org.midonet.cluster.rest_api.annotation._
import org.midonet.cluster.rest_api.models.{RouterPort, HealthMonitor, LoadBalancer, Pool}
import org.midonet.cluster.rest_api.validation.MessageProperty._
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource._

import scala.collection.JavaConverters._
import scala.concurrent.Future

@ApiResource(version = 1)
@Path("pools")
@RequestScoped
@AllowGet(Array(APPLICATION_POOL_JSON,
                APPLICATION_JSON))
@AllowList(Array(APPLICATION_POOL_COLLECTION_JSON,
                 APPLICATION_JSON))
@AllowCreate(Array(APPLICATION_POOL_JSON,
                   APPLICATION_JSON))
@AllowUpdate(Array(APPLICATION_POOL_JSON,
                   APPLICATION_JSON))
class PoolResource @Inject()(resContext: ResourceContext)
    extends MidonetResource[Pool](resContext) {

    private val store = resContext.backend.store

    @PUT
    @Path("{id}")
    @Consumes(Array(APPLICATION_POOL_JSON,
                    APPLICATION_JSON))
    override def update(@PathParam("id") id: String, pool: Pool,
                        @HeaderParam("Content-Type") contentType: String)
    : Response = {
        lock {
            getResource(classOf[Pool], id).map(current => {
                current.update(pool)
                updateResource(current, OkResponse)
            }).getOrThrow
        }
    }

    @DELETE
    @Path("{id}")
    override def delete(@PathParam("id") id: String): Response = {
        lock { deleteResource(classOf[Pool], id) }
    }

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

    def checkMappingStatus(p: TopPool): Unit = {
        val s = p.getMappingStatus
        if (s == PENDING_UPDATE ||
            s == PENDING_DELETE ||
            s == PENDING_CREATE) {
            throw new ServiceUnavailableHttpException(
                getMessage(MAPPING_STATUS_IS_PENDING, s))
        }
    }

    protected override def updateFilter(to: Pool, from: Pool): Ops = {
        val p = store.get(classOf[TopPool], from.id).getOrThrow
        checkMappingStatus(p)
        to.update(from)
        NoOps
    }

    protected override def deleteFilter(id: String): Ops = {
        if (store.exists(classOf[TopPool], id).getOrThrow) {
            val p = store.get(classOf[TopPool], id).getOrThrow
            checkMappingStatus(p)
        }
        NoOps
    }

    protected override def handleDelete = {
        case r: Response if r.getStatus == Status.NOT_FOUND.getStatusCode =>
            OkNoContentResponse
    }

    protected override def listFilter(pools: Seq[Pool]): Future[Seq[Pool]] = {
        val hmId = resContext.uriInfo.getQueryParameters.getFirst("hm_id")
        if (hmId eq null) {
            Future.successful(pools)
        } else {
            val hmIdUUID = UUID.fromString(hmId)
            Future.successful(pools filter {_.healthMonitorId == hmIdUUID})
        }
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

@RequestScoped
@AllowList(Array(APPLICATION_POOL_COLLECTION_JSON,
                 APPLICATION_JSON))
class HealthMonitorPoolResource @Inject()(healthMonitorId: UUID,
                                          resContext: ResourceContext)
    extends MidonetResource[Pool](resContext) {

    protected override def listIds: Ids = {
        getResource(classOf[HealthMonitor], healthMonitorId) map {
            _.poolIds.asScala
        }
    }

}