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
import javax.ws.rs.PUT;
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
import com.midokura.midolman.mgmt.data.dao.RouterDao;
import com.midokura.midolman.mgmt.data.dto.Router;
import com.midokura.midolman.mgmt.data.dto.UriResource;
import com.midokura.midolman.mgmt.rest_api.core.ResourceUriBuilder;
import com.midokura.midolman.mgmt.rest_api.core.VendorMediaType;
import com.midokura.midolman.mgmt.jaxrs.BadRequestHttpException;
import com.midokura.midolman.mgmt.jaxrs.ForbiddenHttpException;
import com.midokura.midolman.mgmt.jaxrs.NotFoundHttpException;
import com.midokura.midolman.mgmt.rest_api.resources.PortResource.RouterPeerPortResource;
import com.midokura.midolman.mgmt.rest_api.resources.PortResource.RouterPortResource;
import com.midokura.midolman.mgmt.rest_api.resources.RouteResource.RouterRouteResource;
import com.midokura.midolman.state.NoStatePathException;
import com.midokura.midolman.state.StateAccessException;

/**
 * Root resource class for Virtual Router.
 */
public class RouterResource {
    /*
     * Implements REST API endpoints for routers.
     */

    private final static Logger log = LoggerFactory
            .getLogger(RouterResource.class);

    /**
     * Handler to deleting a router.
     *
     * @param id
     *            Router ID from the request.
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

        if (!authorizer.routerAuthorized(context, AuthAction.WRITE, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to delete this router.");
        }

        RouterDao dao = daoFactory.getRouterDao();
        try {
            dao.delete(id);
        } catch (NoStatePathException e) {
            // Deleting a non-existing record is OK.
            log.warn("The resource does not exist", e);
        }
    }

    /**
     * Handler to getting a router.
     *
     * @param id
     *            Router ID from the request.
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
     * @return A Router object.
     */
    @GET
    @PermitAll
    @Path("{id}")
    @Produces({ VendorMediaType.APPLICATION_ROUTER_JSON,
            MediaType.APPLICATION_JSON })
    public Router get(@PathParam("id") UUID id,
            @Context SecurityContext context, @Context UriInfo uriInfo,
            @Context DaoFactory daoFactory, @Context Authorizer authorizer)
            throws StateAccessException {

        if (!authorizer.routerAuthorized(context, AuthAction.READ, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to view this router.");
        }

        RouterDao dao = daoFactory.getRouterDao();
        Router router = dao.get(id);
        if (router == null) {
            throw new NotFoundHttpException(
                    "The requested resource was not found.");
        }
        router.setBaseUri(uriInfo.getBaseUri());

        return router;
    }

    /**
     * Port resource locator for routers.
     *
     * @param id
     *            Router ID from the request.
     * @returns RouterPortResource object to handle sub-resource requests.
     */
    @Path("/{id}" + ResourceUriBuilder.PORTS)
    public RouterPortResource getPortResource(@PathParam("id") UUID id) {
        return new RouterPortResource(id);
    }

    /**
     * Route resource locator for routers.
     *
     * @param id
     *            Router ID from the request.
     * @returns RouterRouteResource object to handle sub-resource requests.
     */
    @Path("/{id}" + ResourceUriBuilder.ROUTES)
    public RouterRouteResource getRouteResource(@PathParam("id") UUID id) {
        return new RouterRouteResource(id);
    }

    /**
     * Peer port resource locator for bridges.
     *
     * @param id
     *            Router ID from the request.
     * @returns RouterPortResource object to handle sub-resource requests.
     */
    @Path("/{id}" + ResourceUriBuilder.PEER_PORTS)
    public RouterPeerPortResource RouterPeerPortResource(
            @PathParam("id") UUID id) {
        return new RouterPeerPortResource(id);
    }

    /**
     * Handler to updating a router.
     *
     * @param id
     *            Router ID from the request.
     * @param router
     *            Router object.
     * @param context
     *            Object that holds the security data.
     * @param daoFactory
     *            Data access factory object.
     * @param authorizer
     *            Authorizer object.
     * @throws StateAccessException
     *             Data access error.
     */
    @PUT
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Path("{id}")
    @Consumes({ VendorMediaType.APPLICATION_ROUTER_JSON,
            MediaType.APPLICATION_JSON })
    public void update(@PathParam("id") UUID id, Router router,
            @Context SecurityContext context, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer, @Context Validator validator)
            throws StateAccessException {

        Set<ConstraintViolation<Router>> violations = validator
                .validate(router);
        if (!violations.isEmpty()) {
            throw new BadRequestHttpException(violations);
        }

        if (!authorizer.routerAuthorized(context, AuthAction.WRITE, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to update this router.");
        }
        RouterDao dao = daoFactory.getRouterDao();
        router.setId(id);
        dao.update(router);
    }

    /**
     * Sub-resource class for tenant's virtual router.
     */
    public static class TenantRouterResource {

        private String tenantId = null;

        /**
         * Constructor
         *
         * @param tenantId
         *            ID of a tenant.
         */
        public TenantRouterResource(String tenantId) {
            this.tenantId = tenantId;
        }

        /**
         * Handler for creating a tenant router.
         *
         * @param router
         *            Router object.
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
        @Consumes({ VendorMediaType.APPLICATION_ROUTER_JSON,
                MediaType.APPLICATION_JSON })
        public Response create(Router router, @Context UriInfo uriInfo,
                @Context SecurityContext context,
                @Context DaoFactory daoFactory, @Context Authorizer authorizer,
                @Context Validator validator) throws StateAccessException {

            Set<ConstraintViolation<Router>> violations = validator
                    .validate(router);
            if (!violations.isEmpty()) {
                throw new BadRequestHttpException(violations);
            }

            if (!authorizer
                    .tenantAuthorized(context, AuthAction.READ, tenantId)) {
                throw new ForbiddenHttpException(
                        "Not authorized to add router to this tenant.");
            }

            RouterDao dao = daoFactory.getRouterDao();
            router.setTenantId(tenantId);
            UUID id = dao.create(router);
            return Response.created(
                    ResourceUriBuilder.getRouter(uriInfo.getBaseUri(), id))
                    .build();
        }

        /**
         * Handler to list tenant routers.
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
         * @return A list of Router objects.
         */
        @GET
        @PermitAll
        @Produces({ VendorMediaType.APPLICATION_ROUTER_COLLECTION_JSON,
                MediaType.APPLICATION_JSON })
        public List<Router> list(@Context SecurityContext context,
                @Context UriInfo uriInfo, @Context DaoFactory daoFactory,
                @Context Authorizer authorizer) throws StateAccessException {

            if (!authorizer
                    .tenantAuthorized(context, AuthAction.READ, tenantId)) {
                throw new ForbiddenHttpException(
                        "Not authorized to view routers of this tenant.");
            }

            RouterDao dao = daoFactory.getRouterDao();
            List<Router> routers = dao.list(tenantId);
            if (routers != null) {
                for (UriResource resource : routers) {
                    resource.setBaseUri(uriInfo.getBaseUri());
                }
            }
            return routers;
        }
    }
}
