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

import org.midonet.cluster.backend.zookeeper.StateAccessException;
import org.midonet.cluster.rest_api.NotFoundHttpException;
import org.midonet.cluster.rest_api.neutron.NeutronMediaType;
import org.midonet.cluster.rest_api.neutron.NeutronUriBuilder;
import org.midonet.cluster.rest_api.neutron.models.Subnet;
import org.midonet.cluster.services.rest_api.neutron.plugin.NetworkApi;
import org.midonet.cluster.services.rest_api.neutron.plugin.NeutronZoomPlugin;
import org.midonet.midolman.serialization.SerializationException;

import static org.midonet.cluster.rest_api.validation.MessageProperty.RESOURCE_NOT_FOUND;
import static org.midonet.cluster.rest_api.validation.MessageProperty.getMessage;

public class SubnetResource {

    private final NetworkApi api;
    private final URI baseUri;

    @Inject
    public SubnetResource(UriInfo uriInfo, NeutronZoomPlugin api) {
        this.api = api;
        this.baseUri = uriInfo.getBaseUri();
    }

    @POST
    @Consumes(NeutronMediaType.SUBNET_JSON_V1)
    @Produces(NeutronMediaType.SUBNET_JSON_V1)
    public Response create(Subnet subnet)
            throws SerializationException, StateAccessException {
        Subnet sub = api.createSubnet(subnet);
        return Response.created(NeutronUriBuilder.getSubnet(baseUri,
                                                            sub.id))
                       .entity(sub).build();

    }

    @POST
    @Consumes(NeutronMediaType.SUBNETS_JSON_V1)
    @Produces(NeutronMediaType.SUBNETS_JSON_V1)
    public Response createBulk(List<Subnet> subnets)
        throws SerializationException, StateAccessException {
        List<Subnet> nets = api.createSubnetBulk(subnets);
        return Response.created(NeutronUriBuilder.getSubnets(baseUri))
                       .entity(nets).build();
    }

    @DELETE
    @Path("{id}")
    public void delete(@PathParam("id") UUID id)
            throws SerializationException, StateAccessException {
        api.deleteSubnet(id);
    }

    @GET
    @Path("{id}")
    @Produces(NeutronMediaType.SUBNET_JSON_V1)
    public Subnet get(@PathParam("id") UUID id)
            throws SerializationException, StateAccessException {
        Subnet sub = api.getSubnet(id);
        if (sub == null) {
            throw new NotFoundHttpException(getMessage(RESOURCE_NOT_FOUND));
        }
        return sub;
    }

    @GET
    @Produces(NeutronMediaType.SUBNETS_JSON_V1)
    public List<Subnet> list()
            throws SerializationException, StateAccessException {
        return api.getSubnets();
    }

    @PUT
    @Path("{id}")
    @Consumes(NeutronMediaType.SUBNET_JSON_V1)
    @Produces(NeutronMediaType.SUBNET_JSON_V1)
    public Response update(@PathParam("id") UUID id, Subnet subnet) {
        Subnet sub = api.updateSubnet(id, subnet);
        return Response.ok(NeutronUriBuilder.getSubnet(baseUri,
                                                       sub.id))
                       .entity(sub).build();
    }
}
