/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.network.rest_api;

import java.util.ArrayList;
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
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import com.google.inject.Inject;
import com.google.inject.servlet.RequestScoped;
import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.VendorMediaType;
import org.midonet.api.auth.AuthAction;
import org.midonet.api.auth.AuthRole;
import org.midonet.api.auth.Authorizer;
import org.midonet.api.auth.ForbiddenHttpException;
import org.midonet.api.network.VlanBridge;
import org.midonet.api.network.auth.VlanBridgeAuthorizer;
import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.BadRequestHttpException;
import org.midonet.api.rest_api.NotFoundHttpException;
import org.midonet.api.rest_api.ResourceFactory;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.cluster.DataClient;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.InvalidStateOperationException;
import org.midonet.midolman.state.StateAccessException;

/**
 * Root resource class for Virtual bridges.
 */
@RequestScoped
public class VlanBridgeResource extends AbstractResource {

    private final Authorizer authorizer;
    private final Validator validator;
    private final DataClient dataClient;
    private final ResourceFactory factory;

    @Inject
    public VlanBridgeResource(RestApiConfig config, UriInfo uriInfo,
                              SecurityContext context,
                              VlanBridgeAuthorizer authorizer,
                              Validator validator, DataClient dataClient,
                              ResourceFactory factory) {
        super(config, uriInfo, context);
        this.authorizer = authorizer;
        this.validator = validator;
        this.dataClient = dataClient;
        this.factory = factory;
    }

    /**
     * Handler to deleting a vlan-bridge.
     *
     * @param id Vlan Bridge ID from the request.
     * @throws org.midonet.midolman.state.StateAccessException
     *             Data access error.
     */
    @DELETE
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Path("{id}")
    public void delete(@PathParam("id") UUID id)
            throws StateAccessException,
            InvalidStateOperationException,
            SerializationException {

        org.midonet.cluster.data.VlanAwareBridge bridgeData =
                dataClient.vlanBridgesGet(id);
        if (bridgeData == null) {
            return;
        }

        if (!authorizer.authorize(context, AuthAction.WRITE, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to delete this vlan bridge.");
        }

        dataClient.vlanBridgesDelete(id);
    }

    /**
     * Handler to getting a vlan-bridge.
     *
     * @param id Vlan Bridge ID from the request.
     * @throws org.midonet.midolman.state.StateAccessException Data access error.
     * @return A Vlan Bridge object.
     */
    @GET
    @PermitAll
    @Path("{id}")
    @Produces({ VendorMediaType.APPLICATION_VLAN_BRIDGE_JSON,
            MediaType.APPLICATION_JSON })
    public VlanBridge get(@PathParam("id") UUID id)
            throws StateAccessException,
            SerializationException {

        if (!authorizer.authorize(context, AuthAction.READ, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to view this vlan-aware bridge.");
        }

        org.midonet.cluster.data.VlanAwareBridge bridgeData =
                dataClient.vlanBridgesGet(id);
        if (bridgeData == null) {
            throw new NotFoundHttpException(
                    "The requested resource was not found.");
        }

        // Convert to the REST API DTO
        VlanBridge bridge = new VlanBridge(bridgeData);
        bridge.setBaseUri(getBaseUri());

        return bridge;
    }

    /**
     * Handler to updating a bridge.
     *
     * @param id Bridge ID from the request.
     * @param bridge Bridge object.
     * @throws org.midonet.midolman.state.StateAccessException
     *             Data access error.
     */
    @PUT
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Path("{id}")
    @Consumes({ VendorMediaType.APPLICATION_VLAN_BRIDGE_JSON,
            MediaType.APPLICATION_JSON })
    public void update(@PathParam("id") UUID id, VlanBridge bridge)
            throws StateAccessException,
            InvalidStateOperationException,
            SerializationException {

        bridge.setId(id);

        Set<ConstraintViolation<VlanBridge>> violations = validator.validate(
                bridge, VlanBridge.VlanBridgeUpdateGroupSequence.class);
        if (!violations.isEmpty()) {
            throw new BadRequestHttpException(violations);
        }

        if (!authorizer.authorize(context, AuthAction.WRITE, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to update this vlan bridge.");
        }

        dataClient.vlanBridgesUpdate(bridge.toData());
    }

    /**
     * Handler for creating a tenant vlan bridge.
     *
     * @param bridge Vlan Bridge object.
     * @throws org.midonet.midolman.state.StateAccessException
     *             Data access error.
     * @return Response object with 201 status code set if successful.
     */
    @POST
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Consumes({ VendorMediaType.APPLICATION_VLAN_BRIDGE_JSON,
            MediaType.APPLICATION_JSON })
    public Response create(VlanBridge bridge)
            throws StateAccessException,
            InvalidStateOperationException,
            SerializationException {

        Set<ConstraintViolation<VlanBridge>> violations = validator.validate(
                bridge, VlanBridge.VlanBridgeCreateGroupSequence.class);
        if (!violations.isEmpty()) {
            throw new BadRequestHttpException(violations);
        }

        if (!Authorizer.isAdminOrOwner(context, bridge.getTenantId())) {
            throw new ForbiddenHttpException(
                    "Not authorized to add vlan bridge to this tenant.");
        }

        UUID id = dataClient.vlanBridgesCreate(bridge.toData());
        return Response.created(
                ResourceUriBuilder.getVlanBridge(getBaseUri(), id))
                .build();
    }

    /**
     * Handler to list tenant bridges.
     *
     * @throws org.midonet.midolman.state.StateAccessException
     *             Data access error.
     * @return A list of Bridge objects.
     */
    @GET
    @PermitAll
    @Produces({ VendorMediaType.APPLICATION_VLAN_BRIDGE_COLLECTION_JSON,
            MediaType.APPLICATION_JSON })
    public List<VlanBridge> list(@QueryParam("tenant_id") String tenantId)
            throws StateAccessException,
            SerializationException {

        if (tenantId == null) {
            throw new BadRequestHttpException(
                    "Currently tenant_id is required for search.");
        }

        // Tenant ID query string is a special parameter that is used to check
        // authorization.
        if (!Authorizer.isAdminOrOwner(context, tenantId)) {
            throw new ForbiddenHttpException(
                    "Not authorized to view bridges of this request.");
        }

        List<org.midonet.cluster.data.VlanAwareBridge> dataBridges =
                dataClient.vlanBridgesFindByTenant(tenantId);
        List<VlanBridge> bridges = new ArrayList<VlanBridge>();
        if (dataBridges != null) {
            for (org.midonet.cluster.data.VlanAwareBridge dataBridge :
                    dataBridges) {
                VlanBridge bridge = new VlanBridge(dataBridge);
                bridge.setBaseUri(getBaseUri());
                bridges.add(bridge);
            }
        }
        return bridges;
    }

}

