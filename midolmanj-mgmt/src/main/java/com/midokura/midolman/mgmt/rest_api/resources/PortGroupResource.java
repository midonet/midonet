/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.mgmt.rest_api.resources;

import com.google.inject.Inject;
import com.google.inject.servlet.RequestScoped;
import com.midokura.midolman.mgmt.auth.AuthAction;
import com.midokura.midolman.mgmt.auth.AuthRole;
import com.midokura.midolman.mgmt.auth.authorizer.Authorizer;
import com.midokura.midolman.mgmt.auth.authorizer.PortGroupAuthorizer;
import com.midokura.midolman.mgmt.data.dao.PortGroupDao;
import com.midokura.midolman.mgmt.data.dto.PortGroup;
import com.midokura.midolman.mgmt.data.dto.UriResource;
import com.midokura.midolman.mgmt.http.VendorMediaType;
import com.midokura.midolman.mgmt.jaxrs.BadRequestHttpException;
import com.midokura.midolman.mgmt.jaxrs.ForbiddenHttpException;
import com.midokura.midolman.mgmt.jaxrs.NotFoundHttpException;
import com.midokura.midolman.mgmt.jaxrs.ResourceUriBuilder;
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
 * Root resource class for port groups.
 */
@RequestScoped
public class PortGroupResource {

    private final static Logger log = LoggerFactory
            .getLogger(PortGroupResource.class);

    private final SecurityContext context;
    private final UriInfo uriInfo;
    private final Authorizer authorizer;
    private final Validator validator;
    private final PortGroupDao dao;


    @Inject
    public PortGroupResource(UriInfo uriInfo, SecurityContext context,
                          PortGroupAuthorizer authorizer, Validator validator,
                          PortGroupDao dao) {
        this.context = context;
        this.uriInfo = uriInfo;
        this.authorizer = authorizer;
        this.validator = validator;
        this.dao = dao;
    }

    /**
     * Handler to deleting a port group.
     *
     * @param id
     *            PortGroup ID from the request.
     * @throws com.midokura.midolman.state.StateAccessException
     *             Data access error.
     */
    @DELETE
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Path("{id}")
    public void delete(@PathParam("id") UUID id)
            throws StateAccessException, InvalidStateOperationException {

        if (!authorizer.authorize(context, AuthAction.WRITE, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to delete this port group.");
        }

        try {
            dao.delete(id);
        } catch (NoStatePathException e) {
            // Deleting a non-existing record is OK.
            log.warn("The resource does not exist", e);
        }
    }

    /**
     * Handler to getting a port group.
     *
     * @param id
     *            PortGroup ID from the request.
     * @throws com.midokura.midolman.state.StateAccessException
     *             Data access error.
     * @return A PortGroup object.
     */
    @GET
    @PermitAll
    @Path("{id}")
    @Produces({ VendorMediaType.APPLICATION_PORTGROUP_JSON })
    public PortGroup get(@PathParam("id") UUID id)
            throws StateAccessException {

        if (!authorizer.authorize(context, AuthAction.READ, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to view this port group.");
        }

        PortGroup group = dao.get(id);
        if (group == null) {
            throw new NotFoundHttpException(
                    "The requested resource was not found.");
        }
        group.setBaseUri(uriInfo.getBaseUri());

        return group;
    }

    /**
     * Handler for creating a tenant port group.
     *
     * @param group
     *            PortGroup object.
     * @throws com.midokura.midolman.state.StateAccessException
     *             Data access error.
     * @returns Response object with 201 status code set if successful.
     */
    @POST
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Consumes({ VendorMediaType.APPLICATION_PORTGROUP_JSON })
    public Response create(PortGroup group)
            throws StateAccessException, InvalidStateOperationException {

        Set<ConstraintViolation<PortGroup>> violations = validator
                .validate(group);
        if (!violations.isEmpty()) {
            throw new BadRequestHttpException(violations);
        }

        if (!Authorizer.isAdminOrOwner(context, group.getTenantId())) {
            throw new ForbiddenHttpException(
                    "Not authorized to add PortGroup to this tenant.");
        }

        UUID id = dao.create(group);
        return Response.created(
                ResourceUriBuilder.getPortGroup(uriInfo.getBaseUri(), id))
                .build();
    }

    /**
     * Handler to getting a collection of PortGroups.
     *
     * @throws com.midokura.midolman.state.StateAccessException
     *             Data access error.
     * @return A list of PortGroup objects.
     */
    @GET
    @PermitAll
    @Produces({ VendorMediaType.APPLICATION_PORTGROUP_COLLECTION_JSON,
            MediaType.APPLICATION_JSON })
    public List<PortGroup> list(@QueryParam("tenant_id") String tenantId,
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
                    "Not authorized to view port group of this request.");
        }

        List<PortGroup> groups = null;
        if (name == null) {
            groups = dao.findByTenant(tenantId);
        } else {
            PortGroup group =  dao.findByName(tenantId, name);
            if (group != null) {
                groups = new ArrayList<PortGroup>();
                groups.add(group);
            }
        }

        if (groups != null) {
            for (UriResource resource : groups) {
                resource.setBaseUri(uriInfo.getBaseUri());
            }
        }
        return groups;
    }
}
