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
package org.midonet.api.neutron;

import java.util.List;
import java.util.UUID;

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import com.google.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.cluster.auth.AuthRole;
import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.cluster.rest_api.NotFoundHttpException;
import org.midonet.cluster.rest_api.neutron.NeutronMediaType;
import org.midonet.cluster.rest_api.neutron.NeutronUriBuilder;
import org.midonet.cluster.rest_api.neutron.models.Port;
import org.midonet.cluster.services.rest_api.neutron.plugin.NetworkApi;
import org.midonet.event.neutron.PortEvent;

import static org.midonet.cluster.rest_api.validation.MessageProperty.RESOURCE_NOT_FOUND;
import static org.midonet.cluster.rest_api.validation.MessageProperty.getMessage;

public class PortResource extends AbstractResource {

    private final static Logger log = LoggerFactory.getLogger(
            PortResource.class);
    private final static PortEvent PORT_EVENT =
            new PortEvent();

    private final NetworkApi api;

    @Inject
    public PortResource(RestApiConfig config, UriInfo uriInfo,
                        SecurityContext context, NetworkApi api) {
        super(config, uriInfo, context, null, null);
        this.api = api;
    }

    @POST
    @Consumes(NeutronMediaType.PORT_JSON_V1)
    @Produces(NeutronMediaType.PORT_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public Response create(Port port) {
        log.info("PortResource.create entered {}", port);
        Port p = api.createPort(port);
        PORT_EVENT.create(p.id, p);
        log.info("PortResource.create exiting {}", p);
        return Response.created(
            NeutronUriBuilder.getPort(
                getBaseUri(), p.id)).entity(p).build();
    }

    @POST
    @Consumes(NeutronMediaType.PORTS_JSON_V1)
    @Produces(NeutronMediaType.PORTS_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public Response createBulk(List<Port> ports) {
        log.info("PortResource.createBulk entered");

        List<Port> outPorts = api.createPortBulk(ports);
        for (Port p : outPorts) {
            PORT_EVENT.create(p.id, p);
        }
        return Response.created(NeutronUriBuilder.getPorts(
            getBaseUri())).entity(outPorts).build();
    }

    @DELETE
    @Path("{id}")
    @RolesAllowed(AuthRole.ADMIN)
    public void delete(@PathParam("id") UUID id) {
        log.info("PortResource.delete entered {}", id);
        api.deletePort(id);
        PORT_EVENT.delete(id);
    }

    @GET
    @Path("{id}")
    @Produces(NeutronMediaType.PORT_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public Port get(@PathParam("id") UUID id) {
        log.info("PortResource.get entered {}", id);

        Port p = api.getPort(id);
        if (p == null) {
            throw new NotFoundHttpException(getMessage(RESOURCE_NOT_FOUND));
        }

        log.info("PortResource.get exiting {}", p);
        return p;
    }

    @GET
    @Produces(NeutronMediaType.PORTS_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public List<Port> list() {
        log.info("PortResource.list entered");
        return api.getPorts();
    }

    @PUT
    @Path("{id}")
    @Consumes(NeutronMediaType.PORT_JSON_V1)
    @Produces(NeutronMediaType.PORT_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public Response update(@PathParam("id") UUID id, Port port) {
        log.info("PortResource.update entered {}", port);

        Port p = api.updatePort(id, port);
        PORT_EVENT.update(id, p);
        log.info("PortResource.update exiting {}", p);
        return Response.ok(
            NeutronUriBuilder.getPort(
                getBaseUri(), p.id)).entity(p).build();

    }
}
