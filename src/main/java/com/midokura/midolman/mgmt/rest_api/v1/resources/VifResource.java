/*
 * @(#)VifResource        1.6 11/09/24
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.rest_api.v1.resources;

import java.net.URI;
import java.util.UUID;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.auth.AuthManager;
import com.midokura.midolman.mgmt.auth.UnauthorizedException;
import com.midokura.midolman.mgmt.data.DaoFactory;
import com.midokura.midolman.mgmt.data.dao.OwnerQueryable;
import com.midokura.midolman.mgmt.data.dao.VifDao;
import com.midokura.midolman.mgmt.data.dto.Vif;
import com.midokura.midolman.state.StateAccessException;

/**
 * Root resource class for VIFs.
 * 
 * @version 1.6 24 Sept 2011
 * @author Ryu Ishimoto
 */
@Path("/vifs")
public class VifResource {
    /*
     * Implements REST API endpoints for VIFs.
     */

    private final static Logger log = LoggerFactory
            .getLogger(VifResource.class);

    private boolean isPortOwner(SecurityContext context, UUID portId,
            DaoFactory daoFactory) throws StateAccessException {
        OwnerQueryable q = daoFactory.getPortDao();
        return AuthManager.isOwner(context, q, portId);
    }

    private boolean isPluggedToOwnPort(SecurityContext context, UUID vifId,
            DaoFactory daoFactory) throws StateAccessException {
        VifDao q = daoFactory.getVifDao();
        Vif v = q.get(vifId);
        return AuthManager.isOwner(context, q, v.getPortId());
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response create(Vif vif, @Context UriInfo uriInfo,
            @Context SecurityContext context, @Context DaoFactory daoFactory)
            throws StateAccessException, UnauthorizedException {
        if (vif.getPortId() == null) {
            throw new IllegalArgumentException("Port ID is missing");
        }

        if (!isPortOwner(context, vif.getPortId(), daoFactory)) {
            throw new UnauthorizedException("Can only plug into your port.");
        }

        VifDao dao = daoFactory.getVifDao();
        UUID id = null;
        try {
            id = dao.create(vif);
        } catch (StateAccessException e) {
            log.error("Error accessing data", e);
            throw e;
        } catch (Exception e) {
            log.error("Unhandled error", e);
            throw new UnknownRestApiException(e);
        }
        URI uri = uriInfo.getBaseUriBuilder().path("vifs/" + id).build();
        return Response.created(uri).build();
    }

    @GET
    @Path("{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Vif get(@PathParam("id") UUID id, @Context SecurityContext context,
            @Context DaoFactory daoFactory) throws StateAccessException,
            UnauthorizedException {
        if (!isPluggedToOwnPort(context, id, daoFactory)) {
            throw new UnauthorizedException(
                    "Can only see VIFs plugged into your port.");
        }

        VifDao dao = daoFactory.getVifDao();
        try {
            return dao.get(id);
        } catch (StateAccessException e) {
            log.error("Error accessing data", e);
            throw e;
        } catch (Exception e) {
            log.error("Unhandled error", e);
            throw new UnknownRestApiException(e);
        }
    }

    @DELETE
    @Path("{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response delete(@PathParam("id") UUID id,
            @Context SecurityContext context, @Context DaoFactory daoFactory)
            throws StateAccessException, UnauthorizedException {
        if (!isPluggedToOwnPort(context, id, daoFactory)) {
            throw new UnauthorizedException(
                    "Can only delete VIFs plugged into your port.");
        }

        VifDao dao = daoFactory.getVifDao();
        try {
            dao.delete(id);
        } catch (StateAccessException e) {
            log.error("Error accessing data", e);
            throw e;
        } catch (Exception e) {
            log.error("Unhandled error", e);
            throw new UnknownRestApiException(e);
        }
        return Response.ok().build();
    }

}
