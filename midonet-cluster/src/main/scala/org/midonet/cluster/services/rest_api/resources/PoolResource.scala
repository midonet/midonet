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

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.rest_api.annotation._
import org.midonet.cluster.rest_api.models.{HealthMonitor, LoadBalancer, Pool}
import org.midonet.cluster.rest_api.validation.MessageProperty._
import org.midonet.cluster.rest_api.{NotFoundHttpException, ServiceUnavailableHttpException}
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource._
import org.midonet.midolman.state.PoolHealthMonitorMappingStatus._

@ApiResource(version = 1, name = "pools", template = "poolTemplate")
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
@AllowDelete
class PoolResource @Inject()(resContext: ResourceContext)
    extends MidonetResource[Pool](resContext) {

    @Path("{id}/vips")
    def vips(@PathParam("id") id: UUID): PoolVipResource = {
        getResource(classOf[Pool], id)
        new PoolVipResource(id, resContext)
    }

    @Path("{id}/pool_members")
    def members(@PathParam("id") id: UUID): PoolPoolMemberResource = {
        getResource(classOf[Pool], id)
        new PoolPoolMemberResource(id, resContext)
    }

    def checkMappingStatus(pool: Pool): Unit = {
        val s = pool.mappingStatus
        if (s == PENDING_UPDATE ||
            s == PENDING_DELETE ||
            s == PENDING_CREATE) {
            throw new ServiceUnavailableHttpException(
                getMessage(MAPPING_STATUS_IS_PENDING, s))
        }
    }

    protected override def updateFilter(to: Pool, from: Pool,
                                        tx: ResourceTransaction): Unit = {
        val pool = tx.get(classOf[Pool], from.id)
        checkMappingStatus(pool)
        to.update(from)
        tx.update(to)
    }

    protected override def deleteFilter(id: String,
                                        tx: ResourceTransaction): Unit = {
        try { checkMappingStatus(tx.get(classOf[Pool], id)) }
        catch { case e: NotFoundHttpException => }
        tx.delete(classOf[Pool], id)
    }

    protected override def handleDelete = {
        case r: Response if r.getStatus == Status.NOT_FOUND.getStatusCode =>
            OkNoContentResponse
    }

    protected override def listFilter(pools: Seq[Pool]): Seq[Pool] = {
        val hmId = resContext.uriInfo.getQueryParameters.getFirst("hm_id")
        if (hmId eq null) {
            pools
        } else {
            val hmIdUUID = UUID.fromString(hmId)
            pools filter {_.healthMonitorId == hmIdUUID}
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

    protected override def listIds: Seq[Any] = {
        getResource(classOf[LoadBalancer], loadBalancerId).poolIds.asScala
    }

    protected override def createFilter(pool: Pool, tx: ResourceTransaction)
    : Unit = {
        pool.create(loadBalancerId)
        tx.create(pool)
    }
}

@RequestScoped
@AllowList(Array(APPLICATION_POOL_COLLECTION_JSON,
                 APPLICATION_JSON))
class HealthMonitorPoolResource @Inject()(healthMonitorId: UUID,
                                          resContext: ResourceContext)
    extends MidonetResource[Pool](resContext) {

    protected override def listIds: Seq[Any] = {
        getResource(classOf[HealthMonitor], healthMonitorId).poolIds.asScala
    }

}