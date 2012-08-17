/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.rest_api.resources;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.servlet.RequestScoped;
import com.midokura.midolman.mgmt.auth.AuthAction;
import com.midokura.midolman.mgmt.auth.AuthRole;
import com.midokura.midolman.mgmt.auth.Authorizer;
import com.midokura.midolman.mgmt.data.dao.BridgeDao;
import com.midokura.midolman.mgmt.data.dto.Bridge;
import com.midokura.midolman.mgmt.data.dto.Bridge.BridgeGroupSequence;
import com.midokura.midolman.mgmt.data.dto.UriResource;
import com.midokura.midolman.mgmt.http.VendorMediaType;
import com.midokura.midolman.mgmt.jaxrs.BadRequestHttpException;
import com.midokura.midolman.mgmt.jaxrs.ForbiddenHttpException;
import com.midokura.midolman.mgmt.jaxrs.NotFoundHttpException;
import com.midokura.midolman.mgmt.jaxrs.ResourceUriBuilder;
import com.midokura.midolman.mgmt.rest_api.resources.PortResource.BridgePeerPortResource;
import com.midokura.midolman.mgmt.rest_api.resources.PortResource.BridgePortResource;
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
import javax.ws.rs.core.*;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Root resource class for Virtual bridges.
 */
@RequestScoped
public class BridgeResource {

    private final static Logger log = LoggerFactory
            .getLogger(BridgeResource.class);

    private final SecurityContext context;
    private final UriInfo uriInfo;
    private final Authorizer authorizer;
    private final Validator validator;
    private final BridgeDao dao;
    private final ResourceFactory factory;

    @Inject
    public BridgeResource(UriInfo uriInfo, SecurityContext context,
                          Authorizer authorizer, Validator validator,
                          BridgeDao dao, ResourceFactory factory) {
        this.context = context;
        this.uriInfo = uriInfo;
        this.authorizer = authorizer;
        this.validator = validator;
        this.dao = dao;
        this.factory = factory;
    }

