/*
 * @(#)RouterResource.java        1.6 11/09/05
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.rest_api.v1.resources;

import java.util.UUID;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import com.midokura.midolman.mgmt.data.Router;
import com.midokura.midolman.mgmt.data.RouterDataAccessor;
 
/**
 * Root resource class for Virtual Router.
 *
 * @version        1.6 05 Sept 2011
 * @author         Ryu Ishimoto
 */
@Path("/routers")
public class RouterResource extends RestResource {
    /*
     * Implements REST API endpoints for routers.
     */
    
    @GET
    @Path("{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Router get(@PathParam("id") UUID id) {
        // Get a router for the given ID.
        RouterDataAccessor dao = new RouterDataAccessor(zookeeperConn);
        Router router = null;
        try {
            router = dao.find(id);
        } catch (Exception ex) {
            // TODO: LOG
            System.err.println("Exception = " + ex.getMessage());
            throw new WebApplicationException(
                    Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .type(MediaType.APPLICATION_JSON).build());
        }
        return router;
    }
}
