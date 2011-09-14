/*
 * @(#)PortResource        1.6 11/09/05
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

import com.midokura.midolman.mgmt.data.dao.PortDataAccessor;
import com.midokura.midolman.mgmt.data.dao.UnsupportedOperationException;
import com.midokura.midolman.mgmt.data.dto.Port;
import com.midokura.midolman.mgmt.data.dto.RouterPort;

/**
 * Root resource class for ports.
 * 
 * @version 1.6 08 Sept 2011
 * @author Ryu Ishimoto
 */
@Path("/ports")
public class PortResource extends RestResource {
    /*
     * Implements REST API endpoints for ports.
     */

    private final static Logger log = LoggerFactory
            .getLogger(PortResource.class);

    /**
     * Get the port with the given ID.
     * 
     * @param id
     *            Port UUID.
     * @return Port object.
     * @throws Exception
     * @throws Exception
     */
    @GET
    @Path("{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Port get(@PathParam("id") UUID id) {
        // Get a port for the given ID.
        PortDataAccessor dao = new PortDataAccessor(zookeeperConn);
        try {
            return dao.get(id);
        } catch (Exception ex) {
            log.error("Error getting port", ex);
            throw new WebApplicationException(ex, Response.status(
                    Response.Status.INTERNAL_SERVER_ERROR).type(
                    MediaType.APPLICATION_JSON).build());
        }
    }

    @PUT
    @Path("{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response update(@PathParam("id") UUID id, RouterPort port) {
        PortDataAccessor dao = new PortDataAccessor(zookeeperConn);

        try {
            dao.update(id, port);
        } catch (UnsupportedOperationException ex) {
            log.error("Cannot update this port", ex);
            throw new WebApplicationException(ex, Response.status(
                    Response.Status.INTERNAL_SERVER_ERROR).type(
                    MediaType.APPLICATION_JSON).build());
        } catch (Exception ex) {
            log.error("Error updating port", ex);
            throw new WebApplicationException(ex, Response.status(
                    Response.Status.INTERNAL_SERVER_ERROR).type(
                    MediaType.APPLICATION_JSON).build());
        }

        return Response.ok().build();
    }

    @DELETE
    @Path("{id}")
    public void delete(@PathParam("id") UUID id) {
        PortDataAccessor dao = new PortDataAccessor(zookeeperConn);
        try {
            dao.delete(id);
        } catch (Exception ex) {
            log.error("Error deleting port", ex);
            throw new WebApplicationException(ex, Response.status(
                    Response.Status.INTERNAL_SERVER_ERROR).type(
                    MediaType.APPLICATION_JSON).build());
        }
    }

    public static class BridgePortResource extends RestResource {

        private UUID bridgeId = null;

        public BridgePortResource(String zkConn, UUID bridgeId) {
            this.zookeeperConn = zkConn;
            this.bridgeId = bridgeId;
        }

        @POST
        @Consumes(MediaType.APPLICATION_JSON)
        public Response create(Port port, @Context UriInfo uriInfo)
                throws Exception {
            port.setDeviceId(bridgeId);
            PortDataAccessor dao = new PortDataAccessor(zookeeperConn);

            UUID id = null;
            try {
                id = dao.createBridgePort(port);
            } catch (Exception ex) {
                log.error("Error creating bridge ports", ex);
                throw new WebApplicationException(ex, Response.status(
                        Response.Status.INTERNAL_SERVER_ERROR).type(
                        MediaType.APPLICATION_JSON).build());
            }

            URI uri = uriInfo.getBaseUriBuilder().path("ports/" + id).build();
            return Response.created(uri).build();
        }

        @GET
        @Produces(MediaType.APPLICATION_JSON)
        public Port[] list() {
            PortDataAccessor dao = new PortDataAccessor(zookeeperConn);
            try {
                return dao.listBridgePorts(bridgeId);
            } catch (Exception ex) {
                log.error("Error listing bridge ports", ex);
                throw new WebApplicationException(ex, Response.status(
                        Response.Status.INTERNAL_SERVER_ERROR).type(
                        MediaType.APPLICATION_JSON).build());
            }
        }
    }

    /**
     * Sub-resource class for router's ports.
     */
    public static class RouterPortResource extends RestResource {

        private UUID routerId = null;

        public RouterPortResource(String zkConn, UUID routerId) {
            this.zookeeperConn = zkConn;
            this.routerId = routerId;
        }

        @POST
        @Consumes(MediaType.APPLICATION_JSON)
        public Response create(RouterPort port, @Context UriInfo uriInfo)
                throws Exception {
            port.setDeviceId(routerId);
            PortDataAccessor dao = new PortDataAccessor(zookeeperConn);

            UUID id = null;
            try {
                id = dao.createRouterPort(port);
            } catch (Exception ex) {
                log.error("Error creating router ports", ex);
                throw new WebApplicationException(ex, Response.status(
                        Response.Status.INTERNAL_SERVER_ERROR).type(
                        MediaType.APPLICATION_JSON).build());
            }

            URI uri = uriInfo.getBaseUriBuilder().path("ports/" + id).build();
            return Response.created(uri).build();
        }

        @GET
        @Produces(MediaType.APPLICATION_JSON)
        public Port[] list() {
            PortDataAccessor dao = new PortDataAccessor(zookeeperConn);
            try {
                return dao.listRouterPorts(routerId);
            } catch (Exception ex) {
                log.error("Error listing router ports", ex);
                throw new WebApplicationException(ex, Response.status(
                        Response.Status.INTERNAL_SERVER_ERROR).type(
                        MediaType.APPLICATION_JSON).build());
            }
        }
    }
}
