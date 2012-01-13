/*
 * @(#)BridgePortResource        1.6 12/1/11
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
import com.midokura.midolman.mgmt.data.dao.PortDao;
import com.midokura.midolman.mgmt.data.dto.BridgePort;
import com.midokura.midolman.mgmt.data.dto.Port;
import com.midokura.midolman.mgmt.data.dto.UriResource;
import com.midokura.midolman.mgmt.rest_api.core.UriManager;
import com.midokura.midolman.mgmt.rest_api.core.VendorMediaType;
import com.midokura.midolman.mgmt.rest_api.jaxrs.UnknownRestApiException;
import com.midokura.midolman.state.StateAccessException;

/**
 * Sub-resource class for bridge's ports.
 */
public class BridgePortResource {

    private final static Logger log = LoggerFactory
            .getLogger(BridgePortResource.class);
    private final UUID bridgeId;

    /**
     * Constructor.
     *
     * @param bridgeId
     *            UUID of a bridge.
     */
    public BridgePortResource(UUID bridgeId) {
        this.bridgeId = bridgeId;
    }

    /**
     * Handler to create a bridge port.
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
    @Consumes({ VendorMediaType.APPLICATION_PORT_JSON,
            MediaType.APPLICATION_JSON })
    public Response create(BridgePort port, @Context UriInfo uriInfo,
            @Context SecurityContext context, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException,
            UnauthorizedException {

        PortDao dao = daoFactory.getPortDao();
        port.setDeviceId(bridgeId);
        port.setVifId(null); // Don't allow any VIF plugging in create.
        UUID id = null;
        try {
            if (!authorizer.bridgeAuthorized(context, AuthAction.WRITE,
                    bridgeId)) {
                throw new UnauthorizedException(
                        "Not authorized to add port to this bridge.");
            }
            id = dao.create(port);
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

        return Response.created(UriManager.getPort(uriInfo.getBaseUri(), id))
                .build();
    }

    /**
     * Handler to list bridge ports.
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
     * @return A list of Port objects.
     */
    @GET
    @Produces({ VendorMediaType.APPLICATION_PORT_COLLECTION_JSON,
            MediaType.APPLICATION_JSON })
    public List<Port> list(@Context SecurityContext context,
            @Context UriInfo uriInfo, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException,
            UnauthorizedException {

        PortDao dao = daoFactory.getPortDao();
        List<Port> ports = null;
        try {
            if (!authorizer
                    .bridgeAuthorized(context, AuthAction.READ, bridgeId)) {
                throw new UnauthorizedException(
                        "Not authorized to view these ports.");
            }
            ports = dao.listBridgePorts(bridgeId);
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

        for (UriResource resource : ports) {
            resource.setBaseUri(uriInfo.getBaseUri());
        }
        return ports;
    }
}