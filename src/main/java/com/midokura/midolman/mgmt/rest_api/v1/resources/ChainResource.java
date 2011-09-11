/*
 * @(#)ChainResource        1.6 11/09/05
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

import com.midokura.midolman.mgmt.data.dao.ChainDataAccessor;
import com.midokura.midolman.mgmt.data.dto.Chain;

/**
 * Root resource class for chains.
 *
 * @version        1.6 11 Sept 2011
 * @author         Ryu Ishimoto
 */
@Path("/chains")
public class ChainResource extends RestResource {

    private final static Logger log = LoggerFactory.getLogger(
            ChainResource.class);
    
    @GET
    @Path("{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Chain get(@PathParam("id") UUID id) {
        ChainDataAccessor dao = new ChainDataAccessor(zookeeperConn);
        Chain chain = null;
        try {
            chain = dao.get(id);
        } catch (Exception ex) {
            log.error("Error getting chain", ex);
            throw new WebApplicationException(
                    Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .type(MediaType.APPLICATION_JSON).build());
        }
        return chain;
    }

    @PUT
    @Path("{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response update(@PathParam("id") UUID id, Chain chain){
        ChainDataAccessor dao = new ChainDataAccessor(zookeeperConn);
        try {
            dao.update(id, chain);
        } catch (Exception ex) {
            log.error("Error updating chain", ex);
            throw new WebApplicationException(
                    Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .type(MediaType.APPLICATION_JSON).build());
        }
        
        return Response.ok().build();
    }
    
    @DELETE
    @Path("{id}")
    public void delete(@PathParam("id") UUID id) {
        ChainDataAccessor dao = new ChainDataAccessor(zookeeperConn);
        try {
            dao.delete(id);
        } catch (Exception ex) {
            log.error("Error deleting chain", ex);
            throw new WebApplicationException(
                    Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .type(MediaType.APPLICATION_JSON).build());
        }
    }
    
    /**
     * Sub-resource class for router's chains.
     */
    public static class RouterChainResource extends RestResource {
        
        private UUID routerId = null;
        
        public RouterChainResource(String zkConn, UUID routerId) {
            this.zookeeperConn = zkConn;
            this.routerId = routerId;        
        }
        
        @GET
        @Produces(MediaType.APPLICATION_JSON)
        public Chain[] list() {
            ChainDataAccessor dao = new ChainDataAccessor(zookeeperConn);
            Chain[] chains = null;
            try {
                chains = dao.list(routerId);
            } catch (Exception ex) {
                log.error("Error getting chains", ex);
                throw new WebApplicationException(
                        Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                        .type(MediaType.APPLICATION_JSON).build());           
            }
            return chains;
        }
        
        @POST
        @Consumes(MediaType.APPLICATION_JSON)
        public Response create(Chain chain, @Context UriInfo uriInfo) {
            chain.setId(UUID.randomUUID());
            chain.setRouterId(routerId);
            ChainDataAccessor dao = new ChainDataAccessor(zookeeperConn);
            try {
                dao.create(chain);
            } catch (Exception ex) {
                log.error("Error creating chain", ex);
                throw new WebApplicationException(
                        Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                        .type(MediaType.APPLICATION_JSON).build());
            }

            URI uri = uriInfo.getBaseUriBuilder()
                .path("chains/" + chain.getId()).build();            
            return Response.created(uri).build();
        }        
    }    
}
