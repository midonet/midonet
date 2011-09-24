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
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.data.dao.ChainDataAccessor;
import com.midokura.midolman.mgmt.data.dto.Chain;
import com.midokura.midolman.mgmt.rest_api.v1.resources.RuleResource.ChainRuleResource;
import com.midokura.midolman.state.StateAccessException;

/**
 * Root resource class for chains.
 * 
 * @version 1.6 11 Sept 2011
 * @author Ryu Ishimoto
 */
@Path("/chains")
public class ChainResource extends RestResource {

    private final static Logger log = LoggerFactory
            .getLogger(ChainResource.class);

    /**
     * Rule resource locator for chains
     */
    @Path("/{id}/rules")
    public ChainRuleResource getRuleResource(@PathParam("id") UUID id) {
        return new ChainRuleResource(zookeeperConn, id);
    }

    @GET
    @Path("{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Chain get(@PathParam("id") UUID id) throws StateAccessException {
        ChainDataAccessor dao = new ChainDataAccessor(zookeeperConn,
                zookeeperTimeout, zookeeperRoot, zookeeperMgmtRoot);
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
    public void delete(@PathParam("id") UUID id) throws StateAccessException {
        ChainDataAccessor dao = new ChainDataAccessor(zookeeperConn,
                zookeeperTimeout, zookeeperRoot, zookeeperMgmtRoot);
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
        public Chain[] list() throws StateAccessException {
            ChainDataAccessor dao = new ChainDataAccessor(zookeeperConn,
                    zookeeperTimeout, zookeeperRoot, zookeeperMgmtRoot);
            try {
                return dao.list(routerId);
            } catch (StateAccessException e) {
                log.error("Error accessing data", e);
                throw e;
            } catch (Exception e) {
                log.error("Unhandled error", e);
                throw new UnknownRestApiException(e);
            }
        }

        @POST
        @Consumes(MediaType.APPLICATION_JSON)
        public Response create(Chain chain, @Context UriInfo uriInfo)
                throws StateAccessException {
            chain.setId(UUID.randomUUID());
            chain.setRouterId(routerId);
            ChainDataAccessor dao = new ChainDataAccessor(zookeeperConn,
                    zookeeperTimeout, zookeeperRoot, zookeeperMgmtRoot);
            UUID id = null;
            try {
                id = dao.create(chain);
            } catch (StateAccessException e) {
                log.error("Error accessing data", e);
                throw e;
            } catch (Exception e) {
                log.error("Unhandled error", e);
                throw new UnknownRestApiException(e);
            }

            URI uri = uriInfo.getBaseUriBuilder().path("chains/" + id).build();
            return Response.created(uri).build();
        }
    }
}
