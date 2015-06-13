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

import java.util.{List => JList, UUID}
import javax.ws.rs._
import javax.ws.rs.core.MediaType.APPLICATION_JSON

import scala.collection.JavaConverters._

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.rest_api.annotation.{AllowCreate, AllowDelete, AllowGet, AllowList}
import org.midonet.cluster.rest_api.models.{Port, PortGroup, PortGroupPort}
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource.ResourceContext

@RequestScoped
@AllowGet(Array(APPLICATION_PORTGROUP_JSON))
@AllowList(Array(APPLICATION_PORTGROUP_COLLECTION_JSON,
                 APPLICATION_JSON))
@AllowCreate(Array(APPLICATION_PORTGROUP_JSON,
                   APPLICATION_JSON))
@AllowDelete
class PortGroupResource @Inject()(resContext: ResourceContext)
    extends MidonetResource[PortGroup](resContext) {

    private val uriInfo = resContext.uriInfo

    protected override def listFilter: (PortGroup) => Boolean = {
        val portId = uriInfo.getQueryParameters.getFirst("port_id")
        val tenantId = uriInfo.getQueryParameters.getFirst("tenant_id")
        if ((portId ne null) && (tenantId ne null))
            (pg: PortGroup) => pg.portIds.contains(portId) &&
                               pg.tenantId == tenantId
        else if (portId ne null)
            (pg: PortGroup) => pg.portIds.contains(portId)
        else if (tenantId ne null)
            (pg: PortGroup) => pg.tenantId == tenantId
        else
            (_: PortGroup) => true
    }

    @Path("{id}/ports")
    def ports(@PathParam("id") id: UUID) = {
        new PortGroupPortResource(id, resContext)
    }

}

@RequestScoped
class PortPortGroupResource @Inject()(portId: UUID,
                                      resContext: ResourceContext)
    extends MidonetResource[PortGroupPort](resContext) {

    @GET
    @Produces(Array(APPLICATION_PORTGROUP_PORT_COLLECTION_JSON))
    override def list(@HeaderParam("Accept") accept: String)
    : JList[PortGroupPort] = {
        getResource(classOf[Port], portId).map(_.portGroupIds.asScala.map(id => {
            val portGroupPort = new PortGroupPort
            portGroupPort.portId = portId
            portGroupPort.portGroupId = id
            portGroupPort.setBaseUri(resContext.uriInfo.getBaseUri)
            portGroupPort
        }))
            .getOrThrow
            .asJava
    }

}