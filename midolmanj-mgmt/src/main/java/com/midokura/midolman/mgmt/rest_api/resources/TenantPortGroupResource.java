/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.rest_api.resources;

import java.util.List;
import java.util.UUID;
import javax.annotation.security.PermitAll;
import javax.annotation.security.RolesAllowed;
import javax.ws.rs.Consumes;
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

import com.midokura.midolman.mgmt.auth.AuthAction;
import com.midokura.midolman.mgmt.auth.AuthRole;
import com.midokura.midolman.mgmt.auth.Authorizer;
import com.midokura.midolman.mgmt.data.DaoFactory;
import com.midokura.midolman.mgmt.data.dao.PortGroupDao;
import com.midokura.midolman.mgmt.data.dto.PortGroup;
import com.midokura.midolman.mgmt.data.dto.UriResource;
import com.midokura.midolman.mgmt.rest_api.core.ResourceUriBuilder;
import com.midokura.midolman.mgmt.rest_api.core.VendorMediaType;
import com.midokura.midolman.mgmt.rest_api.jaxrs.ForbiddenHttpException;
import com.midokura.midolman.state.StateAccessException;

/**
 * Sub-resource class for tenant's port groups.
 */
public class TenantPortGroupResource {

    private final String tenantID;

    /**
     * Constructor
     *
     * @param tenantID
     *            ID of a tenant.
     */
    public TenantPortGroupResource(String tenantID) {
        this.tenantID = tenantID;
    }

    /**
     * Handler for creating a tenant port group.
     *
     * @param group
     *            PortGroup object.
     * @param uriInfo
     *            Object that holds the request URI data.
     * @param context
     *            Object that holds the security data.
     * @param daoFactory
     *            Data access factory object.
     * @param authorizer
     *            Authorizer object.
     * @throws com.midokura.midolman.state.StateAccessException
     *             Data access error.
     * @returns Response object with 201 status code set if successful.
     */
    @POST
    @RolesAllowed({AuthRole.ADMIN, AuthRole.TENANT_ADMIN})
    @Consumes({ VendorMediaType.APPLICATION_PORTGROUP_JSON })
    public Response create(PortGroup group, @Context UriInfo uriInfo,
            @Context SecurityContext context, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException {

        if (!authorizer.tenantAuthorized(context, AuthAction.WRITE, tenantID)) {
            throw new ForbiddenHttpException(
                    "Not authorized to add PortGroup to this tenant.");
        }

        PortGroupDao dao = daoFactory.getPortGroupDao();
        group.setTenantId(tenantID);
        UUID id = dao.create(group);
        return Response.created(ResourceUriBuilder.getPortGroup(
                uriInfo.getBaseUri(), id)).build();
    }

    /**
     * Handler to getting a collection of PortGroups.
     *
     * @param context
     *            Object that holds the security data.
     * @param uriInfo
     *            Object that holds the request URI data.
     * @param daoFactory
     *            Data access factory object.
     * @param authorizer
     *            Authorizer object.
     * @throws com.midokura.midolman.state.StateAccessException
     *             Data access error.
     * @return A list of PortGroup objects.
     */
    @GET
    @PermitAll
    @Produces({ VendorMediaType.APPLICATION_PORTGROUP_COLLECTION_JSON,
            MediaType.APPLICATION_JSON })
    public List<PortGroup> list(@Context SecurityContext context,
            @Context UriInfo uriInfo, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException {

        if (!authorizer.tenantAuthorized(context, AuthAction.READ, tenantID)) {
            throw new ForbiddenHttpException(
                    "Not authorized to view these port groups.");
        }

        PortGroupDao dao = daoFactory.getPortGroupDao();
        List<PortGroup> groups = dao.list(tenantID);
        if (groups != null) {
            for (UriResource resource : groups) {
                resource.setBaseUri(uriInfo.getBaseUri());
            }
        }
        return groups;
    }

    /**
     * Handler to getting a Port Group.
     *
     * @param name
     *            Group name from the request.
     * @param context
     *            Object that holds the security data.
     * @param uriInfo
     *            Object that holds the request URI data.
     * @param daoFactory
     *            Data access factory object.
     * @param authorizer
     *            Authorizer object.
     * @throws com.midokura.midolman.state.StateAccessException
     *             Data access error.
     * @return A PortGroup object.
     */
    @GET
    @PermitAll
    @Path("{name}")
    @Produces({ VendorMediaType.APPLICATION_PORTGROUP_JSON,
            MediaType.APPLICATION_JSON })
    public PortGroup get(@PathParam("name") String name,
                     @Context SecurityContext context, @Context UriInfo uriInfo,
                     @Context DaoFactory daoFactory, @Context Authorizer authorizer)
            throws StateAccessException {

        if (!authorizer.tenantAuthorized(context, AuthAction.READ, tenantID)) {
            throw new ForbiddenHttpException(
                    "Not authorized to view the PortGroups of this tenant.");
        }

        PortGroupDao dao = daoFactory.getPortGroupDao();
        PortGroup group = dao.get(tenantID, name);
        if (group != null) {
            group.setBaseUri(uriInfo.getBaseUri());
        }
        return group;
    }
}