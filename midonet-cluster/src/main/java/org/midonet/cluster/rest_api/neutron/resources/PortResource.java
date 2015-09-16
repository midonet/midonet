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

package org.midonet.cluster.rest_api.neutron.resources;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import com.google.inject.Inject;

import org.midonet.cluster.rest_api.NotFoundHttpException;
import org.midonet.cluster.rest_api.neutron.NeutronMediaType;
import org.midonet.cluster.rest_api.neutron.NeutronUriBuilder;
import org.midonet.cluster.rest_api.neutron.models.Port;
import org.midonet.cluster.services.rest_api.neutron.plugin.NetworkApi;
import org.midonet.cluster.services.rest_api.neutron.plugin.NeutronZoomPlugin;

import static org.midonet.cluster.rest_api.validation.MessageProperty.RESOURCE_NOT_FOUND;
import static org.midonet.cluster.rest_api.validation.MessageProperty.getMessage;

public class PortResource {

    private final NetworkApi api;
    private final URI baseUri;

    @Inject
    public PortResource(UriInfo uriInfo, NeutronZoomPlugin api) {
        this.api = api;
        this.baseUri = uriInfo.getBaseUri();
    }

    @POST
    @Consumes(NeutronMediaType.PORT_JSON_V1)
    @Produces(NeutronMediaType.PORT_JSON_V1)
    public Response create(Port port) {
        Port p = api.createPort(port);
        return Response.created(NeutronUriBuilder.getPort(baseUri, p.id))
                       .entity(p).build();
    }

    @POST
    @Consumes(NeutronMediaType.PORTS_JSON_V1)
    @Produces(NeutronMediaType.PORTS_JSON_V1)
    public Response createBulk(List<Port> ports) {
        List<Port> outPorts = api.createPortBulk(ports);
        return Response.created(NeutronUriBuilder.getPorts(baseUri))
                       .entity(outPorts).build();
    }

    @DELETE
    @Path("{id}")
    public void delete(@PathParam("id") UUID id) {
        api.deletePort(id);
    }

    @GET
    @Path("{id}")
    @Produces(NeutronMediaType.PORT_JSON_V1)
    public Port get(@PathParam("id") UUID id) {
        Port p = api.getPort(id);
        if (p == null) {
            throw new NotFoundHttpException(getMessage(RESOURCE_NOT_FOUND));
        }
        return p;
    }

    @GET
    @Produces(NeutronMediaType.PORTS_JSON_V1)
    public List<Port> list() {
        return api.getPorts();
    }

    @PUT
    @Path("{id}")
    @Consumes(NeutronMediaType.PORT_JSON_V1)
    @Produces(NeutronMediaType.PORT_JSON_V1)
    public Response update(@PathParam("id") UUID id, Port port) {
        Port p = api.updatePort(id, port);
        return Response.ok(NeutronUriBuilder.getPort(baseUri, p.id))
                       .entity(p).build();

    }
}
