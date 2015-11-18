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

import org.midonet.cluster.data.storage.{SingleValueKey, StateKey}
import org.midonet.cluster.models.Services.ServiceContainer
import org.midonet.cluster.models.Topology.{Pool => TopPool}
import org.midonet.cluster.rest_api.ServiceUnavailableHttpException
import org.midonet.cluster.rest_api.annotation._
import org.midonet.cluster.rest_api.models.{HealthMonitor, LoadBalancer, Pool}
import org.midonet.cluster.rest_api.validation.MessageProperty._
import org.midonet.cluster.services.MidonetBackend.{AliveKey, ClusterNamespaceId, PoolMappingStatus}
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource._
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.l4lb
import org.midonet.midolman.state.PoolHealthMonitorMappingStatus._
import org.midonet.util.reactivex._

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

    private val store = resContext.backend.store
    private val stateStore = resContext.backend.stateStore

    private val timeout = MidonetResource.Timeout

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

    /**
      * Returns the namespace in which the pool mapping status state key
      * resides. This is the agent ID that is managing the health monitor
      * service.
      */
    private def namespace(poolId: UUID, healthMonitorId: UUID): StateKey = {
        val containerId =
            l4lb.HealthMonitor.containerId(poolId, healthMonitorId)
        stateStore.getKey(ClusterNamespaceId.toString,
                          classOf[ServiceContainer],
                          containerId, AliveKey).await(timeout)
    }

    def checkMappingStatus(poolId: UUID): Unit = {
        val pool = store.get(classOf[TopPool], poolId).getOrThrow
        val healthMonitorId = pool.getHealthMonitorId.asJava

        var stateKey: StateKey = null

        namespace(poolId, healthMonitorId) match {
            case SingleValueKey(key, Some(name), owner) =>
                stateKey = stateStore.getKey(name, classOf[TopPool], poolId,
                                             PoolMappingStatus).await(timeout)
            case SingleValueKey(key, None, owner) =>
                stateKey = stateStore.getKey(classOf[TopPool], poolId,
                                             PoolMappingStatus).await(timeout)
            case _ => // This cannot happen.
        }

        stateKey match {
            case SingleValueKey(key, Some(status), owner) =>
                if (status == PENDING_UPDATE.name ||
                    status == PENDING_DELETE.name ||
                    status == PENDING_CREATE.name) {
                    throw new ServiceUnavailableHttpException(
                        getMessage(MAPPING_STATUS_IS_PENDING, status))
                }
            case SingleValueKey(key, None, owner) =>
                throw new ServiceUnavailableHttpException(
                    getMessage(MAPPING_STATUS_IS_PENDING, PENDING_CREATE))
            case _ => // This cannot happen.
        }
    }

    protected override def updateFilter(to: Pool, from: Pool): Seq[Multi] = {
        checkMappingStatus(to.id)
        to.update(from)
        Seq.empty
    }

    protected override def deleteFilter(id: String): Seq[Multi] = {
        if (store.exists(classOf[TopPool], id).getOrThrow) {
            checkMappingStatus(UUID.fromString(id))
        }
        Seq.empty
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

    protected override def createFilter(pool: Pool): Seq[Multi] = {
        pool.create(loadBalancerId)
        Seq.empty
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