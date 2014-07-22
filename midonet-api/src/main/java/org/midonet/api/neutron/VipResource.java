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
import org.midonet.cluster.data.neutron.LBaaSApi;
import org.midonet.cluster.data.neutron.loadbalancer.VIP;
import org.midonet.event.neutron.VipEvent;
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

public class VipResource extends AbstractResource {

    private final static Logger log = LoggerFactory.getLogger(
        VipResource.class);
    private final static VipEvent VIP_EVENT = new VipEvent();

    private final LBaaSApi api;

    @Inject
    public VipResource(RestApiConfig config, UriInfo uriInfo,
                       SecurityContext context, LBaaSApi api) {
        super(config, uriInfo, context, null);
        this.api = api;
    }

    @POST
    @Consumes(NeutronMediaType.VIP_JSON_V1)
    @Produces(NeutronMediaType.VIP_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public Response create(VIP vip)
            throws SerializationException, StateAccessException {
        log.info("VipResource.create entered {}", vip);

        try {
            VIP createdVip = api.createNeutronVip(vip);
            VIP_EVENT.create(createdVip.id, createdVip);
            log.info("VipResource.create exiting {}", createdVip);
            return Response.created(
                NeutronUriBuilder.getVip(getBaseUri(), createdVip.id))
                    .entity(createdVip).build();
        } catch (StatePathExistsException e) {
            log.error("Duplicate resource error", e);
            throw new ConflictHttpException(e, getMessage(RESOURCE_EXISTS));
        }
    }

    @POST
    @Consumes(NeutronMediaType.VIPS_JSON_V1)
    @Produces(NeutronMediaType.VIPS_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public Response createBulk(List<VIP> vips)
            throws SerializationException, StateAccessException {
        log.info("VipResource.createBulk entered");

        try {
            List<VIP> createdVips = api.createVipBulk(vips);
            for (VIP vip : createdVips) {
                VIP_EVENT.create(vip.id, vip);
            }
            return Response.created(NeutronUriBuilder.getVips(
                getBaseUri())).entity(vips).build();
        } catch (StatePathExistsException e) {
            throw new ConflictHttpException(e, getMessage(RESOURCE_EXISTS));
        }
    }

    @DELETE
    @Path("{id}")
    @RolesAllowed(AuthRole.ADMIN)
    public void delete(@PathParam("id") UUID id)
            throws SerializationException, StateAccessException {
        log.info("VipResource.delete entered {}", id);
        api.deleteVip(id);
        VIP_EVENT.delete(id);
    }

    @GET
    @Path("{id}")
    @Produces(NeutronMediaType.VIP_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public VIP get(@PathParam("id") UUID id)
            throws SerializationException, StateAccessException {
        log.info("VipResource.get entered {}", id);

        VIP vip = api.getVip(id);
        if (vip == null) {
            throw new NotFoundHttpException(getMessage(RESOURCE_NOT_FOUND));
        }

        log.info("VipResource.get exiting {}", vip);
        return vip;
    }

    @GET
    @Produces(NeutronMediaType.VIPS_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public List<VIP> list()
            throws SerializationException, StateAccessException {
        log.info("VipResource.list entered");
        List<VIP> vips = api.getVips();
        return vips;
    }

    @PUT
    @Path("{id}")
    @Consumes(NeutronMediaType.VIP_JSON_V1)
    @Produces(NeutronMediaType.VIP_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public Response update(@PathParam("id") UUID id, VIP vip)
            throws SerializationException, StateAccessException,
            BridgeZkManager.VxLanPortIdUpdateException {
        log.info("VipResource.update entered {}", vip);

        try {
            VIP updatedVip = api.updateVip(id, vip);
            VIP_EVENT.update(id, updatedVip);
            log.info("VipResource.update exiting {}", updatedVip);
            return Response.ok(
                NeutronUriBuilder.getVip(getBaseUri(), updatedVip.id))
                    .entity(updatedVip).build();
        } catch (NoStatePathException e) {
            log.error("Resource does not exist", e);
            throw new NotFoundHttpException(e, getMessage(RESOURCE_NOT_FOUND));
        }
    }
}
