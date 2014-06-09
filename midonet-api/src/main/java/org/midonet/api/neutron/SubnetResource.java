/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.api.neutron;

import com.google.inject.Inject;
import org.midonet.api.auth.AuthRole;
import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.ConflictHttpException;
import org.midonet.api.rest_api.NotFoundHttpException;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.client.neutron.NeutronMediaType;
import org.midonet.cluster.data.neutron.NetworkApi;
import org.midonet.cluster.data.neutron.Subnet;
import org.midonet.event.neutron.SubnetEvent;
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

public class SubnetResource extends AbstractResource {

    private final static Logger log = LoggerFactory.getLogger(
            SubnetResource.class);
    private final static SubnetEvent SUBNET_EVENT =
            new SubnetEvent();

    private final NetworkApi api;

    @Inject
    public SubnetResource(RestApiConfig config, UriInfo uriInfo,
                          SecurityContext context, NetworkApi api) {
        super(config, uriInfo, context, null);
        this.api = api;
    }

    @POST
    @Consumes(NeutronMediaType.SUBNET_JSON_V1)
    @Produces(NeutronMediaType.SUBNET_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public Response create(Subnet subnet)
            throws SerializationException, StateAccessException {
        log.info("SubnetResource.create entered {}", subnet);

        try {

            Subnet sub = api.createSubnet(subnet);
            SUBNET_EVENT.create(sub.id, sub);
            log.info("SubnetResource.create exiting {}", sub);
            return Response.created(
                    NeutronUriBuilder.getSubnet(
                            getBaseUri(), sub.id)).entity(sub).build();

        } catch (StatePathExistsException e) {
            log.error("Duplicate resource error", e);
            throw new ConflictHttpException(e, getMessage(RESOURCE_EXISTS));
        }
    }

    @POST
    @Consumes(NeutronMediaType.SUBNETS_JSON_V1)
    @Produces(NeutronMediaType.SUBNETS_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public Response createBulk(List<Subnet> subnets)
            throws SerializationException, StateAccessException {
        log.info("SubnetResource.createBulk entered");

        try {
            List<Subnet> nets = api.createSubnetBulk(subnets);
            for (Subnet subnet : nets) {
                SUBNET_EVENT.create(subnet.id, subnet);
            }
            return Response.created(NeutronUriBuilder.getSubnets(
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
    public Response update(@PathParam("id") UUID id, Subnet subnet)
            throws SerializationException, StateAccessException,
            BridgeZkManager.VxLanPortIdUpdateException {
        log.info("SubnetResource.update entered {}", subnet);

        try {

            Subnet sub = api.updateSubnet(id, subnet);
            SUBNET_EVENT.update(id, sub);
            log.info("SubnetResource.update exiting {}", sub);
            return Response.ok(
                    NeutronUriBuilder.getSubnet(
                            getBaseUri(), sub.id)).entity(sub).build();

        } catch (NoStatePathException e) {
            log.error("Resource does not exist", e);
            throw new NotFoundHttpException(e, getMessage(RESOURCE_NOT_FOUND));
        }
    }
}
