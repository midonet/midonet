/*
 * @(#)PortResource        1.6 11/09/05
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.rest_api.v1.resources;

import java.net.URI;
import java.util.UUID;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

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

    /**
     * Get the port with the given ID.
     * @param id  Port UUID.
     * @return  Port object.
     */
    @GET
    @Path("{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Port get(@PathParam("id") UUID id) {
        // Get a port for the given ID.
        PortDataAccessor dao = new PortDataAccessor(zookeeperConn);
        Port port = null;
        try {
            port = dao.find(id);
        } catch (Exception ex) {
            // TODO: LOG
            System.err.println("Exception = " + ex.getMessage());
            throw new WebApplicationException(
                    Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .type(MediaType.APPLICATION_JSON).build());
        }
        return port;
    }
    
    /**
     * Sub-resource class for tenant's port.
     */
    public static class RouterPortResource extends RestResource {
        
        private UUID routerId = null;
        
        /**
         * Default constructor.
         * 
         * @param   zkConn  Zookeeper connection string.
         * @param   tenantId  UUID of a tenant.
         */
        public RouterPortResource(String zkConn, UUID routerId) {
            this.zookeeperConn = zkConn;
            this.routerId = routerId;        
        }
        
        /**
         * Handler for create port API call.
         * 
         * @param   port  Router object mapped to the request input.
         * @throws Exception 
         * @returns Response object with 201 status code set if successful.
         */
        @POST
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.APPLICATION_JSON)
        public Response create(Port port) throws Exception {
            // Add a new router entry into zookeeper.
            port.setId(UUID.randomUUID());
            port.setDeviceId(routerId);
            PortDataAccessor dao = new PortDataAccessor(zookeeperConn);

            try {
                dao.create(port);
            } catch (Exception ex) {
                // TODO: LOG
                System.err.println("Exception = " + ex.getMessage());
                throw new WebApplicationException(
                        Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                        .type(MediaType.APPLICATION_JSON).build());
            }
            return Response.created(URI.create("/" + port.getId())).build();
        }        
        
        
        
    }
}
