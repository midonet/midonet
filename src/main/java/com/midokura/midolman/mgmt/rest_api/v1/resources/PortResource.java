/*
 * @(#)PortResource        1.6 11/09/05
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.rest_api.v1.resources;

import java.net.URI;
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
import com.midokura.midolman.mgmt.data.dto.MaterializedRouterPort;
import com.midokura.midolman.mgmt.data.dto.Port;
import com.midokura.midolman.mgmt.rest_api.v1.resources.BgpResource.PortBgpResource;
import com.midokura.midolman.mgmt.rest_api.v1.resources.RouteResource.PortRouteResource;
import com.midokura.midolman.mgmt.rest_api.v1.resources.VpnResource.PortVpnResource;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkStateSerializationException;

/**
 * Root resource class for ports.
 * 
 * @version 1.6 08 Sept 2011
 * @author Ryu Ishimoto
 */
@Path("/ports")
public class PortResource {
    /*
     * Implements REST API endpoints for ports.
     */

    private final static Logger log = LoggerFactory
            .getLogger(PortResource.class);

    /**
     * Port resource locator for bgp
     */
    @Path("/{id}/bgps")
    public PortBgpResource getBgpResource(@PathParam("id") UUID id) {
        return new PortBgpResource(id);
    }

    /**
     * Route resource locator for ports
     */
    @Path("/{id}/routes")
    public PortRouteResource getRouteResource(@PathParam("id") UUID id) {
        return new PortRouteResource(id);
    }

    /**
     * Port resource locator for vpn
     */
    @Path("/{id}/vpns")
    public PortVpnResource getVpnResource(@PathParam("id") UUID id) {
        return new PortVpnResource(id);
    }

    /**
     * Get the port with the given ID.
     * 
     * @param id
     *            Port UUID.
     * @return Port object.
     * @throws StateAccessException
     * @throws UnauthorizedException
     * @throws Exception
     * @throws Exception
     */
    @GET
    @Path("{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Port get(@PathParam("id") UUID id, @Context SecurityContext context,
            @Context DaoFactory daoFactory) throws StateAccessException,
            UnauthorizedException {
        // Get a port for the given ID.
        PortDao dao = daoFactory.getPortDao();
        if (!AuthManager.isOwner(context, dao, id)) {
            throw new UnauthorizedException("Can only see your own port.");
        }

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
    public void delete(@PathParam("id") UUID id,
            @Context SecurityContext context, @Context DaoFactory daoFactory)
            throws StateAccessException, UnauthorizedException {
        PortDao dao = daoFactory.getPortDao();
        if (!AuthManager.isOwner(context, dao, id)) {
            throw new UnauthorizedException("Can only delete your own port.");
        }

        try {
            dao.delete(id);
        } catch (StateAccessException e) {
            log.error("Error accessing data", e);
            throw e;
        } catch (Exception e) {
            log.error("Unhandled error", e);
            throw new UnknownRestApiException(e);
        }
    }

    public static class BridgePortResource {

        private UUID bridgeId = null;

        public BridgePortResource(UUID bridgeId) {
            this.bridgeId = bridgeId;
        }

        private boolean isBridgeOwner(SecurityContext context,
                DaoFactory daoFactory) throws StateAccessException {
            OwnerQueryable q = daoFactory.getBridgeDao();
            return AuthManager.isOwner(context, q, bridgeId);
        }

        @POST
        @Consumes(MediaType.APPLICATION_JSON)
        public Response create(Port port, @Context UriInfo uriInfo,
                @Context SecurityContext context, @Context DaoFactory daoFactory)
                throws StateAccessException, UnauthorizedException {

            if (!isBridgeOwner(context, daoFactory)) {
                throw new UnauthorizedException("Can only see your own ports.");
            }

            PortDao dao = daoFactory.getPortDao();
            port.setDeviceId(bridgeId);

            UUID id = null;
            try {
                id = dao.create(port);
            } catch (StateAccessException e) {
                log.error("Error accessing data", e);
                throw e;
            } catch (Exception e) {
                log.error("Unhandled error", e);
                throw new UnknownRestApiException(e);
            }

            URI uri = uriInfo.getBaseUriBuilder().path("ports/" + id).build();
            return Response.created(uri).build();
        }

        @GET
        @Produces(MediaType.APPLICATION_JSON)
        public List<Port> list(@Context SecurityContext context,
                @Context DaoFactory daoFactory) throws StateAccessException,
                UnauthorizedException {

            if (!isBridgeOwner(context, daoFactory)) {
                throw new UnauthorizedException("Can only see your own ports.");
            }

            PortDao dao = daoFactory.getPortDao();
            try {
                return dao.listBridgePorts(bridgeId);
            } catch (StateAccessException e) {
                log.error("Error accessing data", e);
                throw e;
            } catch (Exception e) {
                log.error("Unhandled error", e);
                throw new UnknownRestApiException(e);
            }
        }
    }

    /**
     * Sub-resource class for router's ports.
     */
    public static class RouterPortResource {

        private UUID routerId = null;

        public RouterPortResource(UUID routerId) {
            this.routerId = routerId;
        }

        private boolean isRouterOwner(SecurityContext context,
                DaoFactory daoFactory) throws StateAccessException,
                ZkStateSerializationException {
            OwnerQueryable q = daoFactory.getRouterDao();
            return AuthManager.isOwner(context, q, routerId);
        }

        @POST
        @Consumes(MediaType.APPLICATION_JSON)
        public Response create(MaterializedRouterPort port,
                @Context UriInfo uriInfo, @Context SecurityContext context,
                @Context DaoFactory daoFactory) throws StateAccessException,
                UnauthorizedException, ZkStateSerializationException {
            if (!isRouterOwner(context, daoFactory)) {
                throw new UnauthorizedException("Can only see your own ports.");
            }

            PortDao dao = daoFactory.getPortDao();
            port.setDeviceId(routerId);

            UUID id = null;
            try {
                id = dao.create(port);
            } catch (StateAccessException e) {
                log.error("Error accessing data", e);
                throw e;
            } catch (Exception e) {
                log.error("Unhandled error", e);
                throw new UnknownRestApiException(e);
            }

            URI uri = uriInfo.getBaseUriBuilder().path("ports/" + id).build();
            return Response.created(uri).build();
        }

        @GET
        @Produces(MediaType.APPLICATION_JSON)
        public List<Port> list(@Context SecurityContext context,
                @Context DaoFactory daoFactory) throws StateAccessException,
                ZkStateSerializationException, UnauthorizedException {
            if (!isRouterOwner(context, daoFactory)) {
                throw new UnauthorizedException("Can only see your own ports.");
            }

            PortDao dao = daoFactory.getPortDao();
            try {
                return dao.listRouterPorts(routerId);
            } catch (StateAccessException e) {
                log.error("Error accessing data", e);
                throw e;
            } catch (Exception e) {
                log.error("Unhandled error", e);
                throw new UnknownRestApiException(e);
            }
        }
    }
}
