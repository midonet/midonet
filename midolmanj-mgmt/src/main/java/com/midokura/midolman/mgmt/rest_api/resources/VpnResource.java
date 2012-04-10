/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.rest_api.resources;

import java.util.UUID;

import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.auth.AuthAction;
import com.midokura.midolman.mgmt.auth.Authorizer;
import com.midokura.midolman.mgmt.data.DaoFactory;
import com.midokura.midolman.mgmt.data.dao.VpnDao;
import com.midokura.midolman.mgmt.data.dto.Vpn;
import com.midokura.midolman.mgmt.rest_api.core.VendorMediaType;
import com.midokura.midolman.mgmt.rest_api.jaxrs.ForbiddenHttpException;
import com.midokura.midolman.mgmt.rest_api.jaxrs.NotFoundHttpException;
import com.midokura.midolman.state.NoStatePathException;
import com.midokura.midolman.state.StateAccessException;

/**
 * Root resource class for vpns.
 */
public class VpnResource {
    /*
     * Implements REST API end points for vpns.
     */

    private final static Logger log = LoggerFactory
            .getLogger(VpnResource.class);

    /**
     * Handler to deleting a VPN record.
     *
     * @param id
     *            VPN ID from the request.
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

        if (!authorizer.vpnAuthorized(context, AuthAction.WRITE, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to delete this VPN.");
        }

        VpnDao dao = daoFactory.getVpnDao();
        try {
            dao.delete(id);
        } catch (NoStatePathException e) {
            // Deleting a non-existing record is OK.
            log.warn("The resource does not exist", e);
        }
    }

    /**
     * Handler to getting VPN configuration record.
     *
     * @param id
     *            VPN ID from the request.
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
     * @return A Vpn object.
     */
    @GET
    @Path("{id}")
    @Produces({ VendorMediaType.APPLICATION_VPN_JSON,
            MediaType.APPLICATION_JSON })
    public Vpn get(@PathParam("id") UUID id, @Context SecurityContext context,
            @Context UriInfo uriInfo, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException {

        if (!authorizer.vpnAuthorized(context, AuthAction.READ, id)) {
            throw new ForbiddenHttpException("Not authorized to view this VPN.");
        }

        VpnDao dao = daoFactory.getVpnDao();
        Vpn vpn = dao.get(id);
        if (vpn == null) {
            throw new NotFoundHttpException(
                    "The requested resource was not found.");
        }
        vpn.setBaseUri(uriInfo.getBaseUri());

        return vpn;
    }
}
