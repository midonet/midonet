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
import com.midokura.midolman.mgmt.data.dto.Port;

/**
 * Root resource class for ports.
 *
 * @version        1.6 08 Sept 2011
 * @author         Ryu Ishimoto
 */
@Path("/ports")
public class PortResource extends RestResource {
    /*
     * Implements REST API endpoints for ports.
     */

    private final static Logger log = LoggerFactory.getLogger(
            PortResource.class);
    
    /**
     * Get the port with the given ID.
     * @param id  Port UUID.
     * @return  Port object.
     * @throws Exception 
     */
    @GET
    @Path("{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Port get(@PathParam("id") UUID id) {
        // Get a port for the given ID.
        PortDataAccessor dao = new PortDataAccessor(zookeeperConn);
        Port port = null;
        try {
            port = dao.get(id);
        } catch (Exception ex) {
            log.error("Error getting port", ex);
            throw new WebApplicationException(
                    Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .type(MediaType.APPLICATION_JSON).build());
        }
        return port;
    }
    
    @PUT
    @Path("{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response update(@PathParam("id") UUID id, Port port){
        PortDataAccessor dao = new PortDataAccessor(zookeeperConn);
        
        try {
            dao.update(id, port);
        } catch (Exception ex) {
            log.error("Error updating port", ex);
            throw new WebApplicationException(
                    Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .type(MediaType.APPLICATION_JSON).build());
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
            throw new WebApplicationException(
                    Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .type(MediaType.APPLICATION_JSON).build());
        }
    }
    
    public static class DevicePortResource extends RestResource {
        
        protected UUID deviceId = null;

        /**
         * constructor.
         * 
         * @param   zkConn  Zookeeper connection string.
         * @param   deviceId  UUID of a device.
         */
        public DevicePortResource(String zkConn, UUID deviceId) {
            this.zookeeperConn = zkConn;
            this.deviceId = deviceId;        
        }   
        
        /**
         * Handler for create port API call.
         * 
         * @param   port  Device object mapped to the request input.
         * @throws Exception 
         * @returns Response object with 201 status code set if successful.
         */
        @POST
        @Consumes(MediaType.APPLICATION_JSON)
        public Response create(Port port, @Context UriInfo uriInfo) 
                throws Exception {
            // Add a new port entry into zookeeper.
            port.setId(UUID.randomUUID());
            port.setDeviceId(deviceId);
            PortDataAccessor dao = new PortDataAccessor(zookeeperConn);

            try {
                dao.create(port);
            } catch (Exception ex) {
                log.error("Error creating ports", ex);
                throw new WebApplicationException(
                        Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                        .type(MediaType.APPLICATION_JSON).build());
            }

            URI uri = uriInfo.getBaseUriBuilder()
                .path("ports/" + port.getId()).build();        
            return Response.created(uri).build();
        }    
    }

    /**
     * Sub-resource class for router's ports.
     */
    public static class RouterPortResource extends DevicePortResource {
        
        /**
         * Constructor.
         * 
         * @param   zkConn  Zookeeper connection string.
         * @param   routerId  UUID of a router.
         */
        public RouterPortResource(String zkConn, UUID routerId) {
            super(zkConn, routerId);
        }

        /**
         * Return a list of ports.
         * 
         * @return  A list of Port objects.
         * @throws Exception 
         */
        @GET
        @Produces(MediaType.APPLICATION_JSON)
        public Port[] list() throws Exception {
            PortDataAccessor dao = new PortDataAccessor(zookeeperConn);
            Port[] ports = null;
            try {
                ports = dao.listRouterPorts(deviceId);
            } catch (Exception ex) {
                log.error("Error listing ports", ex);
                throw new WebApplicationException(
                        Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                        .type(MediaType.APPLICATION_JSON).build());           
            }
            return ports;
        }
    }
    
    /**
     * Sub-resource class for bridge ports.
     */
    public static class BridgePortResource extends DevicePortResource {
        
        /**
         * Constructor.
         * 
         * @param   zkConn  Zookeeper connection string.
         * @param   routerId  UUID of a router.
         */
        public BridgePortResource(String zkConn, UUID routerId) {
            super(zkConn, routerId);
        }

        /**
         * Return a list of ports.
         * 
         * @return  A list of Port objects.
         * @throws Exception 
         */
        @GET
        @Produces(MediaType.APPLICATION_JSON)
        public Port[] list() throws Exception {
            PortDataAccessor dao = new PortDataAccessor(zookeeperConn);
            Port[] ports = null;
            try {
                ports = dao.listBridgePorts(deviceId);
            } catch (Exception ex) {
                log.error("Error listing ports", ex);
                throw new WebApplicationException(
                        Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                        .type(MediaType.APPLICATION_JSON).build());           
            }
            return ports;
        }
    }
}
