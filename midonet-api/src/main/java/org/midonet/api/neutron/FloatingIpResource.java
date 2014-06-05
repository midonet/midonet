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
import org.midonet.cluster.data.Rule;
import org.midonet.cluster.data.neutron.FloatingIp;
import org.midonet.cluster.data.neutron.L3Api;
import org.midonet.event.neutron.FloatingIpEvent;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.NoStatePathException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.StatePathExistsException;
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

public class FloatingIpResource extends AbstractResource {

    private final static Logger log = LoggerFactory.getLogger(
            FloatingIpResource.class);
    private final static FloatingIpEvent FLOATING_IP_EVENT =
            new FloatingIpEvent();

    private final L3Api api;

    @Inject
    public FloatingIpResource(RestApiConfig config, UriInfo uriInfo,
                              SecurityContext context, L3Api api) {
        super(config, uriInfo, context, null);
        this.api = api;
    }

    @POST
    @Consumes(NeutronMediaType.FLOATING_IP_JSON_V1)
    @Produces(NeutronMediaType.FLOATING_IP_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public Response create(FloatingIp floatingIp)
            throws SerializationException, StateAccessException,
            Rule.RuleIndexOutOfBoundsException {
        log.info("FloatingIpResource.create entered {}", floatingIp);

        try {

            FloatingIp fip = api.createFloatingIp(floatingIp);
            FLOATING_IP_EVENT.create(fip.id, fip);
            log.info("FloatingIpResource.create exiting {}", fip);
            return Response.created(
                    NeutronUriBuilder.getFloatingIp(
                            getBaseUri(), fip.id)).entity(fip).build();

        } catch (StatePathExistsException e) {
            log.error("Duplicate resource error", e);
            throw new ConflictHttpException(e, getMessage(RESOURCE_EXISTS));
        }
    }

    @DELETE
    @Path("{id}")
    @RolesAllowed(AuthRole.ADMIN)
    public void delete(@PathParam("id") UUID id)
            throws SerializationException, StateAccessException {
        log.info("FloatingIpResource.delete entered {}", id);
        api.deleteFloatingIp(id);
        FLOATING_IP_EVENT.delete(id);
    }

    @GET
    @Path("{id}")
    @Produces(NeutronMediaType.FLOATING_IP_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public FloatingIp get(@PathParam("id") UUID id)
            throws SerializationException, StateAccessException {
        log.info("FloatingIpResource.get entered {}", id);

        FloatingIp fip = api.getFloatingIp(id);
        if (fip == null) {
            throw new NotFoundHttpException(getMessage(RESOURCE_NOT_FOUND));
        }

        log.info("FloatingIpResource.get exiting {}", fip);
        return fip;
    }

    @GET
    @Produces(NeutronMediaType.FLOATING_IPS_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public List<FloatingIp> list()
            throws SerializationException, StateAccessException {
        log.info("FloatingIpResource.list entered");
        return api.getFloatingIps();
    }

    @PUT
    @Path("{id}")
    @Consumes(NeutronMediaType.FLOATING_IP_JSON_V1)
    @Produces(NeutronMediaType.FLOATING_IP_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public Response update(@PathParam("id") UUID id, FloatingIp floatingIp)
            throws SerializationException, StateAccessException,
            Rule.RuleIndexOutOfBoundsException {
        log.info("FloatingIpResource.update entered {}", floatingIp);

        try {

            FloatingIp fip = api.updateFloatingIp(id, floatingIp);
            FLOATING_IP_EVENT.update(id, fip);
            log.info("FloatingIpResource.update exiting {}", fip);
            return Response.ok(
                    NeutronUriBuilder.getFloatingIp(
                            getBaseUri(), fip.id)).entity(fip).build();

        } catch (NoStatePathException e) {
            log.error("Resource does not exist", e);
            throw new NotFoundHttpException(e, getMessage(RESOURCE_NOT_FOUND));
        }
    }
}
