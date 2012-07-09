/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.rest_api.resources;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.annotation.security.PermitAll;
import javax.annotation.security.RolesAllowed;
import javax.validation.ConstraintViolation;
import javax.validation.Validator;
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

import com.midokura.midolman.mgmt.auth.AuthAction;
import com.midokura.midolman.mgmt.auth.AuthRole;
import com.midokura.midolman.mgmt.auth.Authorizer;
import com.midokura.midolman.mgmt.data.DaoFactory;
import com.midokura.midolman.mgmt.data.dao.ChainDao;
import com.midokura.midolman.mgmt.data.dto.Chain;
import com.midokura.midolman.mgmt.data.dto.Chain.ChainGroupSequence;
import com.midokura.midolman.mgmt.data.dto.UriResource;
import com.midokura.midolman.mgmt.jaxrs.BadRequestHttpException;
import com.midokura.midolman.mgmt.jaxrs.ForbiddenHttpException;
import com.midokura.midolman.mgmt.jaxrs.NotFoundHttpException;
import com.midokura.midolman.mgmt.rest_api.core.ResourceUriBuilder;
import com.midokura.midolman.mgmt.rest_api.core.VendorMediaType;
import com.midokura.midolman.mgmt.rest_api.resources.RuleResource.ChainRuleResource;
import com.midokura.midolman.state.NoStatePathException;
import com.midokura.midolman.state.StateAccessException;

/**
 * Root resource class for chains.
 */
public class ChainResource {

    private final static Logger log = LoggerFactory
            .getLogger(ChainResource.class);

