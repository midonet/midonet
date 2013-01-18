/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midonet.api.vpn.rest_api;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.servlet.RequestScoped;
import com.midokura.midonet.api.VendorMediaType;
import com.midokura.midonet.api.rest_api.AbstractResource;
import com.midokura.midonet.api.rest_api.NotFoundHttpException;
import com.midokura.midonet.api.vpn.auth.VpnAuthorizer;
import com.midokura.midonet.api.ResourceUriBuilder;
import com.midokura.midonet.api.auth.AuthAction;
import com.midokura.midonet.api.auth.AuthRole;
import com.midokura.midonet.api.auth.Authorizer;
import com.midokura.midonet.api.auth.ForbiddenHttpException;
import com.midokura.midonet.api.network.auth.PortAuthorizer;
import com.midokura.midonet.api.rest_api.RestApiConfig;
import com.midokura.midonet.api.vpn.Vpn;
import com.midokura.midolman.state.InvalidStateOperationException;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midonet.cluster.DataClient;
import com.midokura.midonet.cluster.data.VPN;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.security.PermitAll;
import javax.annotation.security.RolesAllowed;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Root resource class for vpns.
 */
@RequestScoped
public class VpnResource extends AbstractResource {

    private final static Logger log = LoggerFactory
            .getLogger(VpnResource.class);

    private final Authorizer authorizer;
    private final DataClient dataClient;

    @Inject
    public VpnResource(RestApiConfig config, UriInfo uriInfo,
                       SecurityContext context,
                       VpnAuthorizer authorizer, DataClient dataClient) {
        super(config, uriInfo, context);
        this.authorizer = authorizer;
        this.dataClient = dataClient;
    }

    /**
     * Handler to deleting a VPN record.
     *
     * @param id
     *            VPN ID from the request.
     * @throws StateAccessException
     *             Data access error.
     */
    @DELETE
    @RolesAllowed({AuthRole.ADMIN, AuthRole.TENANT_ADMIN})
    @Path("{id}")
    public void delete(@PathParam("id") UUID id)
            throws StateAccessException, InvalidStateOperationException {

        VPN vpnData = dataClient.vpnGet(id);
        if (vpnData == null) {
            return;
        }

        if (!authorizer.authorize(context, AuthAction.WRITE, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to delete this VPN.");
        }

        dataClient.vpnDelete(id);
    }

    /**
     * Handler to getting VPN configuration record.
     *
     * @param id
     *            VPN ID from the request.
     * @throws StateAccessException
     *             Data access error.
     * @return A Vpn object.
     */
    @GET
    @PermitAll
    @Path("{id}")
    @Produces({ VendorMediaType.APPLICATION_VPN_JSON,
            MediaType.APPLICATION_JSON })
    public Vpn get(@PathParam("id") UUID id) throws StateAccessException {

        if (!authorizer.authorize(context, AuthAction.READ, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to view this VPN.");
        }

        VPN vpnData = dataClient.vpnGet(id);
        if (vpnData == null) {
            throw new NotFoundHttpException(
                    "The requested resource was not found.");
        }

        // Convert to the REST API DTO
        Vpn vpn = new Vpn(vpnData);
        vpn.setBaseUri(getBaseUri());

        return vpn;
    }

    /**
     * Sub-resource class for port's VPN.
     */
    @RequestScoped
    public static class PortVpnResource extends AbstractResource {

        private final UUID portId;
        private final Authorizer authorizer;
        private final DataClient dataClient;

        @Inject
        public PortVpnResource(RestApiConfig config, UriInfo uriInfo,
                               SecurityContext context,
                               PortAuthorizer authorizer,
                               DataClient dataClient,
                               @Assisted UUID portId) {
            super(config, uriInfo, context);
            this.portId = portId;
            this.authorizer = authorizer;
            this.dataClient = dataClient;
        }

        /**
         * Handler for creating a VPN record.
         *
         * @param vpn
         *            VPN object.
         * @throws StateAccessException
         *             Data access error.
         * @returns Response object with 201 status code set if successful.
         */
        @POST
        @RolesAllowed({AuthRole.ADMIN, AuthRole.TENANT_ADMIN})
        @Consumes({ VendorMediaType.APPLICATION_VPN_JSON,
                MediaType.APPLICATION_JSON })
        public Response create(Vpn vpn)
                throws StateAccessException, InvalidStateOperationException {

            vpn.setPublicPortId(portId);

            if (!authorizer.authorize(context, AuthAction.WRITE, portId)) {
                throw new ForbiddenHttpException(
                        "Not authorized to add VPN to this port.");
            }

            UUID id = dataClient.vpnCreate(vpn.toData());
            return Response.created(
                    ResourceUriBuilder.getVpn(getBaseUri(), id)).build();
        }

        /**
         * Handler to getting a list of VPN records.
         *
         * @throws StateAccessException
         *             Data access error.
         * @return A list of VPN objects.
         */
        @GET
        @PermitAll
        @Produces({ VendorMediaType.APPLICATION_VPN_COLLECTION_JSON,
                MediaType.APPLICATION_JSON })
        public List<Vpn> list() throws StateAccessException {

            if (!authorizer.authorize(context, AuthAction.READ, portId)) {
                throw new ForbiddenHttpException(
                        "Not authorized to view these VPNs.");
            }

            List<VPN> vpnDataList = dataClient.vpnFindByPort(portId);
            List<Vpn> vpnList = new ArrayList<Vpn>();
            if (vpnDataList != null) {
                for (VPN vpnData : vpnDataList) {
                    Vpn vpn = new Vpn(vpnData);
                    vpn.setBaseUri(getBaseUri());
                    vpnList.add(vpn);
                }
            }
            return vpnList;
        }
    }
}
