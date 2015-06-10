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
import org.midonet.cluster.rest_api.neutron.models.Network;
import org.midonet.cluster.services.rest_api.neutron.plugin.NetworkApi;

import static org.midonet.cluster.rest_api.validation.MessageProperty.RESOURCE_NOT_FOUND;
import static org.midonet.cluster.rest_api.validation.MessageProperty.getMessage;

public class NetworkResource {

    private final NetworkApi api;
    private final URI baseUri;

    @Inject
    public NetworkResource(UriInfo uriInfo, NetworkApi api) {
        this.api = api;
        this.baseUri = uriInfo.getBaseUri();
    }

    @POST
    @Consumes(NeutronMediaType.NETWORK_JSON_V1)
    @Produces(NeutronMediaType.NETWORK_JSON_V1)
    public Response create(Network network) {
        Network net = api.createNetwork(network);
        return Response.created(NeutronUriBuilder.getNetwork(baseUri,
                                                             net.id))
                       .entity(net).build();
    }

    @POST
    @Consumes(NeutronMediaType.NETWORKS_JSON_V1)
    @Produces(NeutronMediaType.NETWORKS_JSON_V1)
    public Response createBulk(List<Network> networks) {
        List<Network> nets = api.createNetworkBulk(networks);
        return Response.created(NeutronUriBuilder.getNetworks(baseUri))
                       .entity(nets).build();
    }

    @DELETE
    @Path("{id}")
    public void delete(@PathParam("id") UUID id) {
        api.deleteNetwork(id);
    }

    @GET
    @Path("{id}")
    @Produces(NeutronMediaType.NETWORK_JSON_V1)
    public Network get(@PathParam("id") UUID id) {
        Network net = api.getNetwork(id);
        if (net == null) {
            throw new NotFoundHttpException(getMessage(RESOURCE_NOT_FOUND));
        }
        return net;
    }

    @GET
    @Produces(NeutronMediaType.NETWORKS_JSON_V1)
    public List<Network> list() {
        return api.getNetworks();
    }

    @PUT
    @Path("{id}")
    @Consumes(NeutronMediaType.NETWORK_JSON_V1)
    @Produces(NeutronMediaType.NETWORK_JSON_V1)
    public Response update(@PathParam("id") UUID id, Network network) {
        Network net = api.updateNetwork(id, network);
        return Response.ok(NeutronUriBuilder.getNetwork(baseUri,
                                                        net.id))
                       .entity(net).build();
    }
}
