/*
 * @(#)VifResource        1.6 11/09/24
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.rest_api.resources;

import java.util.List;
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
import com.midokura.midolman.mgmt.data.dao.PortDao;
import com.midokura.midolman.mgmt.data.dao.VifDao;
import com.midokura.midolman.mgmt.data.dto.UriResource;
import com.midokura.midolman.mgmt.data.dto.Vif;
import com.midokura.midolman.mgmt.rest_api.core.UriManager;
import com.midokura.midolman.mgmt.rest_api.core.VendorMediaType;
import com.midokura.midolman.mgmt.rest_api.jaxrs.UnknownRestApiException;
import com.midokura.midolman.state.NoStatePathException;
import com.midokura.midolman.state.StateAccessException;

/**
 * Root resource class for VIFs.
 *
 * @version 1.6 24 Sept 2011
 * @author Ryu Ishimoto
 */
public class VifResource {
    /*
     * Implements REST API endpoints for VIFs.
     */

    private final static Logger log = LoggerFactory
            .getLogger(VifResource.class);

    private boolean isPortOwner(SecurityContext context, UUID portId,
            DaoFactory daoFactory) throws StateAccessException {
        PortDao q = daoFactory.getPortDao();
        return AuthManager.isOwner(context, (OwnerQueryable) q, portId);
    }

    private boolean isPluggedToOwnPort(SecurityContext context, UUID vifId,
            DaoFactory daoFactory) throws StateAccessException {
        VifDao q = daoFactory.getVifDao();
        return AuthManager.isOwner(context, q, vifId);
    }

    /**
     * Handler to create a VIF.
     *
     * @param context
     *            Object that holds the security data.
     * @param uriInfo
     *            Object that holds the request URI data.
     * @param daoFactory
     *            Data access factory object.
     * @throws StateAccessException
     *             Data access error.
     * @throws UnauthorizedException
     *             Authentication/authorization error.
     * @returns Response object with 201 status code set if successful.
     */
    @POST
    @Consumes({ VendorMediaType.APPLICATION_VIF_JSON,
            MediaType.APPLICATION_JSON })
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
        return Response.created(UriManager.getVif(uriInfo.getBaseUri(), id))
                .build();
    }

    /**
     * Handler to getting a VIF.
     *
     * @param id
     *            VIF ID from the request.
     * @param context
     *            Object that holds the security data.
     * @param uriInfo
     *            Object that holds the request URI data.
     * @param daoFactory
     *            Data access factory object.
     * @throws StateAccessException
     *             Data access error.
     * @throws UnauthorizedException
     *             Authentication/authorization error.
     * @return A VIF object.
     */
    @GET
    @Path("{id}")
    @Produces({ VendorMediaType.APPLICATION_VIF_JSON,
            MediaType.APPLICATION_JSON })
    public Vif get(@PathParam("id") UUID id, @Context SecurityContext context,
            @Context UriInfo uriInfo, @Context DaoFactory daoFactory)
            throws StateAccessException, UnauthorizedException {
        if (!isPluggedToOwnPort(context, id, daoFactory)) {
            throw new UnauthorizedException(
                    "Can only see VIFs plugged into your port.");
        }

        VifDao dao = daoFactory.getVifDao();
        Vif vif = null;
        try {
            vif = dao.get(id);
        } catch (StateAccessException e) {
            log.error("Error accessing data", e);
            throw e;
        } catch (Exception e) {
            log.error("Unhandled error", e);
            throw new UnknownRestApiException(e);
        }
        vif.setBaseUri(uriInfo.getBaseUri());
        return vif;
    }

    /**
     * Handler to deleting a VIF.
     *
     * @param id
     *            VIF ID from the request.
     * @param context
     *            Object that holds the security data.
     * @param daoFactory
     *            Data access factory object.
     * @throws StateAccessException
     *             Data access error.
     * @throws UnauthorizedException
     *             Authentication/authorization error.
     */
    @DELETE
    @Path("{id}")
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
        } catch (NoStatePathException e) {
            // Deleting a non-existing record is OK.
            log.warn("The resource does not exist", e);
        } catch (StateAccessException e) {
            log.error("Error accessing data", e);
            throw e;
        } catch (Exception e) {
            log.error("Unhandled error", e);
            throw new UnknownRestApiException(e);
        }
        return Response.noContent().build();
    }

    /**
     * Handler to list VIFs.
     *
     * @param context
     *            Object that holds the security data.
     * @param uriInfo
     *            Object that holds the request URI data.
     * @param daoFactory
     *            Data access factory object.
     * @throws StateAccessException
     *             Data access error.
     * @throws UnauthorizedException
     *             Authentication/authorization error.
     * @return A list of VIF objects.
     */
    @GET
    @Produces({ VendorMediaType.APPLICATION_VIF_COLLECTION_JSON,
            MediaType.APPLICATION_JSON })
    public List<Vif> list(@Context SecurityContext context,
            @Context UriInfo uriInfo, @Context DaoFactory daoFactory)
            throws StateAccessException, UnauthorizedException {
        if (!AuthManager.isAdmin(context)) {
            throw new UnauthorizedException("Must be admin to see all VIFs.");
        }

        VifDao dao = daoFactory.getVifDao();
        List<Vif> vifs = null;
        try {
            vifs = dao.list();
        } catch (StateAccessException e) {
            log.error("Error accessing data", e);
            throw e;
        } catch (Exception e) {
            log.error("Unhandled error", e);
            throw new UnknownRestApiException(e);
        }

        for (UriResource resource : vifs) {
            resource.setBaseUri(uriInfo.getBaseUri());
        }
        return vifs;
    }
}
