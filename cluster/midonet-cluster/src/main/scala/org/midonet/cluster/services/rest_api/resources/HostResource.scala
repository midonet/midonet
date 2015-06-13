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
import javax.ws.rs.core.Response

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.rest_api.annotation.{AllowGet, AllowList}
import org.midonet.cluster.rest_api.models.Host
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource.ResourceContext

@RequestScoped
@AllowGet(Array(APPLICATION_HOST_JSON_V2,
                APPLICATION_HOST_JSON_V3,
                APPLICATION_JSON))
@AllowList(Array(APPLICATION_HOST_COLLECTION_JSON_V2,
                 APPLICATION_HOST_COLLECTION_JSON_V3,
                 APPLICATION_JSON))
class HostResource @Inject()(resContext: ResourceContext)
    extends MidonetResource[Host](resContext) {

    @DELETE
    @Path("{id}")
    override def delete(@PathParam("id") id: String): Response = {
        if (isAlive(id)) {
            return Response.status(Response.Status.FORBIDDEN).build()
        }
        val host = getResource(classOf[Host], id).getOrThrow
        if ((host.portIds ne null) && !host.portIds.isEmpty) {
            return Response.status(Response.Status.FORBIDDEN).build()
        }
        deleteResource(classOf[Host], id)
    }

    @PUT
    @Path("{id}")
    @Consumes(Array(APPLICATION_HOST_JSON_V2,
                    APPLICATION_HOST_JSON_V3,
                    APPLICATION_JSON))
    override def update(@PathParam("id") id: String, host: Host,
                        @HeaderParam("Content-Type") contentType: String)
    : Response = {
        if (host.floodingProxyWeight eq null) {
            return Response.status(Response.Status.BAD_REQUEST).build()
        }
        getResource(classOf[Host], id).map(current => {
            current.floodingProxyWeight = host.floodingProxyWeight
            updateResource(current)
        }).getOrThrow
    }

    @Path("{id}/interfaces")
    def interfaces(@PathParam("id") hostId: UUID): InterfaceResource = {
        new InterfaceResource(hostId, resContext)
    }

    @Path("{id}/ports")
    def ports(@PathParam("id") hostId: UUID): HostInterfacePortResource = {
        new HostInterfacePortResource(hostId, resContext)
    }

    protected override def getFilter = (host: Host) => setAlive(host)

    protected override def listFilter = (host: Host) => { setAlive(host); true }

    private def isAlive(id: String): Boolean = {
        getResourceOwners(classOf[Host], id).getOrThrow.nonEmpty
    }

    private def setAlive(host: Host): Host = {
        host.alive = isAlive(host.id.toString)
        host
    }
}
