/*
 * @(#)ChainResource        1.6 11/09/05
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
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.data.dao.ChainZkManagerProxy;
import com.midokura.midolman.mgmt.data.dto.Chain;
import com.midokura.midolman.mgmt.rest_api.v1.resources.RuleResource.ChainRuleResource;
import com.midokura.midolman.state.Directory;
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
        return new ChainRuleResource(zooKeeper, id);
    }

    @GET
    @Path("{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Chain get(@PathParam("id") UUID id) throws StateAccessException {
        ChainZkManagerProxy dao = new ChainZkManagerProxy(zooKeeper,
                zookeeperRoot, zookeeperMgmtRoot);
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
        ChainZkManagerProxy dao = new ChainZkManagerProxy(zooKeeper,
                zookeeperRoot, zookeeperMgmtRoot);
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
     * Sub-resource class for router's chain tables.
     */
    public static class RouterTableResource extends RestResource {

        private UUID routerId = null;

        public RouterTableResource(Directory zkConn, UUID routerId) {
            this.zooKeeper = zkConn;
            this.routerId = routerId;
        }

        /**
         * Chain resource locator for chain table
         */
        @Path("/{name}/chains")
        public RouterTableChainResource getChainTableResource(
                @PathParam("name") String name) {
            return new RouterTableChainResource(zooKeeper, routerId, name);
        }
    }

    /**
     * Sub-resource class for router's table chains.
     */
    public static class RouterTableChainResource extends RestResource {

        private UUID routerId = null;
        private String table = null;

        public RouterTableChainResource(Directory zkConn, UUID routerId,
                String table) {
            this.zooKeeper = zkConn;
            this.routerId = routerId;
            this.table = table;
        }

        @GET
        @Produces(MediaType.APPLICATION_JSON)
        public List<Chain> list() throws StateAccessException {
            ChainZkManagerProxy dao = new ChainZkManagerProxy(zooKeeper,
                    zookeeperRoot, zookeeperMgmtRoot);
            try {
                return dao.listTableChains(routerId, table);
            } catch (StateAccessException e) {
                log.error("Error accessing data", e);
                throw e;
            } catch (Exception e) {
                log.error("Unhandled error", e);
                throw new UnknownRestApiException(e);
            }
        }

        @GET
        @Path("{name}")
        @Produces(MediaType.APPLICATION_JSON)
        public Chain get(@PathParam("name") String name)
                throws StateAccessException {
            ChainZkManagerProxy dao = new ChainZkManagerProxy(zooKeeper,
                    zookeeperRoot, zookeeperMgmtRoot);
            try {
                return dao.get(routerId, table, name);
            } catch (StateAccessException e) {
                log.error("Error accessing data", e);
                throw e;
            } catch (Exception e) {
                log.error("Unhandled error", e);
                throw new UnknownRestApiException(e);
            }
        }
    }

    /**
     * Sub-resource class for router's table chains.
     */
    public static class RouterChainResource extends RestResource {

        private UUID routerId = null;

        public RouterChainResource(Directory zkConn, UUID routerId) {
            this.zooKeeper = zkConn;
            this.routerId = routerId;
        }

        @POST
        @Consumes(MediaType.APPLICATION_JSON)
        public Response create(Chain chain, @Context UriInfo uriInfo)
                throws StateAccessException {
            chain.setRouterId(routerId);
            ChainZkManagerProxy dao = new ChainZkManagerProxy(zooKeeper,
                    zookeeperRoot, zookeeperMgmtRoot);
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

        @GET
        @Produces(MediaType.APPLICATION_JSON)
        public List<Chain> list() throws StateAccessException {
            ChainZkManagerProxy dao = new ChainZkManagerProxy(zooKeeper,
                    zookeeperRoot, zookeeperMgmtRoot);
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
    }
}
