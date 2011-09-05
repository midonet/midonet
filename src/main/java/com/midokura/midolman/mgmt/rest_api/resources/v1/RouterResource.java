/*
 * @(#)RouterResource.java        1.6 11/09/05
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.rest_api.resources.v1;

import java.io.InputStream;
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

import com.midokura.midolman.mgmt.data.Router;
import com.midokura.midolman.mgmt.data.RouterDataAccessor;
 
/**
 * Resource class for Virtual Router.
 *
 * @version        1.6 05 Sept 2011
 * @author         Ryu Ishimoto
 */
@Path("/v1/routers")
public class RouterResource extends RestResource {
    /*
     * Implements REST API endpoints for routers.
     */
		
    @GET
    @Path("{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getRouter(@PathParam("id") String id) {
       Router router = new Router();
       router.setId(UUID.randomUUID());
       router.setName("foo");
       return Response.status(200).entity(router).build();
    }

	/**
	 * Handler for create router API call.
	 * 
	 * @param   is  InputStream of the request.
	 * @returns Response object with 201 status code set if successful.
	 */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response createRouter(InputStream is) {
    	// Add a new router entry into zookeeper.
    	Router router = new Router();
    	router.setId(UUID.randomUUID());
    	RouterDataAccessor dao = new RouterDataAccessor(zookeeperConn);

    	try {
        	dao.create(router);
    	} catch (Exception ex) {
    		// TODO: LOG
    		System.err.println("Exception = " + ex.getMessage());
    		throw new WebApplicationException(
    				Response.status(Response.Status.INTERNAL_SERVER_ERROR)
    				.type(MediaType.APPLICATION_JSON).build());
    	}
    	
    	return Response.created(URI.create("/v1/routers/"
    			+ router.getId())).build();
    }
}