    /**
     * Handler to deleting a bridge.
     *
     * @param id
     *            Bridge ID from the request.
     * @throws StateAccessException
     *             Data access error.
     */
    @DELETE
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Path("{id}")
    public void delete(@PathParam("id") UUID id)
            throws StateAccessException, InvalidStateOperationException {

        if (!authorizer.bridgeAuthorized(context, AuthAction.WRITE, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to delete this bridge.");
        }

        try {
            dao.delete(id);
        } catch (NoStatePathException e) {
            // Deleting a non-existing record is OK.
            log.warn("The resource does not exist", e);
        }
    }

    /**
     * Handler to getting a bridge.
     *
     * @param id
     *            Bridge ID from the request.
     * @throws StateAccessException
     *             Data access error.
     * @return A Bridge object.
     */
    @GET
    @PermitAll
    @Path("{id}")
    @Produces({ VendorMediaType.APPLICATION_BRIDGE_JSON,
            MediaType.APPLICATION_JSON })
    public Bridge get(@PathParam("id") UUID id)
            throws StateAccessException {

        if (!authorizer.bridgeAuthorized(context, AuthAction.READ, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to view this bridge.");
        }

        Bridge bridge = dao.get(id);
        if (bridge == null) {
            throw new NotFoundHttpException(
                    "The requested resource was not found.");
        }
        bridge.setBaseUri(uriInfo.getBaseUri());

        return bridge;
    }

    /**
     * Port resource locator for bridges.
     *
     * @param id
     *            Bridge ID from the request.
     * @returns BridgePortResource object to handle sub-resource requests.
     */
    @Path("/{id}" + ResourceUriBuilder.PORTS)
    public BridgePortResource getPortResource(@PathParam("id") UUID id) {
        return factory.getBridgePortResource(id);
    }

    /**
     * Filtering database resource locator for bridges.
     *
     * @param id
     *            Bridge ID from the request.
     * @returns BridgeFilterDbResource object to handle sub-resource requests.
     */
    @Path("/{id}" + ResourceUriBuilder.FILTER_DB)
    public BridgeFilterDbResource getBridgeFilterDbResource(
            @PathParam("id") UUID id) {
        return factory.getBridgeFilterDbResource(id);
    }

    /**
     * DHCP resource locator for bridges.
     *
     * @param id
     *            Bridge ID from the request.
     * @returns BridgeDhcpResource object to handle sub-resource requests.
     */
    @Path("/{id}" + ResourceUriBuilder.DHCP)
    public BridgeDhcpResource getBridgeDhcpResource(@PathParam("id") UUID id) {
        return factory.getBridgeDhcpResource(id);
    }

    /**
     * Peer port resource locator for bridges.
     *
     * @param id
     *            Bridge ID from the request.
     * @returns BridgePeerPortResource object to handle sub-resource requests.
     */
    @Path("/{id}" + ResourceUriBuilder.PEER_PORTS)
    public BridgePeerPortResource getBridgePeerPortResource(
            @PathParam("id") UUID id) {
        return factory.getBridgePeerPortResource(id);
    }

    /**
     * Handler to updating a bridge.
     *
     * @param id
     *            Bridge ID from the request.
     * @param bridge
     *            Bridge object.
     * @throws StateAccessException
     *             Data access error.
     */
    @PUT
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Path("{id}")
    @Consumes({ VendorMediaType.APPLICATION_BRIDGE_JSON,
            MediaType.APPLICATION_JSON })
    public void update(@PathParam("id") UUID id, Bridge bridge)
            throws StateAccessException, InvalidStateOperationException {

        bridge.setId(id);

        Set<ConstraintViolation<Bridge>> violations = validator.validate(
                bridge, BridgeGroupSequence.class);
        if (!violations.isEmpty()) {
            throw new BadRequestHttpException(violations);
        }

        if (!authorizer.bridgeAuthorized(context, AuthAction.WRITE, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to update this bridge.");
        }
        dao.update(bridge);
    }

    /**
     * Sub-resource class for tenant's virtual switch.
     */
    @RequestScoped
    public static class TenantBridgeResource {

        private final String tenantId;
        private final SecurityContext context;
        private final UriInfo uriInfo;
        private final Authorizer authorizer;
        private final Validator validator;
        private final BridgeDao dao;

        @Inject
        public TenantBridgeResource(UriInfo uriInfo,
                                    SecurityContext context,
                                    Authorizer authorizer,
                                    Validator validator,
                                    BridgeDao dao,
                                    @Assisted String tenantId) {
            this.context = context;
            this.uriInfo = uriInfo;
            this.authorizer = authorizer;
            this.validator = validator;
            this.dao = dao;
            this.tenantId = tenantId;
        }

        /**
         * Handler for creating a tenant bridge.
         *
         * @param bridge
         *            Bridge object.
         * @throws StateAccessException
         *             Data access error.
         * @returns Response object with 201 status code set if successful.
         */
        @POST
        @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
        @Consumes({ VendorMediaType.APPLICATION_BRIDGE_JSON,
                MediaType.APPLICATION_JSON })
        public Response create(Bridge bridge)
                throws StateAccessException, InvalidStateOperationException {

            bridge.setTenantId(tenantId);

            Set<ConstraintViolation<Bridge>> violations = validator.validate(
                    bridge, BridgeGroupSequence.class);
            if (!violations.isEmpty()) {
                throw new BadRequestHttpException(violations);
            }

            if (!authorizer.tenantAuthorized(context, AuthAction.WRITE,
                    tenantId)) {
                throw new ForbiddenHttpException(
                        "Not authorized to add bridge to this tenant.");
            }

            UUID id = dao.create(bridge);
            return Response.created(
                    ResourceUriBuilder.getBridge(uriInfo.getBaseUri(), id))
                    .build();
        }

        /**
         * Handler to list tenant bridges.
         *
         * @throws StateAccessException
         *             Data access error.
         * @return A list of Bridge objects.
         */
        @GET
        @PermitAll
        @Produces({ VendorMediaType.APPLICATION_BRIDGE_COLLECTION_JSON,
                MediaType.APPLICATION_JSON })
        public List<Bridge> list() throws StateAccessException {

            if (!authorizer
                    .tenantAuthorized(context, AuthAction.READ, tenantId)) {
                throw new ForbiddenHttpException(
                        "Not authorized to view bridges of this tenant.");
            }

            List<Bridge> bridges = dao.findByTenant(tenantId);
            if (bridges != null) {
                for (UriResource resource : bridges) {
                    resource.setBaseUri(uriInfo.getBaseUri());
                }
            }
            return bridges;
        }
    }
}
