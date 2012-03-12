/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midolman.mgmt.rest_api.resources;

import java.util.List;
import java.util.UUID;

import javax.ws.rs.GET;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.auth.AuthAction;
import com.midokura.midolman.mgmt.auth.Authorizer;
import com.midokura.midolman.mgmt.auth.UnauthorizedException;
import com.midokura.midolman.mgmt.data.DaoFactory;
import com.midokura.midolman.mgmt.data.dao.PortDao;
import com.midokura.midolman.mgmt.data.dto.FilteringDbEntry;
import com.midokura.midolman.mgmt.data.dto.Port;
import com.midokura.midolman.mgmt.data.dto.UriResource;
import com.midokura.midolman.mgmt.rest_api.core.VendorMediaType;
import com.midokura.midolman.mgmt.rest_api.jaxrs.UnknownRestApiException;
import com.midokura.midolman.state.StateAccessException;

public class BridgeFilterDbResource {
    private final static Logger log = LoggerFactory
            .getLogger(BridgeFilterDbResource.class);
    private final UUID bridgeId;

    public BridgeFilterDbResource(UUID bridgeId) {
        this.bridgeId = bridgeId;
    }

    /**
     * Handler to list filtering database static entries.
     *
     * @param context
     *            Object that holds the security data.
     * @param uriInfo
     *            Object that holds the request URI data.
     * @param daoFactory
     *            Data access factory object.
     * @param authorizer
     *            Authorizer object.
     * @throws com.midokura.midolman.state.StateAccessException
     *             Data access error.
     * @throws com.midokura.midolman.mgmt.auth.UnauthorizedException
     *             Authentication/authorization error.
     * @return A list of FilteringDbEntry objects.
     */
    @GET
    @Produces({ MediaType.APPLICATION_JSON })
    public List<FilteringDbEntry> list(@Context SecurityContext context,
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
        return null;
    }
}
