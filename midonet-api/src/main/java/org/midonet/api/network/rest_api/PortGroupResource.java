/*
 * Copyright 2012 Midokura Europe SARL
 */
package org.midonet.api.network.rest_api;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.servlet.RequestScoped;
import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.VendorMediaType;
import org.midonet.api.auth.AuthAction;
import org.midonet.api.auth.AuthRole;
import org.midonet.api.auth.Authorizer;
import org.midonet.api.auth.ForbiddenHttpException;
import org.midonet.api.network.PortGroup;
import org.midonet.api.network.auth.PortAuthorizer;
import org.midonet.api.network.auth.PortGroupAuthorizer;
import org.midonet.api.rest_api.*;
import org.midonet.cluster.DataClient;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.InvalidStateOperationException;
import org.midonet.midolman.state.StateAccessException;
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
public class PortGroupResource extends AbstractResource {

    private final static Logger log = LoggerFactory
            .getLogger(PortGroupResource.class);

    private final PortGroupAuthorizer authorizer;
    private final Validator validator;
    private final DataClient dataClient;
    private final ResourceFactory factory;

    @Inject
    public PortGroupResource(RestApiConfig config, UriInfo uriInfo,
                             SecurityContext context,
                             PortGroupAuthorizer authorizer,
                             Validator validator, DataClient dataClient,
                             ResourceFactory factory) {
        super(config, uriInfo, context);
        this.authorizer = authorizer;
        this.validator = validator;
        this.dataClient = dataClient;
        this.factory = factory;
    }

