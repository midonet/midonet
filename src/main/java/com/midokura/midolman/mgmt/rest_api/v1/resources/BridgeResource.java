/*
 * @(#)BridgeResource        1.6 11/09/05
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.rest_api.v1.resources;

import java.net.URI;
import java.util.UUID;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.data.dao.BridgeDataAccessor;
import com.midokura.midolman.mgmt.data.dto.Bridge;
import com.midokura.midolman.mgmt.rest_api.v1.resources.PortResource.BridgePortResource;

/**
 * Root resource class for Virtual bridges.
 * 
 * @version 1.6 11 Sept 2011
 * @author Ryu Ishimoto
 */
@Path("/bridges")
public class BridgeResource extends RestResource {
    /*
     * Implements REST API end points for bridges.
     */

    private final static Logger log = LoggerFactory
            .getLogger(BridgeResource.class);

    /**
     * Port resource locator for bridges
     */
    @Path("/{id}/ports")
    public BridgePortResource getPortResource(@PathParam("id") UUID id) {
        return new BridgePortResource(zookeeperConn, id);
    }

    /**
     * Get the bridge with the given ID.
     * 
     * @param id
     *            Bridge UUID.
     * @return Bridge object.
     */
    @GET
    @Path("{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Bridge get(@PathParam("id") UUID id) {
        // Get a bridge for the given ID.
        BridgeDataAccessor dao = new BridgeDataAccessor(zookeeperConn);
        try {
            return dao.get(id);
        } catch (Exception ex) {
            log.error("Error getting bridge", ex);
            throw new WebApplicationException(ex, Response.status(
                    Response.Status.INTERNAL_SERVER_ERROR).type(
                    MediaType.APPLICATION_JSON).build());
        }
    }

    @PUT
    @Path("{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response update(@PathParam("id") UUID id, Bridge bridge) {
        BridgeDataAccessor dao = new BridgeDataAccessor(zookeeperConn);
        try {
            dao.update(id, bridge);
        } catch (Exception ex) {
            log.error("Error updating bridge", ex);
            throw new WebApplicationException(ex, Response.status(
                    Response.Status.INTERNAL_SERVER_ERROR).type(
                    MediaType.APPLICATION_JSON).build());
        }
        return Response.ok().build();
    }

    @DELETE
    @Path("{id}")
    public void delete(@PathParam("id") UUID id) {
        BridgeDataAccessor dao = new BridgeDataAccessor(zookeeperConn);
        try {
            dao.delete(id);
        } catch (Exception ex) {
            log.error("Error deleting bridge", ex);
            throw new WebApplicationException(ex, Response.status(
                    Response.Status.INTERNAL_SERVER_ERROR).type(
                    MediaType.APPLICATION_JSON).build());
        }
    }

    /**
     * Sub-resource class for tenant's virtual switch.
     */
    public static class TenantBridgeResource extends RestResource {

        private UUID tenantId = null;

        /**
         * Constructor.
         * 
         * @param zkConn
         *            ZooKeeper connection string.
         * @param tenantId
         *            UUID of a tenant.
         */
        public TenantBridgeResource(String zkConn, UUID tenantId) {
            this.zookeeperConn = zkConn;
            this.tenantId = tenantId;
        }

        /**
         * Index of bridges belonging to the tenant.
         * 
         * @return A list of bridges.
         */
        @GET
        @Produces(MediaType.APPLICATION_JSON)
        public Bridge[] list() {
            BridgeDataAccessor dao = new BridgeDataAccessor(zookeeperConn);
            try {
                return dao.list(tenantId);
            } catch (Exception ex) {
                log.error("Error getting bridges", ex);
                throw new WebApplicationException(ex, Response.status(
                        Response.Status.INTERNAL_SERVER_ERROR).type(
                        MediaType.APPLICATION_JSON).build());
            }
        }

        /**
         * Handler for create bridge.
         * 
         * @param bridge
         *            Bridge object mapped to the request input.
         * @returns Response object with 201 status code set if successful.
         */
        @POST
        @Consumes(MediaType.APPLICATION_JSON)
        public Response create(Bridge bridge, @Context UriInfo uriInfo) {
            bridge.setTenantId(tenantId);
            BridgeDataAccessor dao = new BridgeDataAccessor(zookeeperConn);
            UUID id = null;
            try {
                id = dao.create(bridge);
            } catch (Exception ex) {
                log.error("Error creating bridge", ex);
                throw new WebApplicationException(ex, Response.status(
                        Response.Status.INTERNAL_SERVER_ERROR).type(
                        MediaType.APPLICATION_JSON).build());
            }

            URI uri = uriInfo.getBaseUriBuilder().path("bridges/" + id).build();
            return Response.created(uri).build();
        }
    }

}
