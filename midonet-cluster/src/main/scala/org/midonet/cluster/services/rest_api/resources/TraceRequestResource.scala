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

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.data.TraceRequest.DeviceType
import org.midonet.cluster.data.storage.NotFoundException
import org.midonet.cluster.models.Topology
import org.midonet.cluster.rest_api.annotation.{ApiResource, AllowCreate, AllowDelete}
import org.midonet.cluster.rest_api.annotation.{AllowGet, AllowList, AllowUpdate}
import org.midonet.cluster.rest_api.models._
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource._
import org.midonet.cluster.util.UUIDUtil._

@ApiResource(version = 1, name = "traceRequests", template = "traceRequestTemplate")
@Path("traces")
@RequestScoped
@AllowGet(Array(APPLICATION_TRACE_REQUEST_JSON,
                APPLICATION_JSON))
@AllowList(Array(APPLICATION_TRACE_REQUEST_COLLECTION_JSON,
                 APPLICATION_JSON))
@AllowCreate(Array(APPLICATION_TRACE_REQUEST_JSON,
                   APPLICATION_JSON))
@AllowUpdate(Array(APPLICATION_TRACE_REQUEST_JSON,
                   APPLICATION_JSON))
@AllowDelete
class TraceRequestResource @Inject()(resContext: ResourceContext)
        extends MidonetResource[TraceRequest](resContext) {

    protected override def listFilter(
        traceRequests: Seq[TraceRequest]): Seq[TraceRequest] = {

        val tenantId = resContext.uriInfo
            .getQueryParameters.getFirst("tenant_id")
        if (tenantId eq null) traceRequests
        else {
            traceRequests filter {
                case tr if tr.deviceType == DeviceType.PORT =>
                    checkPortTenant(tr.deviceId, tenantId)
                case tr if tr.deviceType == DeviceType.BRIDGE =>
                    checkNetworkTenant(tr.deviceId, tenantId)
                case tr if tr.deviceType == DeviceType.ROUTER =>
                    checkRouterTenant(tr.deviceId, tenantId)
                case _ => false
            }
        }
    }

    private def checkPortTenant(id: UUID, tenantId: String): Boolean = {
        try {
            val port = resContext.backend.store.get(
                classOf[Topology.Port], id).getOrThrow
            if (port.hasNetworkId) {
                checkNetworkTenant(port.getNetworkId, tenantId)
            } else if (port.hasRouterId) {
                checkRouterTenant(port.getRouterId, tenantId)
            } else false
        } catch {
            case e: NotFoundException => false
        }
    }

    private def checkNetworkTenant(id: UUID, tenantId: String): Boolean = {
        try {
            resContext.backend.store.get(
                classOf[Topology.Network], id).getOrThrow.getTenantId == tenantId
        } catch {
            case e: NotFoundException => false
        }
    }

    private def checkRouterTenant(id: UUID, tenantId: String): Boolean = {
        try {
            resContext.backend.store.get(
                classOf[Topology.Router], id).getOrThrow.getTenantId == tenantId
        } catch {
            case e: NotFoundException => false
        }
    }
}
