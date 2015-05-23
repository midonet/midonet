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
import org.midonet.cluster.auth.AuthRole;
import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.ConflictHttpException;
import org.midonet.api.rest_api.NotFoundHttpException;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.client.neutron.NeutronMediaType;
import org.midonet.cluster.data.neutron.Network;
import org.midonet.cluster.data.neutron.NetworkApi;
import org.midonet.event.neutron.NetworkEvent;
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

import static org.midonet.cluster.rest_api.validation.MessageProperty.*;

public class NetworkResource extends AbstractResource {

    private final static Logger log = LoggerFactory.getLogger(
            NetworkResource.class);
    private final static NetworkEvent NETWORK_EVENT =
            new NetworkEvent();

    private final NetworkApi api;

    @Inject
    public NetworkResource(RestApiConfig config, UriInfo uriInfo,
                           SecurityContext context, NetworkApi api) {
        super(config, uriInfo, context, null, null);
        this.api = api;
    }

    @POST
    @Consumes(NeutronMediaType.NETWORK_JSON_V1)
    @Produces(NeutronMediaType.NETWORK_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public Response create(Network network)
            throws SerializationException, StateAccessException {
        log.info("NetworkResource.create entered {}", network);

        try {
            Network net = api.createNetwork(network);
            NETWORK_EVENT.create(net.id, net);
            log.info("NetworkResource.create exiting {}", net);
            return Response.created(
                    NeutronUriBuilder.getNetwork(
                            getBaseUri(), net.id)).entity(net).build();
        } catch (StatePathExistsException e) {
            log.error("Duplicate resource error", e);
            throw new ConflictHttpException(e, getMessage(RESOURCE_EXISTS));
        }
    }

    @POST
    @Consumes(NeutronMediaType.NETWORKS_JSON_V1)
    @Produces(NeutronMediaType.NETWORKS_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public Response createBulk(List<Network> networks)
            throws SerializationException, StateAccessException {
        log.info("NetworkResource.createBulk entered");

        try {
            List<Network> nets = api.createNetworkBulk(networks);
            for (Network net : nets) {
                NETWORK_EVENT.create(net.id, net);
            }
            return Response.created(NeutronUriBuilder.getNetworks(
                    getBaseUri())).entity(nets).build();
        } catch (StatePathExistsException e) {
            throw new ConflictHttpException(e, getMessage(RESOURCE_EXISTS));
        }
    }

    @DELETE
    @Path("{id}")
    @RolesAllowed(AuthRole.ADMIN)
    public void delete(@PathParam("id") UUID id)
            throws SerializationException, StateAccessException {
        log.info("NetworkResource.delete entered {}", id);
        api.deleteNetwork(id);
        NETWORK_EVENT.delete(id);
    }

    @GET
    @Path("{id}")
    @Produces(NeutronMediaType.NETWORK_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public Network get(@PathParam("id") UUID id)
            throws SerializationException, StateAccessException {
        log.info("NetworkResource.get entered {}", id);

        Network net = api.getNetwork(id);
        if (net == null) {
            throw new NotFoundHttpException(getMessage(RESOURCE_NOT_FOUND));
        }

        log.info("NetworkResource.get exiting {}", net);
        return net;
    }

    @GET
    @Produces(NeutronMediaType.NETWORKS_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public List<Network> list()
            throws SerializationException, StateAccessException {
        log.info("NetworkResource.list entered");
        return api.getNetworks();
    }

    @PUT
    @Path("{id}")
    @Consumes(NeutronMediaType.NETWORK_JSON_V1)
    @Produces(NeutronMediaType.NETWORK_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public Response update(@PathParam("id") UUID id, Network network)
            throws SerializationException, StateAccessException,
            BridgeZkManager.VxLanPortIdUpdateException {
        log.info("NetworkResource.update entered {}", network);

        try {
            Network net = api.updateNetwork(id, network);
            NETWORK_EVENT.update(id, net);
            log.info("NetworkResource.update exiting {}", net);
            return Response.ok(
                    NeutronUriBuilder.getNetwork(
                            getBaseUri(), net.id)).entity(net).build();
        } catch (NoStatePathException e) {
            log.error("Resource does not exist", e);
            throw new NotFoundHttpException(e, getMessage(RESOURCE_NOT_FOUND));
        }
    }
}
