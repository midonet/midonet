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

import org.midonet.cluster.rest_api.BadRequestHttpException
import org.midonet.cluster.rest_api.annotation._
import org.midonet.cluster.rest_api.models.Bridge
import org.midonet.cluster.rest_api.validation.MessageProperty.{VXLAN_PORT_ID_NOT_SETTABLE, getMessage}
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource.ResourceContext

@RequestScoped
@AllowGet(Array(APPLICATION_BRIDGE_JSON,
                APPLICATION_BRIDGE_JSON_V2,
                APPLICATION_BRIDGE_JSON_V3,
                APPLICATION_JSON))
@AllowList(Array(APPLICATION_BRIDGE_COLLECTION_JSON,
                 APPLICATION_BRIDGE_COLLECTION_JSON_V2,
                 APPLICATION_BRIDGE_COLLECTION_JSON_V3,
                 APPLICATION_JSON))
@AllowCreate(Array(APPLICATION_BRIDGE_JSON,
                   APPLICATION_BRIDGE_JSON_V2,
                   APPLICATION_BRIDGE_JSON_V3,
                   APPLICATION_JSON))
@AllowUpdate(Array(APPLICATION_BRIDGE_JSON,
                   APPLICATION_BRIDGE_JSON_V2,
                   APPLICATION_BRIDGE_JSON_V3,
                   APPLICATION_JSON))
@AllowDelete
class BridgeResource @Inject()(resContext: ResourceContext)
    extends MidonetResource[Bridge](resContext) {

    @Path("{id}/ports")
    def ports(@PathParam("id") id: UUID): BridgePortResource = {
        new BridgePortResource(id, resContext)
    }

    @Path("{id}/dhcp")
    def dhcps(@PathParam("id") id: UUID): DhcpSubnetResource = {
        new DhcpSubnetResource(id, resContext)
    }

    protected override def listFilter: (Bridge) => Boolean = {
        val tenantId = resContext.uriInfo
                                 .getQueryParameters.getFirst("tenant_id")
        if (tenantId eq null) (_: Bridge) => true
        else (r: Bridge) => r.tenantId == tenantId
    }

    protected override def createFilter = (to: Bridge) => {
        if (to.getVxLanPort != null || to.getVxLanPorts != null) {
            throw new BadRequestHttpException(
                getMessage(VXLAN_PORT_ID_NOT_SETTABLE))
        }
        super.createFilter(to)
    }

    protected override def updateFilter = (to: Bridge, from: Bridge) => {
        if ((to.getVxLanPorts != from.getVxLanPorts) ||
            (to.getVxLanPort != from.getVxLanPort)) {
            throw new BadRequestHttpException(
                getMessage(VXLAN_PORT_ID_NOT_SETTABLE))
        }
        super.updateFilter(to, from)
    }
}
