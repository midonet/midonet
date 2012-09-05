/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.vpn.rest_api;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.servlet.RequestScoped;
import com.midokura.midolman.mgmt.auth.AuthAction;
import com.midokura.midolman.mgmt.auth.AuthRole;
import com.midokura.midolman.mgmt.auth.Authorizer;
import com.midokura.midolman.mgmt.VendorMediaType;
import com.midokura.midolman.mgmt.auth.ForbiddenHttpException;
import com.midokura.midolman.mgmt.rest_api.NotFoundHttpException;
import com.midokura.midolman.mgmt.ResourceUriBuilder;
import com.midokura.midolman.mgmt.network.auth.PortAuthorizer;
import com.midokura.midolman.mgmt.vpn.Vpn;
import com.midokura.midolman.mgmt.vpn.auth.VpnAuthorizer;
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
public class VpnResource {

    private final static Logger log = LoggerFactory
            .getLogger(VpnResource.class);

    private final SecurityContext context;
    private final UriInfo uriInfo;
    private final Authorizer authorizer;
    private final DataClient dataClient;

    @Inject
    public VpnResource(UriInfo uriInfo, SecurityContext context,
                       VpnAuthorizer authorizer, DataClient dataClient) {
        this.context = context;
        this.uriInfo = uriInfo;
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
        vpn.setBaseUri(uriInfo.getBaseUri());

        return vpn;
    }

    /**
     * Sub-resource class for port's VPN.
     */
    @RequestScoped
    public static class PortVpnResource {

        private final UUID portId;
        private final SecurityContext context;
        private final UriInfo uriInfo;
        private final Authorizer authorizer;
        private final DataClient dataClient;

        @Inject
        public PortVpnResource(UriInfo uriInfo,
                               SecurityContext context,
                               PortAuthorizer authorizer,
                               DataClient dataClient,
                               @Assisted UUID portId) {
            this.portId = portId;
            this.context = context;
            this.uriInfo = uriInfo;
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
                    ResourceUriBuilder.getVpn(
                            uriInfo.getBaseUri(), id)).build();
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
                    vpn.setBaseUri(uriInfo.getBaseUri());
                    vpnList.add(vpn);
                }
            }
            return vpnList;
        }
    }
}