    /**
     * Handler to deleting a port group.
     *
     * @param id
     *            PortGroup ID from the request.
     * @throws org.midonet.midolman.state.StateAccessException
     *             Data access error.
     */
    @DELETE
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Path("{id}")
    public void delete(@PathParam("id") UUID id)
            throws StateAccessException,
            InvalidStateOperationException, SerializationException {

        org.midonet.cluster.data.PortGroup portGroupData =
                dataClient.portGroupsGet(id);
        if (portGroupData == null) {
            return;
        }

        if (!authorizer.authorize(context, AuthAction.WRITE, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to delete this port group.");
        }

        dataClient.portGroupsDelete(id);
    }

    /**
     * Handler to getting a port group.
     *
     * @param id
     *            PortGroup ID from the request.
     * @throws org.midonet.midolman.state.StateAccessException
     *             Data access error.
     * @return A PortGroup object.
     */
    @GET
    @PermitAll
    @Path("{id}")
    @Produces({ VendorMediaType.APPLICATION_PORTGROUP_JSON })
    public PortGroup get(@PathParam("id") UUID id)
            throws StateAccessException, SerializationException {

        if (!authorizer.authorize(context, AuthAction.READ, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to view this port group.");
        }

        org.midonet.cluster.data.PortGroup portGroupData =
                dataClient.portGroupsGet(id);
        if (portGroupData == null) {
            throw new NotFoundHttpException(
                    "The requested resource was not found.");
        }

        // Convert to the REST API DTO
        PortGroup portGroup = new PortGroup(portGroupData);
        portGroup.setBaseUri(getBaseUri());

        return portGroup;
    }

    /**
     * Handler for creating a tenant port group.
     *
     * @param group
     *            PortGroup object.
     * @throws org.midonet.midolman.state.StateAccessException
     *             Data access error.
     * @returns Response object with 201 status code set if successful.
     */
    @POST
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Consumes({ VendorMediaType.APPLICATION_PORTGROUP_JSON,
                   MediaType.APPLICATION_JSON })
    public Response create(PortGroup group)
            throws StateAccessException,
            InvalidStateOperationException, SerializationException {

        Set<ConstraintViolation<PortGroup>> violations = validator
                .validate(group, PortGroup.PortGroupCreateGroupSequence.class);
        if (!violations.isEmpty()) {
            throw new BadRequestHttpException(violations);
        }

        if (!Authorizer.isAdminOrOwner(context, group.getTenantId())) {
            throw new ForbiddenHttpException(
                    "Not authorized to add PortGroup to this tenant.");
        }

        UUID id = dataClient.portGroupsCreate(group.toData());
        return Response.created(
                ResourceUriBuilder.getPortGroup(getBaseUri(), id))
                .build();
    }

    @GET
    @Path("/name")
    @PermitAll
    @Produces({ VendorMediaType.APPLICATION_PORTGROUP_JSON,
            MediaType.APPLICATION_JSON })
    public PortGroup getByName(@QueryParam("tenant_id") String tenantId,
                               @QueryParam("name") String name)
            throws StateAccessException, SerializationException {
        if (tenantId == null || name == null) {
            throw new BadRequestHttpException(
                    "Currently tenant_id and name are required for search.");
        }

        org.midonet.cluster.data.PortGroup portGroupData =
                dataClient.portGroupsGetByName(tenantId, name);
        if (portGroupData == null) {
            throw new NotFoundHttpException(
                    "The requested resource was not found.");
        }

        if (!authorizer.authorize(
                context, AuthAction.READ, portGroupData.getId())) {
            throw new ForbiddenHttpException(
                    "Not authorized to view this chain.");
        }

        // Convert to the REST API DTO
        PortGroup portGroup = new PortGroup(portGroupData);
        portGroup.setBaseUri(getBaseUri());
        return portGroup;
    }

    /**
     * Handler to getting a collection of PortGroups.
     *
     * @throws org.midonet.midolman.state.StateAccessException
     *             Data access error.
     * @return A list of PortGroup objects.
     */
    @GET
    @RolesAllowed({ AuthRole.ADMIN })
    @Produces({ VendorMediaType.APPLICATION_PORTGROUP_COLLECTION_JSON,
            MediaType.APPLICATION_JSON })
    public List<PortGroup> list(@QueryParam("tenant_id") String tenantId,
                                @QueryParam("port_id") UUID portId)
            throws StateAccessException, SerializationException {

        List<org.midonet.cluster.data.PortGroup> portGroupDataList = null;
        if (portId != null) {
            portGroupDataList = dataClient.portGroupsFindByPort(portId);
        } else if (tenantId != null) {
            portGroupDataList = dataClient.portGroupsFindByTenant(tenantId);
        } else {
            portGroupDataList = dataClient.portGroupsGetAll();
        }

        List<PortGroup> portGroups = new ArrayList<PortGroup>();
        if (portGroupDataList != null) {
            for (org.midonet.cluster.data.PortGroup portGroupData :
                    portGroupDataList) {
                PortGroup portGroup = new PortGroup(portGroupData);
                portGroup.setBaseUri(getBaseUri());
                portGroups.add(portGroup);
            }
        }
        return portGroups;
    }

    @Path("/{id}" + ResourceUriBuilder.PORTS)
    public PortResource.PortGroupPortResource getPortGroupPortResource(
            @PathParam("id") UUID id) {
        return factory.getPortGroupPortResource(id);
    }

    /**
     * Sub-resource class for port's port groups.
     */
    @RequestScoped
    public static class PortPortGroupResource extends AbstractResource {

        private final UUID portId;
        private final DataClient dataClient;
        private final PortAuthorizer portAuthorizer;

        @Inject
        public PortPortGroupResource(RestApiConfig config,
                                     UriInfo uriInfo,
                                     SecurityContext context,
                                     PortAuthorizer portAuthorizer,
                                     DataClient dataClient,
                                     @Assisted UUID portId) {
            super(config, uriInfo, context);
            this.portId = portId;
            this.portAuthorizer = portAuthorizer;
            this.dataClient = dataClient;
        }

        /**
         * Handler to list port's PortGroups.
         *
         * @throws StateAccessException
         *             Data access error.
         * @return A list of PortGroup objects.
         */
        @GET
        @PermitAll
        @Produces({ VendorMediaType.APPLICATION_PORTGROUP_COLLECTION_JSON,
                MediaType.APPLICATION_JSON })
        public List<PortGroup> list() throws StateAccessException,
                SerializationException {

            if (!portAuthorizer.authorize(context, AuthAction.READ, portId)) {
                throw new ForbiddenHttpException(
                        "Not authorized to view port groups for this port.");
            }
            List<org.midonet.cluster.data.PortGroup> portGroupDataList =
                    dataClient.portGroupsFindByPort(portId);

            List<PortGroup> portGroups = new ArrayList<PortGroup>();
            if (portGroupDataList != null) {
                for (org.midonet.cluster.data.PortGroup portGroupData :
                        portGroupDataList) {
                    PortGroup portGroup = new PortGroup(portGroupData);
                    portGroup.setBaseUri(getBaseUri());
                    portGroups.add(portGroup);
                }
            }
            return portGroups;
        }
    }


    /**
     * Sub-resource class for tenant's port groups.
     */
    @RequestScoped
    public static class TenantPortGroupResource extends AbstractResource {

        private final String tenantId;
        private final DataClient dataClient;

        @Inject
        public TenantPortGroupResource(RestApiConfig config,
                                       UriInfo uriInfo,
                                       SecurityContext context,
                                       DataClient dataClient,
                                       @Assisted String tenantId) {
            super(config, uriInfo, context);
            this.tenantId = tenantId;
            this.dataClient = dataClient;
        }

        /**
         * Handler to list tenant PortGroups.
         *
         * @throws StateAccessException
         *             Data access error.
         * @return A list of PortGroup objects.
         */
        @GET
        @PermitAll
        @Produces({ VendorMediaType.APPLICATION_PORTGROUP_COLLECTION_JSON,
                MediaType.APPLICATION_JSON })
        public List<PortGroup> list() throws StateAccessException,
                SerializationException {

            if (!Authorizer.isAdminOrOwner(context, tenantId)) {
                throw new ForbiddenHttpException(
                        "Not authorized to view port groups of this request.");
            }

            List<org.midonet.cluster.data.PortGroup> dataPortGroups =
                    dataClient.portGroupsFindByTenant(tenantId);
            List<PortGroup> PortGroups = new ArrayList<PortGroup>();
            if (dataPortGroups != null) {
                for (org.midonet.cluster.data.PortGroup dataPortGroup :
                        dataPortGroups) {
                    PortGroup PortGroup = new PortGroup(dataPortGroup);
                    PortGroup.setBaseUri(getBaseUri());
                    PortGroups.add(PortGroup);
                }
            }
            return PortGroups;
        }
    }
}
