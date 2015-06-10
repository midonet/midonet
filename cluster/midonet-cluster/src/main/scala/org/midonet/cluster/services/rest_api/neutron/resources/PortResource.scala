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

package org.midonet.cluster.services.rest_api.neutron.resources

import java.util
import java.util.UUID
import javax.ws.rs.core.{Response, UriInfo}
import javax.ws.rs.{Consumes, DELETE, GET, POST, PUT, Path, PathParam, Produces}

import com.google.inject.Inject

import org.midonet.cluster.rest_api.NotFoundHttpException
import org.midonet.cluster.rest_api.neutron.NeutronResourceUris.{PORTS, getUri}
import org.midonet.cluster.rest_api.neutron.models.Port
import org.midonet.cluster.rest_api.validation.MessageProperty.{RESOURCE_NOT_FOUND, getMessage}
import org.midonet.cluster.services.rest_api.neutron.NeutronMediaType
import org.midonet.cluster.services.rest_api.neutron.plugin.NeutronZoomPlugin

class PortResource @Inject() (uriInfo: UriInfo, api: NeutronZoomPlugin) {

    @POST
    @Consumes(Array(NeutronMediaType.PORT_JSON_V1))
    @Produces(Array(NeutronMediaType.PORT_JSON_V1))
    def create(port: Port): Response = {
        val p: Port = api.createPort(port)
        Response.created(getUri(uriInfo.getBaseUri, PORTS, p.id))
                .entity(p).build
    }

    @POST
    @Consumes(Array(NeutronMediaType.PORTS_JSON_V1))
    @Produces(Array(NeutronMediaType.PORTS_JSON_V1))
    def createBulk(ports: util.List[Port]): Response = {
        val outPorts: util.List[Port] = api.createPortBulk(ports)
        Response.created(getUri(uriInfo.getBaseUri, PORTS))
                .entity(outPorts).build
    }

    @DELETE
    @Path("{id}")
    def delete(@PathParam("id") id: UUID) {
        api.deletePort(id)
    }

    @GET
    @Path("{id}")
    @Produces(Array(NeutronMediaType.PORT_JSON_V1))
    def get(@PathParam("id") id: UUID): Port = {
        val p: Port = api.getPort(id)
        if (p == null) {
            throw new NotFoundHttpException(getMessage(RESOURCE_NOT_FOUND))
        }
        p
    }

    @GET
    @Produces(Array(NeutronMediaType.PORTS_JSON_V1))
    def list: util.List[Port] = {
        api.getPorts
    }

    @PUT
    @Path("{id}")
    @Consumes(Array(NeutronMediaType.PORT_JSON_V1))
    @Produces(Array(NeutronMediaType.PORT_JSON_V1))
    def update(@PathParam("id") id: UUID, port: Port): Response = {
        val p: Port = api.updatePort(id, port)
        Response.ok(getUri(uriInfo.getBaseUri, PORTS, p.id)).entity(p)
                .build
    }
}