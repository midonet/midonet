/*
 * @(#)BgpResource        1.6 11/09/05
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
import javax.ws.rs.PUT;
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
import com.midokura.midolman.mgmt.data.OwnerQueryable;
import com.midokura.midolman.mgmt.data.dao.BgpZkManagerProxy;
import com.midokura.midolman.mgmt.data.dao.PortZkManagerProxy;
import com.midokura.midolman.mgmt.data.dto.Bgp;
import com.midokura.midolman.mgmt.rest_api.v1.resources.AdRouteResource.BgpAdRouteResource;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkStateSerializationException;

/**
 * Root resource class for bgps.
 * 
 * @version 1.6 11 Sept 2011
 * @author Yoshi Tamura
 */
@Path("/bgps")
public class BgpResource extends RestResource {
    /*
     * Implements REST API end points for bgps.
     */

    private final static Logger log = LoggerFactory
            .getLogger(BgpResource.class);

    /**
     * Advertising route resource locator for bgps
     */
    @Path("/{id}/ad_routes")
    public BgpAdRouteResource getBgpAdRouteResource(@PathParam("id") UUID id) {
        return new BgpAdRouteResource(zooKeeper, zookeeperRoot,
                zookeeperMgmtRoot, id);
    }

    /**
     * Get the BGP with the given ID.
     * 
     * @param id
     *            BGP UUID.
     * @return Bgp object.
     * @throws StateAccessException
     * @throws UnauthorizedException
     * @throws ZkStateSerializationException
     */
    @GET
    @Path("{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Bgp get(@PathParam("id") UUID id, @Context SecurityContext context)
            throws StateAccessException, ZkStateSerializationException,
            UnauthorizedException {

        // Get a bgp for the given ID.
        BgpZkManagerProxy dao = new BgpZkManagerProxy(zooKeeper, zookeeperRoot,
                zookeeperMgmtRoot);

        if (!AuthManager.isOwner(context, dao, id)) {
            throw new UnauthorizedException(
                    "Can only see your own advertised route.");
        }

        Bgp bgp = null;
        try {
            bgp = dao.get(id);
        } catch (StateAccessException e) {
            log.error("Error accessing data", e);
            throw e;
        } catch (Exception e) {
            log.error("Unhandled error", e);
            throw new UnknownRestApiException(e);
        }
        return bgp;
    }

    @PUT
    @Path("{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response update(@PathParam("id") UUID id, Bgp bgp,
            @Context SecurityContext context) throws StateAccessException,
            ZkStateSerializationException, UnauthorizedException {
        BgpZkManagerProxy dao = new BgpZkManagerProxy(zooKeeper, zookeeperRoot,
                zookeeperMgmtRoot);

        if (!AuthManager.isOwner(context, dao, id)) {
            throw new UnauthorizedException(
                    "Can only see your own advertised route.");
        }

        try {
            dao.update(id, bgp);
        } catch (Exception e) {
            log.error("Unhandled error", e);
            throw new UnknownRestApiException(e);
        }
        return Response.ok().build();
    }

    @DELETE
    @Path("{id}")
    public void delete(@PathParam("id") UUID id,
            @Context SecurityContext context) throws StateAccessException,
            ZkStateSerializationException, UnauthorizedException {
        BgpZkManagerProxy dao = new BgpZkManagerProxy(zooKeeper, zookeeperRoot,
                zookeeperMgmtRoot);

        if (!AuthManager.isOwner(context, dao, id)) {
            throw new UnauthorizedException(
                    "Can only see your own advertised route.");
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

    /**
     * Sub-resource class for port's BGP.
     */
    public static class PortBgpResource extends RestResource {

        private UUID portId = null;

        /**
         * Constructor.
         * 
         * @param zkConn
         *            ZooKeeper connection string.
         * @param portId
         *            UUID of a port.
         */
        public PortBgpResource(Directory zkConn, String zkRootDir,
                String zkMgmtRootDir, UUID portId) {
            this.zooKeeper = zkConn;
            this.portId = portId;
            this.zookeeperRoot = zkRootDir;
            this.zookeeperMgmtRoot = zkMgmtRootDir;
        }

        private boolean isPortOwner(SecurityContext context)
                throws StateAccessException, ZkStateSerializationException {
            OwnerQueryable q = new PortZkManagerProxy(zooKeeper, zookeeperRoot,
                    zookeeperMgmtRoot);
            return AuthManager.isOwner(context, q, portId);
        }

        /**
         * Index of bgps belonging to the port.
         * 
         * @return A list of bgps.
         * @throws StateAccessException
         * @throws UnauthorizedException
         * @throws ZkStateSerializationException
         */
        @GET
        @Produces(MediaType.APPLICATION_JSON)
        public List<Bgp> list(@Context SecurityContext context)
                throws StateAccessException, ZkStateSerializationException,
                UnauthorizedException {
            if (!isPortOwner(context)) {
                throw new UnauthorizedException("Can only see your own BGP.");
            }

            BgpZkManagerProxy dao = new BgpZkManagerProxy(zooKeeper,
                    zookeeperRoot, zookeeperMgmtRoot);
            List<Bgp> bgps = null;
            try {
                bgps = dao.list(portId);
            } catch (StateAccessException e) {
                log.error("Error accessing data", e);
                throw e;
            } catch (Exception e) {
                log.error("Unhandled error", e);
                throw new UnknownRestApiException(e);
            }
            return bgps;
        }

        /**
         * Handler for create bgp.
         * 
         * @param bgp
         *            Bgp object mapped to the request input.
         * @throws StateAccessException
         * @throws UnauthorizedException
         * @throws ZkStateSerializationException
         * @returns Response object with 201 status code set if successful.
         */
        @POST
        @Consumes(MediaType.APPLICATION_JSON)
        public Response create(Bgp bgp, @Context UriInfo uriInfo,
                @Context SecurityContext context) throws StateAccessException,
                ZkStateSerializationException, UnauthorizedException {
            if (!isPortOwner(context)) {
                throw new UnauthorizedException("Can only create your own BGP.");
            }
            BgpZkManagerProxy dao = new BgpZkManagerProxy(zooKeeper,
                    zookeeperRoot, zookeeperMgmtRoot);
            bgp.setPortId(portId);

            UUID id = null;
            try {
                id = dao.create(bgp);
            } catch (StateAccessException e) {
                log.error("Error accessing data", e);
                throw e;
            } catch (Exception e) {
                log.error("Unhandled error", e);
                throw new UnknownRestApiException(e);
            }

            URI uri = uriInfo.getBaseUriBuilder().path("bgps/" + id).build();
            return Response.created(uri).build();
        }
    }

}
