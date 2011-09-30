/*
 * @(#)RuleResource        1.6 11/09/05
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
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.auth.AuthManager;
import com.midokura.midolman.mgmt.auth.UnauthorizedException;
import com.midokura.midolman.mgmt.data.OwnerQueryable;
import com.midokura.midolman.mgmt.data.dao.ChainZkManagerProxy;
import com.midokura.midolman.mgmt.data.dao.RuleZkManagerProxy;
import com.midokura.midolman.mgmt.data.dto.Rule;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.RuleIndexOutOfBoundsException;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkStateSerializationException;

/**
 * Root resource class for rules.
 * 
 * @version 1.6 11 Sept 2011
 * @author Ryu Ishimoto
 */
public class RuleResource extends RestResource {

    private final static Logger log = LoggerFactory
            .getLogger(RuleResource.class);

    @GET
    @Path("{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Rule get(@PathParam("id") UUID id, @Context SecurityContext context)
            throws StateAccessException, UnauthorizedException,
            ZkStateSerializationException {
        RuleZkManagerProxy dao = new RuleZkManagerProxy(zooKeeper,
                zookeeperRoot, zookeeperMgmtRoot);

        if (!AuthManager.isOwner(context, dao, id)) {
            throw new UnauthorizedException("Can only see your own rule.");
        }

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
    public void delete(@PathParam("id") UUID id,
            @Context SecurityContext context) throws StateAccessException,
            ZkStateSerializationException, UnauthorizedException {
        RuleZkManagerProxy dao = new RuleZkManagerProxy(zooKeeper,
                zookeeperRoot, zookeeperMgmtRoot);

        if (!AuthManager.isOwner(context, dao, id)) {
            throw new UnauthorizedException("Can only delete your own rule.");
        }

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
     * Sub-resource class for chain's rules.
     */
    public static class ChainRuleResource extends RestResource {

        private UUID chainId = null;

        public ChainRuleResource(Directory zkConn, String zkRootDir,
                String zkMgmtRootDir, UUID chainId) {
            this.zooKeeper = zkConn;
            this.zookeeperRoot = zkRootDir;
            this.zookeeperMgmtRoot = zkMgmtRootDir;
            this.chainId = chainId;
        }

        private boolean isChainOwner(SecurityContext context)
                throws StateAccessException, ZkStateSerializationException {
            OwnerQueryable q = new ChainZkManagerProxy(zooKeeper,
                    zookeeperRoot, zookeeperMgmtRoot);
            return AuthManager.isOwner(context, q, chainId);
        }

        @GET
        @Produces(MediaType.APPLICATION_JSON)
        public List<Rule> list(@Context SecurityContext context)
                throws StateAccessException, ZkStateSerializationException,
                UnauthorizedException {
            RuleZkManagerProxy dao = new RuleZkManagerProxy(zooKeeper,
                    zookeeperRoot, zookeeperMgmtRoot);
            if (!isChainOwner(context)) {
                throw new UnauthorizedException("Can only see your own rule.");
            }

            try {
                return dao.list(chainId);
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
        public Response create(Rule rule, @Context UriInfo uriInfo,
                @Context SecurityContext context) throws StateAccessException,
                RuleIndexOutOfBoundsException, UnauthorizedException,
                ZkStateSerializationException {
            if (!isChainOwner(context)) {
                throw new UnauthorizedException(
                        "Can only create your own rule.");
            }

            RuleZkManagerProxy dao = new RuleZkManagerProxy(zooKeeper,
                    zookeeperRoot, zookeeperMgmtRoot);
            rule.setChainId(chainId);
            UUID id = null;
            try {
                id = dao.create(rule);
            } catch (RuleIndexOutOfBoundsException e) {
                log.error("Invalid rule position.", e);
                throw e;
            } catch (StateAccessException e) {
                log.error("Error accessing data", e);
                throw e;
            } catch (Exception e) {
                log.error("Unhandled error", e);
                throw new UnknownRestApiException(e);
            }

            URI uri = uriInfo.getBaseUriBuilder().path("rules/" + id).build();
            return Response.created(uri).build();
        }
    }
}
