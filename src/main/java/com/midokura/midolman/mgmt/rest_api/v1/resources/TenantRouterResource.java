/*
 * @(#)RouterResource.java        1.6 11/09/05
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.rest_api.v1.resources;

import java.net.URI;
import java.util.UUID;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import com.midokura.midolman.mgmt.data.Router;
import com.midokura.midolman.mgmt.data.RouterDataAccessor;

/**
 * Sub-resource class for tenant's virtual router.
 *
 * @version        1.6 05 Sept 2011
 * @author         Ryu Ishimoto
 */
public class TenantRouterResource extends RestResource {
    // Implement router sub-resource for tenants.
    
    private UUID tenantId = null;
    
    /**
     * Default constructor.
     * 
     * @param   zkConn  Zookeeper connection string.
     * @param   tenantId  UUID of a tenant.
     */
    public TenantRouterResource(String zkConn, UUID tenantId) {
        this.zookeeperConn = zkConn;
        this.tenantId = tenantId;        
    }
    
    /**
     * Return a list of routers.
     * 
     * @return  A list of Router objects.
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Router[] list() {
        RouterDataAccessor dao = new RouterDataAccessor(zookeeperConn);
        Router[] routers = null;
        try {
            routers = dao.list(tenantId);
        } catch (Exception ex) {
            // TODO: LOG
            System.err.println("Exception = " + ex.getMessage());
            throw new WebApplicationException(
                    Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .type(MediaType.APPLICATION_JSON).build());           
        }
        return routers;
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
    public Response create(Router router) {
        // Add a new router entry into zookeeper.
        if(router.getId() == null) {
            router.setId(UUID.randomUUID());
        }
        router.setTenantId(tenantId);
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
        
        return Response.created(URI.create("/" + router.getId())).build();
    }        
}