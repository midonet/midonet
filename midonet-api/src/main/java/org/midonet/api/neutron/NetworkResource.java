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
import org.midonet.cluster.rest_api.neutron.models.Network;
import org.midonet.cluster.services.rest_api.neutron.plugin.NetworkApi;
import org.midonet.event.neutron.NetworkEvent;

import static org.midonet.cluster.rest_api.validation.MessageProperty.RESOURCE_NOT_FOUND;
import static org.midonet.cluster.rest_api.validation.MessageProperty.getMessage;

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
    public Response create(Network network) {
        log.info("NetworkResource.create entered {}", network);
        Network net = api.createNetwork(network);
        NETWORK_EVENT.create(net.id, net);
        log.info("NetworkResource.create exiting {}", net);
        return Response.created(
            NeutronUriBuilder.getNetwork(
                getBaseUri(), net.id)).entity(net).build();
    }

    @POST
    @Consumes(NeutronMediaType.NETWORKS_JSON_V1)
    @Produces(NeutronMediaType.NETWORKS_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public Response createBulk(List<Network> networks) {
        log.info("NetworkResource.createBulk entered");

        List<Network> nets = api.createNetworkBulk(networks);
        for (Network net : nets) {
            NETWORK_EVENT.create(net.id, net);
        }
        return Response.created(NeutronUriBuilder.getNetworks(
            getBaseUri())).entity(nets).build();
    }

    @DELETE
    @Path("{id}")
    @RolesAllowed(AuthRole.ADMIN)
    public void delete(@PathParam("id") UUID id) {
        log.info("NetworkResource.delete entered {}", id);
        api.deleteNetwork(id);
        NETWORK_EVENT.delete(id);
    }

    @GET
    @Path("{id}")
    @Produces(NeutronMediaType.NETWORK_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public Network get(@PathParam("id") UUID id) {
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
    public List<Network> list() {
        log.info("NetworkResource.list entered");
        return api.getNetworks();
    }

    @PUT
    @Path("{id}")
    @Consumes(NeutronMediaType.NETWORK_JSON_V1)
    @Produces(NeutronMediaType.NETWORK_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public Response update(@PathParam("id") UUID id, Network network) {
        log.info("NetworkResource.update entered {}", network);

        Network net = api.updateNetwork(id, network);
        NETWORK_EVENT.update(id, net);
        log.info("NetworkResource.update exiting {}", net);
        return Response.ok(
            NeutronUriBuilder.getNetwork(
                getBaseUri(), net.id)).entity(net).build();
    }
}
