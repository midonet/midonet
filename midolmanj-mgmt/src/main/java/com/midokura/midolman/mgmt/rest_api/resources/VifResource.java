/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
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
import com.midokura.midolman.mgmt.data.DaoFactory;
import com.midokura.midolman.mgmt.data.dao.VifDao;
import com.midokura.midolman.mgmt.data.dto.UriResource;
import com.midokura.midolman.mgmt.data.dto.Vif;
import com.midokura.midolman.mgmt.rest_api.core.ResourceUriBuilder;
import com.midokura.midolman.mgmt.rest_api.core.VendorMediaType;
import com.midokura.midolman.mgmt.rest_api.jaxrs.BadRequestHttpException;
import com.midokura.midolman.mgmt.rest_api.jaxrs.ForbiddenHttpException;
import com.midokura.midolman.state.NoStatePathException;
import com.midokura.midolman.state.StateAccessException;

/**
 * Root resource class for VIFs.
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
     * @returns Response object with 201 status code set if successful.
     */
    @POST
    @Consumes({ VendorMediaType.APPLICATION_VIF_JSON,
            MediaType.APPLICATION_JSON })
    public Response create(Vif vif, @Context UriInfo uriInfo,
            @Context SecurityContext context, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException {

        if (vif.getPortId() == null) {
            throw new BadRequestHttpException("Port ID is missing.");
        }

        if (!authorizer.vifAuthorized(context, AuthAction.WRITE,
                vif.getPortId())) {
            throw new ForbiddenHttpException(
                    "Not authorized to plug VIF to this port.");
        }

        VifDao dao = daoFactory.getVifDao();
        UUID id = dao.create(vif);
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
     */
    @DELETE
    @Path("{id}")
    public void delete(@PathParam("id") UUID id,
            @Context SecurityContext context, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException {

        try {
            VifDao dao = daoFactory.getVifDao();
            Vif vif = dao.get(id);
            if (vif != null) {
                if (!authorizer.vifAuthorized(context, AuthAction.WRITE,
                        vif.getPortId())) {
                    throw new ForbiddenHttpException(
                            "Not authorized to unplug VIF to this port.");
                }

                dao.delete(id);
            }
        } catch (NoStatePathException e) {
            // Deleting a non-existing record is OK.
            log.warn("The resource does not exist", e);
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
     * @return A VIF object.
     */
    @GET
    @Path("{id}")
    @Produces({ VendorMediaType.APPLICATION_VIF_JSON,
            MediaType.APPLICATION_JSON })
    public Vif get(@PathParam("id") UUID id, @Context SecurityContext context,
            @Context UriInfo uriInfo, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException {

        VifDao dao = daoFactory.getVifDao();
        Vif vif = dao.get(id);
        if (vif != null) {
            if (!authorizer.vifAuthorized(context, AuthAction.READ,
                    vif.getPortId())) {
                throw new ForbiddenHttpException(
                        "Not authorized to view this VIF.");
            }
            vif.setBaseUri(uriInfo.getBaseUri());
        }
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
     * @return A list of VIF objects.
     */
    @GET
    @Produces({ VendorMediaType.APPLICATION_VIF_COLLECTION_JSON,
            MediaType.APPLICATION_JSON })
    public List<Vif> list(@Context SecurityContext context,
            @Context UriInfo uriInfo, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException {

        if (!authorizer.isAdmin(context)) {
            throw new ForbiddenHttpException("Not authorized to view all VIFs.");
        }

        VifDao dao = daoFactory.getVifDao();
        List<Vif> vifs = dao.list();
        if (vifs != null) {
            for (UriResource resource : vifs) {
                resource.setBaseUri(uriInfo.getBaseUri());
            }
        }
        return vifs;
    }
}
