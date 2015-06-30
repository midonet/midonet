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
import org.midonet.cluster.rest_api.neutron.models.Subnet;
import org.midonet.cluster.services.rest_api.neutron.plugin.NetworkApi;
import org.midonet.event.neutron.SubnetEvent;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;

import static org.midonet.cluster.rest_api.validation.MessageProperty.RESOURCE_NOT_FOUND;
import static org.midonet.cluster.rest_api.validation.MessageProperty.getMessage;

public class SubnetResource extends AbstractResource {

    private final static Logger log = LoggerFactory.getLogger(
            SubnetResource.class);
    private final static SubnetEvent SUBNET_EVENT =
            new SubnetEvent();

    private final NetworkApi api;

    @Inject
    public SubnetResource(RestApiConfig config, UriInfo uriInfo,
                          SecurityContext context, NetworkApi api) {
        super(config, uriInfo, context, null, null);
        this.api = api;
    }

    @POST
    @Consumes(NeutronMediaType.SUBNET_JSON_V1)
    @Produces(NeutronMediaType.SUBNET_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public Response create(Subnet subnet)
            throws SerializationException, StateAccessException {
        log.info("SubnetResource.create entered {}", subnet);

        Subnet sub = api.createSubnet(subnet);
        SUBNET_EVENT.create(sub.id, sub);
        log.info("SubnetResource.create exiting {}", sub);
        return Response.created(
            NeutronUriBuilder.getSubnet(
                getBaseUri(), sub.id)).entity(sub).build();

    }

    @POST
    @Consumes(NeutronMediaType.SUBNETS_JSON_V1)
    @Produces(NeutronMediaType.SUBNETS_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public Response createBulk(List<Subnet> subnets)
        throws SerializationException, StateAccessException {
        log.info("SubnetResource.createBulk entered");
        List<Subnet> nets = api.createSubnetBulk(subnets);
        for (Subnet subnet : nets) {
            SUBNET_EVENT.create(subnet.id, subnet);
        }
        return Response.created(NeutronUriBuilder.getSubnets(
            getBaseUri())).entity(nets).build();
    }

    @DELETE
    @Path("{id}")
    @RolesAllowed(AuthRole.ADMIN)
    public void delete(@PathParam("id") UUID id)
            throws SerializationException, StateAccessException {
        log.info("SubnetResource.delete entered {}", id);
        api.deleteSubnet(id);
        SUBNET_EVENT.delete(id);
    }

    @GET
    @Path("{id}")
    @Produces(NeutronMediaType.SUBNET_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public Subnet get(@PathParam("id") UUID id)
            throws SerializationException, StateAccessException {
        log.info("SubnetResource.get entered {}", id);

        Subnet sub = api.getSubnet(id);
        if (sub == null) {
            throw new NotFoundHttpException(getMessage(RESOURCE_NOT_FOUND));
        }

        log.info("SubnetResource.get exiting {}", sub);
        return sub;
    }

    @GET
    @Produces(NeutronMediaType.SUBNETS_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public List<Subnet> list()
            throws SerializationException, StateAccessException {
        log.info("SubnetResource.list entered");
        return api.getSubnets();
    }

    @PUT
    @Path("{id}")
    @Consumes(NeutronMediaType.SUBNET_JSON_V1)
    @Produces(NeutronMediaType.SUBNET_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public Response update(@PathParam("id") UUID id, Subnet subnet) {
        log.info("SubnetResource.update entered {}", subnet);
        Subnet sub = api.updateSubnet(id, subnet);
        SUBNET_EVENT.update(id, sub);
        log.info("SubnetResource.update exiting {}", sub);
        return Response.ok(
            NeutronUriBuilder.getSubnet(
                getBaseUri(), sub.id)).entity(sub).build();
    }
}