    /**
     * Handler to deleting a chain.
     *
     * @param id
     *            Chain ID from the request.
     * @param context
     *            Object that holds the security data.
     * @param daoFactory
     *            Data access factory object.
     * @param authorizer
     *            Authorizer object.
     * @throws StateAccessException
     *             Data access error.
     */
    @DELETE
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Path("{id}")
    public void delete(@PathParam("id") UUID id,
            @Context SecurityContext context, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException {

        if (!authorizer.chainAuthorized(context, AuthAction.WRITE, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to delete this chain.");
        }

        ChainDao dao = daoFactory.getChainDao();
        try {
            dao.delete(id);
        } catch (NoStatePathException e) {
            // Deleting a non-existing record is OK.
            log.warn("The resource does not exist", e);
        }
    }

    /**
     * Handler to getting a chain.
     *
     * @param id
     *            Chain ID from the request.
     * @param context
     *            Object that holds the security data.
     * @param uriInfo
     *            Object that holds the request URI data.
     * @param daoFactory
     *            Data access factory object.
     * @param authorizer
     *            Authorizer object.
     * @throws StateAccessException
     *             Data access error.
     * @return A Chain object.
     */
    @GET
    @PermitAll
    @Path("{id}")
    @Produces({ VendorMediaType.APPLICATION_CHAIN_JSON,
            MediaType.APPLICATION_JSON })
    public Chain get(@PathParam("id") UUID id,
            @Context SecurityContext context, @Context UriInfo uriInfo,
            @Context DaoFactory daoFactory, @Context Authorizer authorizer)
            throws StateAccessException {

        if (!authorizer.chainAuthorized(context, AuthAction.READ, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to view this chain.");
        }

        ChainDao dao = daoFactory.getChainDao();
        Chain chain = dao.get(id);
        if (chain == null) {
            throw new NotFoundHttpException(
                    "The requested resource was not found.");
        }
        chain.setBaseUri(uriInfo.getBaseUri());

        return chain;
    }

    /**
     * Rule resource locator for chains.
     *
     * @param id
     *            Chain ID from the request.
     * @returns ChainRuleResource object to handle sub-resource requests.
     */
    @Path("/{id}" + ResourceUriBuilder.RULES)
    public ChainRuleResource getRuleResource(@PathParam("id") UUID id) {
        return new ChainRuleResource(id);
    }

    /**
     * Sub-resource class for tenant's chains.
     */
    public static class TenantChainResource {

        private final String tenantId;

        /**
         * Constructor
         *
         * @param tenantId
         *            ID of a tenant.
         */
        public TenantChainResource(String tenantId) {
            this.tenantId = tenantId;
        }

        /**
         * Handler for creating a tenant chain.
         *
         * @param chain
         *            Chain object.
         * @param uriInfo
         *            Object that holds the request URI data.
         * @param context
         *            Object that holds the security data.
         * @param daoFactory
         *            Data access factory object.
         * @param authorizer
         *            Authorizer object.
         * @throws StateAccessException
         *             Data access error.
         * @returns Response object with 201 status code set if successful.
         */
        @POST
        @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
        @Consumes({ VendorMediaType.APPLICATION_CHAIN_JSON,
                MediaType.APPLICATION_JSON })
        public Response create(Chain chain, @Context UriInfo uriInfo,
                @Context SecurityContext context,
                @Context DaoFactory daoFactory, @Context Authorizer authorizer,
                @Context Validator validator)
                throws StateAccessException {

            chain.setTenantId(tenantId);

            Set<ConstraintViolation<Chain>> violations = validator.validate(
                    chain, ChainGroupSequence.class);
            if (!violations.isEmpty()) {
                throw new BadRequestHttpException(violations);
            }

            if (!authorizer.tenantAuthorized(context, AuthAction.WRITE,
                    tenantId)) {
                throw new ForbiddenHttpException(
                        "Not authorized to add chain to this tenant.");
            }

            ChainDao dao = daoFactory.getChainDao();
            UUID id = dao.create(chain);
            return Response.created(
                    ResourceUriBuilder.getChain(uriInfo.getBaseUri(), id))
                    .build();
        }

        /**
         * Handler to getting a collection of chains.
         *
         * @param context
         *            Object that holds the security data.
         * @param uriInfo
         *            Object that holds the request URI data.
         * @param daoFactory
         *            Data access factory object.
         * @param authorizer
         *            Authorizer object.
         * @throws StateAccessException
         *             Data access error.
         * @return A list of Chain objects.
         */
        @GET
        @PermitAll
        @Produces({ VendorMediaType.APPLICATION_CHAIN_COLLECTION_JSON,
                MediaType.APPLICATION_JSON })
        public List<Chain> list(@Context SecurityContext context,
                @Context UriInfo uriInfo, @Context DaoFactory daoFactory,
                @Context Authorizer authorizer) throws StateAccessException {

            if (!authorizer
                    .tenantAuthorized(context, AuthAction.READ, tenantId)) {
                throw new ForbiddenHttpException(
                        "Not authorized to view these chains.");
            }

            ChainDao dao = daoFactory.getChainDao();
            List<Chain> chains = dao.list(tenantId);
            if (chains != null) {
                for (UriResource resource : chains) {
                    resource.setBaseUri(uriInfo.getBaseUri());
                }
            }
            return chains;
        }

        /**
         * Handler to getting a chain.
         *
         * @param name
         *            Chain name from the request.
         * @param context
         *            Object that holds the security data.
         * @param uriInfo
         *            Object that holds the request URI data.
         * @param daoFactory
         *            Data access factory object.
         * @param authorizer
         *            Authorizer object.
         * @throws StateAccessException
         *             Data access error.
         * @return A Chain object.
         */
        @GET
        @PermitAll
        @Path("{name}")
        @Produces({ VendorMediaType.APPLICATION_CHAIN_JSON,
                MediaType.APPLICATION_JSON })
        public Chain get(@PathParam("name") String name,
                @Context SecurityContext context, @Context UriInfo uriInfo,
                @Context DaoFactory daoFactory, @Context Authorizer authorizer)
                throws StateAccessException {

            if (!authorizer
                    .tenantAuthorized(context, AuthAction.READ, tenantId)) {
                throw new ForbiddenHttpException(
                        "Not authorized to view chain of this tenant.");
            }

            ChainDao dao = daoFactory.getChainDao();
            Chain chain = dao.get(tenantId, name);
            if (chain != null) {
                chain.setBaseUri(uriInfo.getBaseUri());
            }
            return chain;
        }
    }
}
