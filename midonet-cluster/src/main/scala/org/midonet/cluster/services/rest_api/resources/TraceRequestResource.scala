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

import org.midonet.cluster.rest_api.NotFoundHttpException
import org.midonet.cluster.rest_api.annotation._
import org.midonet.cluster.rest_api.models.TraceRequest.DeviceType
import org.midonet.cluster.rest_api.models._
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource._

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

        val tenantId = uriInfo.getQueryParameters.getFirst("tenant_id")
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
            val port = getResource(classOf[Port], id)
            port match {
                case bridgePort: BridgePort =>
                    checkNetworkTenant(port.getDeviceId, tenantId)
                case routerPort: RouterPort =>
                    checkRouterTenant(port.getDeviceId, tenantId)
                case _ => false
            }
        } catch {
            case e: NotFoundHttpException => false
        }
    }

    private def checkNetworkTenant(id: UUID, tenantId: String): Boolean = {
        try {
            getResource(classOf[Bridge], id).tenantId == tenantId
        } catch {
            case e: NotFoundHttpException => false
        }
    }

    private def checkRouterTenant(id: UUID, tenantId: String): Boolean = {
        try {
            getResource(classOf[Router], id).tenantId == tenantId
        } catch {
            case e: NotFoundHttpException => false
        }
    }
}
