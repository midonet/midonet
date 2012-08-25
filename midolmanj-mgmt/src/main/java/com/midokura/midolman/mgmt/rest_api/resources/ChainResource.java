/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.rest_api.resources;

import com.google.inject.Inject;
import com.google.inject.servlet.RequestScoped;
import com.midokura.midolman.mgmt.auth.AuthAction;
import com.midokura.midolman.mgmt.auth.AuthRole;
import com.midokura.midolman.mgmt.auth.authorizer.Authorizer;
import com.midokura.midolman.mgmt.auth.authorizer.ChainAuthorizer;
import com.midokura.midolman.mgmt.data.dao.ChainDao;
import com.midokura.midolman.mgmt.data.dto.Chain;
import com.midokura.midolman.mgmt.data.dto.Chain.ChainGroupSequence;
import com.midokura.midolman.mgmt.data.dto.UriResource;
import com.midokura.midolman.mgmt.http.VendorMediaType;
import com.midokura.midolman.mgmt.jaxrs.BadRequestHttpException;
import com.midokura.midolman.mgmt.jaxrs.ForbiddenHttpException;
import com.midokura.midolman.mgmt.jaxrs.NotFoundHttpException;
import com.midokura.midolman.mgmt.jaxrs.ResourceUriBuilder;
import com.midokura.midolman.mgmt.rest_api.resources.RuleResource.ChainRuleResource;
import com.midokura.midolman.state.InvalidStateOperationException;
import com.midokura.midolman.state.NoStatePathException;
import com.midokura.midolman.state.StateAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.security.PermitAll;
import javax.annotation.security.RolesAllowed;
import javax.validation.ConstraintViolation;
import javax.validation.Validator;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Root resource class for chains.
 */
@RequestScoped
public class ChainResource {

    private final static Logger log = LoggerFactory
            .getLogger(ChainResource.class);

    private final SecurityContext context;
    private final UriInfo uriInfo;
    private final Authorizer authorizer;
    private final Validator validator;
    private final ChainDao dao;
    private final ResourceFactory factory;

    @Inject
    public ChainResource(UriInfo uriInfo, SecurityContext context,
                         ChainAuthorizer authorizer, Validator validator,
                         ChainDao dao, ResourceFactory factory) {
        this.context = context;
        this.uriInfo = uriInfo;
        this.authorizer = authorizer;
        this.validator = validator;
        this.dao = dao;
        this.factory = factory;
    }

    /**
     * Handler to deleting a chain.
     *
     * @param id
     *            Chain ID from the request.
     * @throws StateAccessException
     *             Data access error.
     */
    @DELETE
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Path("{id}")
    public void delete(@PathParam("id") UUID id)
            throws StateAccessException, InvalidStateOperationException {

        if (!authorizer.authorize(context, AuthAction.WRITE, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to delete this chain.");
        }

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
     * @throws StateAccessException
     *             Data access error.
     * @return A Chain object.
     */
    @GET
    @PermitAll
    @Path("{id}")
    @Produces({ VendorMediaType.APPLICATION_CHAIN_JSON,
            MediaType.APPLICATION_JSON })
    public Chain get(@PathParam("id") UUID id)
            throws StateAccessException {

        if (!authorizer.authorize(context, AuthAction.READ, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to view this chain.");
        }

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
        return factory.getChainRuleResource(id);
    }

    /**
     * Handler for creating a tenant chain.
     *
     * @param chain
     *            Chain object.
     * @throws StateAccessException
     *             Data access error.
     * @returns Response object with 201 status code set if successful.
     */
    @POST
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Consumes({ VendorMediaType.APPLICATION_CHAIN_JSON,
            MediaType.APPLICATION_JSON })
    public Response create(Chain chain)
            throws StateAccessException, InvalidStateOperationException {

        Set<ConstraintViolation<Chain>> violations = validator.validate(
                chain, ChainGroupSequence.class);
        if (!violations.isEmpty()) {
            throw new BadRequestHttpException(violations);
        }

        if (!Authorizer.isAdminOrOwner(context, chain.getTenantId())) {
            throw new ForbiddenHttpException(
                    "Not authorized to add chain to this tenant.");
        }

        UUID id = dao.create(chain);
        return Response.created(
                ResourceUriBuilder.getChain(uriInfo.getBaseUri(), id))
                .build();
    }

    /**
     * Handler to getting a collection of chains.
     *
     * @throws StateAccessException
     *             Data access error.
     * @return A list of Chain objects.
     */
    @GET
    @PermitAll
    @Produces({ VendorMediaType.APPLICATION_CHAIN_COLLECTION_JSON,
            MediaType.APPLICATION_JSON })
    public List<Chain> list(@QueryParam("tenant_id") String tenantId,
                            @QueryParam("name") String name)
            throws StateAccessException {

        if (tenantId == null) {
            throw new BadRequestHttpException(
                    "Currently tenant_id is required for search.");
        }

        // Tenant ID query string is a special parameter that is used to check
        // authorization.
        if (!Authorizer.isAdmin(context) && (tenantId == null ||
                !Authorizer.isOwner(context, tenantId))) {
            throw new ForbiddenHttpException(
                    "Not authorized to view chains of this request.");
        }

        List<Chain> chains = null;
        if(name == null) {
            chains = dao.findByTenant(tenantId);
        } else {
            Chain chain = dao.findByName(tenantId, name);
            if(chain != null) {
                chains = new ArrayList<Chain>();
                chains.add(chain);
            }
        }

        if (chains != null) {
            for (UriResource resource : chains) {
                resource.setBaseUri(uriInfo.getBaseUri());
            }
        }
        return chains;
    }

}
