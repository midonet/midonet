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

import com.midokura.midolman.mgmt.auth.AuthAction;
import com.midokura.midolman.mgmt.auth.Authorizer;
import com.midokura.midolman.mgmt.auth.UnauthorizedException;
import com.midokura.midolman.mgmt.data.DaoFactory;
import com.midokura.midolman.mgmt.data.dao.VifDao;
import com.midokura.midolman.mgmt.data.dto.UriResource;
import com.midokura.midolman.mgmt.data.dto.Vif;
import com.midokura.midolman.mgmt.rest_api.core.ResourceUriBuilder;
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

    /**
     * Handler to create a VIF.
     *
     * @param context
     *            Object that holds the security data.
     * @param uriInfo
     *            Object that holds the request URI data.
     * @param daoFactory
     *            Data access factory object.
     * @param authorizer
     *            Authorizer object.
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
            @Context SecurityContext context, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException,
            UnauthorizedException {
        if (vif.getPortId() == null) {
            throw new IllegalArgumentException("Port ID is missing");
        }

        VifDao dao = daoFactory.getVifDao();
        UUID id = null;
        try {
            if (!authorizer.vifAuthorized(context, AuthAction.WRITE,
                    vif.getPortId())) {
                throw new UnauthorizedException(
                        "Not authorized to plug VIF to this port.");
            }
            id = dao.create(vif);
        } catch (StateAccessException e) {
            log.error("StateAccessException error.");
            throw e;
        } catch (UnauthorizedException e) {
            log.error("UnauthorizedException error.");
            throw e;
        } catch (Exception e) {
            log.error("Unhandled error.");
            throw new UnknownRestApiException(e);
        }
        return Response.created(
                ResourceUriBuilder.getVif(uriInfo.getBaseUri(), id)).build();
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
     * @param authorizer
     *            Authorizer object.
     * @throws StateAccessException
     *             Data access error.
     * @throws UnauthorizedException
     *             Authentication/authorization error.
     */
    @DELETE
    @Path("{id}")
    public void delete(@PathParam("id") UUID id,
            @Context SecurityContext context, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException,
            UnauthorizedException {

        VifDao dao = daoFactory.getVifDao();
        try {
            Vif vif = dao.get(id);
            if (!authorizer.vifAuthorized(context, AuthAction.WRITE,
                    vif.getPortId())) {
                throw new UnauthorizedException(
                        "Not authorized to unplug VIF to this port.");
            }
            dao.delete(id);
        } catch (NoStatePathException e) {
            // Deleting a non-existing record is OK.
            log.warn("The resource does not exist", e);
        } catch (StateAccessException e) {
            log.error("StateAccessException error.");
            throw e;
        } catch (UnauthorizedException e) {
            log.error("UnauthorizedException error.");
            throw e;
        } catch (Exception e) {
            log.error("Unhandled error.");
            throw new UnknownRestApiException(e);
        }
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
     * @param authorizer
     *            Authorizer object.
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
            @Context UriInfo uriInfo, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException,
            UnauthorizedException {

        VifDao dao = daoFactory.getVifDao();
        Vif vif = null;
        try {
            vif = dao.get(id);
            if (!authorizer.vifAuthorized(context, AuthAction.READ,
                    vif.getPortId())) {
                throw new UnauthorizedException(
                        "Not authorized to view this VIF.");
            }
        } catch (StateAccessException e) {
            log.error("StateAccessException error.");
            throw e;
        } catch (UnauthorizedException e) {
            log.error("UnauthorizedException error.");
            throw e;
        } catch (Exception e) {
            log.error("Unhandled error.");
            throw new UnknownRestApiException(e);
        }
        vif.setBaseUri(uriInfo.getBaseUri());
        return vif;
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
     * @param authorizer
     *            Authorizer object.
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
            @Context UriInfo uriInfo, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException,
            UnauthorizedException {

        VifDao dao = daoFactory.getVifDao();
        List<Vif> vifs = null;
        try {
            if (!authorizer.isAdmin(context)) {
                throw new UnauthorizedException(
                        "Not authorized to view all VIFs.");
            }
            vifs = dao.list();
        } catch (StateAccessException e) {
            log.error("StateAccessException error.");
            throw e;
        } catch (UnauthorizedException e) {
            log.error("UnauthorizedException error.");
            throw e;
        } catch (Exception e) {
            log.error("Unhandled error.");
            throw new UnknownRestApiException(e);
        }

        for (UriResource resource : vifs) {
            resource.setBaseUri(uriInfo.getBaseUri());
        }
        return vifs;
    }
}
