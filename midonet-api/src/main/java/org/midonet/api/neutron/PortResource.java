/*
 * Copyright 2014 Midokura SARL
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

import com.google.inject.Inject;
import org.midonet.api.auth.AuthRole;
import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.ConflictHttpException;
import org.midonet.api.rest_api.NotFoundHttpException;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.client.neutron.NeutronMediaType;
import org.midonet.cluster.data.Rule;
import org.midonet.cluster.data.neutron.NetworkApi;
import org.midonet.cluster.data.neutron.Port;
import org.midonet.event.neutron.PortEvent;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.NoStatePathException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.StatePathExistsException;
import org.midonet.midolman.state.zkManagers.BridgeZkManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.*;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;
import java.util.List;
import java.util.UUID;

import static org.midonet.api.validation.MessageProperty.*;

public class PortResource extends AbstractResource {

    private final static Logger log = LoggerFactory.getLogger(
            PortResource.class);
    private final static PortEvent PORT_EVENT =
            new PortEvent();

    private final NetworkApi api;

    @Inject
    public PortResource(RestApiConfig config, UriInfo uriInfo,
                        SecurityContext context, NetworkApi api) {
        super(config, uriInfo, context, null);
        this.api = api;
    }

    @POST
    @Consumes(NeutronMediaType.PORT_JSON_V1)
    @Produces(NeutronMediaType.PORT_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public Response create(Port port)
            throws SerializationException, StateAccessException {
        log.info("PortResource.create entered {}", port);

        try {

            Port p = api.createPort(port);
            PORT_EVENT.create(p.id, p);
            log.info("PortResource.create exiting {}", p);
            return Response.created(
                    NeutronUriBuilder.getPort(
                            getBaseUri(), p.id)).entity(p).build();

        } catch (StatePathExistsException e) {
            log.error("Duplicate resource error", e);
            throw new ConflictHttpException(e, getMessage(RESOURCE_EXISTS));
        }
    }

    @POST
    @Consumes(NeutronMediaType.PORTS_JSON_V1)
    @Produces(NeutronMediaType.PORTS_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public Response createBulk(List<Port> ports)
            throws SerializationException, StateAccessException,
            Rule.RuleIndexOutOfBoundsException {
        log.info("PortResource.createBulk entered");

        try {
            List<Port> outPorts = api.createPortBulk(ports);
            for (Port p : outPorts) {
                PORT_EVENT.create(p.id, p);
            }
            return Response.created(NeutronUriBuilder.getPorts(
                    getBaseUri())).entity(outPorts).build();
        } catch (StatePathExistsException e) {
            throw new ConflictHttpException(e, getMessage(RESOURCE_EXISTS));
        }
    }

    @DELETE
    @Path("{id}")
    @RolesAllowed(AuthRole.ADMIN)
    public void delete(@PathParam("id") UUID id)
            throws SerializationException, StateAccessException,
            Rule.RuleIndexOutOfBoundsException {
        log.info("PortResource.delete entered {}", id);
        api.deletePort(id);
        PORT_EVENT.delete(id);
    }

    @GET
    @Path("{id}")
    @Produces(NeutronMediaType.PORT_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public Port get(@PathParam("id") UUID id)
            throws SerializationException, StateAccessException {
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
    public List<Port> list()
            throws SerializationException, StateAccessException {
        log.info("PortResource.list entered");
        return api.getPorts();
    }

    @PUT
    @Path("{id}")
    @Consumes(NeutronMediaType.PORT_JSON_V1)
    @Produces(NeutronMediaType.PORT_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public Response update(@PathParam("id") UUID id, Port port)
            throws SerializationException, StateAccessException,
            BridgeZkManager.VxLanPortIdUpdateException,
            Rule.RuleIndexOutOfBoundsException {
        log.info("PortResource.update entered {}", port);

        try {

            Port p = api.updatePort(id, port);
            PORT_EVENT.update(id, p);
            log.info("PortResource.update exiting {}", p);
            return Response.ok(
                    NeutronUriBuilder.getPort(
                            getBaseUri(), p.id)).entity(p).build();

        } catch (NoStatePathException e) {
            log.error("Resource does not exist", e);
            throw new NotFoundHttpException(e, getMessage(RESOURCE_NOT_FOUND));
        }
    }
}
