/*
 * @(#)AdminResource        1.6 11/11/15
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.rest_api.resources;

import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
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
import com.midokura.midolman.mgmt.config.InvalidConfigException;
import com.midokura.midolman.mgmt.data.DaoFactory;
import com.midokura.midolman.mgmt.data.dao.AdminDao;
import com.midokura.midolman.mgmt.data.dto.Admin;
import com.midokura.midolman.mgmt.rest_api.core.VendorMediaType;
import com.midokura.midolman.mgmt.rest_api.jaxrs.UnknownRestApiException;
import com.midokura.midolman.state.StateAccessException;

/**
 * Root resource class for admin.
 *
 * @version 1.6 15 Nov 2011
 * @author Ryu Ishimoto
 */
public class AdminResource {

    private final static String initPath = "/init";

    private final static Logger log = LoggerFactory
            .getLogger(AdminResource.class);

    /**
     * Handler for getting administrative resource.
     *
     * @param uriInfo
     *            Object that holds the request URI data.
     * @throws InvalidConfigException
     *             Configuration is not set correctly.
     * @returns Admin object.
     */
    @GET
    @Produces({ VendorMediaType.APPLICATION_ADMIN_JSON,
            MediaType.APPLICATION_JSON })
    public Admin get(@Context UriInfo uriInfo) throws InvalidConfigException {
        return new Admin(uriInfo.getBaseUri());
    }

    /**
     * Handler for initializing data storage.
     *
     * @param context
     *            Object that holds the security data.
     * @param daoFactory
     *            Data access factory object.
     * @throws StateAccessException
     *             Data access error.
     * @throws UnauthorizedException
     *             Authentication/authorization error.
     * @returns A Response object indicating the status of the request.
     */
    @POST
    @Path(initPath)
    public Response init(@Context SecurityContext context,
            @Context DaoFactory daoFactory) throws StateAccessException,
            UnauthorizedException {
        if (!AuthManager.isAdmin(context)) {
            throw new UnauthorizedException("Must be admin to initialized ZK.");
        }

        AdminDao dao = daoFactory.getAdminDao();
        try {
            dao.initialize();
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
