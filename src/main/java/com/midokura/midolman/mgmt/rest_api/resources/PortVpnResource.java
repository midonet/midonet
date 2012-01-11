/*
 * @(#)PortVpnResource        1.6 12/1/11
 *
 * Copyright 2012 Midokura KK
 */
package com.midokura.midolman.mgmt.rest_api.resources;

import java.util.List;
import java.util.UUID;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
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
import com.midokura.midolman.mgmt.data.dao.VpnDao;
import com.midokura.midolman.mgmt.data.dto.UriResource;
import com.midokura.midolman.mgmt.data.dto.Vpn;
import com.midokura.midolman.mgmt.rest_api.core.UriManager;
import com.midokura.midolman.mgmt.rest_api.core.VendorMediaType;
import com.midokura.midolman.mgmt.rest_api.jaxrs.UnknownRestApiException;
import com.midokura.midolman.state.StateAccessException;

/**
 * Sub-resource class for port's VPN.
 */
public class PortVpnResource {

    private final static Logger log = LoggerFactory
            .getLogger(PortVpnResource.class);

    private UUID portId = null;

    /**
     * Constructor
     *
     * @param portId
     *            ID of a port.
     */
    public PortVpnResource(UUID portId) {
        this.portId = portId;
    }

    /**
     * Handler for creating a VPN record.
     *
     * @param chain
     *            VPN object.
     * @param uriInfo
     *            Object that holds the request URI data.
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
     * @returns Response object with 201 status code set if successful.
     */
    @POST
    @Consumes({ VendorMediaType.APPLICATION_VPN_JSON,
            MediaType.APPLICATION_JSON })
    public Response create(Vpn vpn, @Context UriInfo uriInfo,
            @Context SecurityContext context, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException,
            UnauthorizedException {

        VpnDao dao = daoFactory.getVpnDao();
        vpn.setPublicPortId(portId);
        UUID id = null;
        try {
            if (!authorizer.portAuthorized(context, AuthAction.WRITE, portId)) {
                throw new UnauthorizedException(
                        "Not authorized to add VPN to this port.");
            }
            id = dao.create(vpn);
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

        return Response.created(UriManager.getVpn(uriInfo.getBaseUri(), id))
                .build();
    }

    /**
     * Handler to getting a list of VPN records.
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
     * @return A list of VPN objects.
     */
    @GET
    @Produces({ VendorMediaType.APPLICATION_VPN_COLLECTION_JSON,
            MediaType.APPLICATION_JSON })
    public List<Vpn> list(@Context SecurityContext context,
            @Context UriInfo uriInfo, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException,
            UnauthorizedException {

        VpnDao dao = daoFactory.getVpnDao();
        List<Vpn> vpns = null;
        try {
            if (!authorizer.portAuthorized(context, AuthAction.READ, portId)) {
                throw new UnauthorizedException(
                        "Not authorized to view these VPNs.");
            }
            vpns = dao.list(portId);
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
        for (UriResource resource : vpns) {
            resource.setBaseUri(uriInfo.getBaseUri());
        }
        return vpns;
    }
}