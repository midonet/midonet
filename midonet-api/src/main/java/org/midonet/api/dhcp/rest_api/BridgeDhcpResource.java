/*
 * Copyright (c) 2012-2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.api.dhcp.rest_api;

import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.VendorMediaType;
import org.midonet.api.auth.AuthAction;
import org.midonet.api.auth.AuthRole;
import org.midonet.api.auth.ForbiddenHttpException;
import org.midonet.api.dhcp.DhcpSubnet;
import org.midonet.api.network.auth.BridgeAuthorizer;
import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.NotFoundHttpException;
import org.midonet.api.rest_api.ResourceFactory;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.data.dhcp.Subnet;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.packets.IPv4Subnet;
import org.midonet.packets.IntIPv4;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.servlet.RequestScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.security.PermitAll;
import javax.annotation.security.RolesAllowed;
import javax.validation.Validator;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@RequestScoped
public class BridgeDhcpResource extends AbstractResource {

    private final UUID bridgeId;
    private final BridgeAuthorizer authorizer;
    private final ResourceFactory factory;

    @Inject
    public BridgeDhcpResource(RestApiConfig config, UriInfo uriInfo,
                              SecurityContext context,
                              BridgeAuthorizer authorizer,
                              Validator validator,
                              DataClient dataClient,
                              ResourceFactory factory,
                              @Assisted UUID bridgeId) {
        super(config, uriInfo, context, dataClient, validator);
        this.authorizer = authorizer;
        this.factory = factory;
        this.bridgeId = bridgeId;
    }

    /**
     * Host Assignments resource locator for dhcp.
     *
     * @return DhcpHostsResource object to handle sub-resource requests.
     */
    @Path("/{subnetAddr}" + ResourceUriBuilder.DHCP_HOSTS)
    public DhcpHostsResource getDhcpAssignmentsResource(
            @PathParam("subnetAddr") IntIPv4 subnetAddr) {
        return factory.getDhcpAssignmentsResource(bridgeId, subnetAddr);
    }

    /**
     * Handler for creating a DHCP subnet configuration.
     *
     * @param subnet
     *            DHCP subnet configuration object.
     * @throws StateAccessException
     *             Data access error.
     * @return Response object with 201 status code set if successful.
     */
    @POST
    @RolesAllowed({AuthRole.ADMIN, AuthRole.TENANT_ADMIN})
    @Consumes({ VendorMediaType.APPLICATION_DHCP_SUBNET_JSON,
            VendorMediaType.APPLICATION_DHCP_SUBNET_JSON_V2,
            MediaType.APPLICATION_JSON })
    public Response create(DhcpSubnet subnet)
            throws StateAccessException, SerializationException {

        if (!authorizer.authorize(context, AuthAction.WRITE, bridgeId)) {
            throw new ForbiddenHttpException(
                    "Not authorized to configure DHCP for this bridge.");
        }

        validate(subnet);

        dataClient.dhcpSubnetsCreate(bridgeId, subnet.toData());

        URI dhcpsUri =
            ResourceUriBuilder.getBridgeDhcps(getBaseUri(), bridgeId);

        IPv4Subnet subnetAddr = new IPv4Subnet(subnet.getSubnetPrefix(),
                                               subnet.getSubnetLength());

        return Response.created(ResourceUriBuilder.getBridgeDhcp(dhcpsUri, subnetAddr))
                       .build();
    }

    /**
     * Handler to updating a host assignment.
     *
     * @param subnetAddr
     *            Identifier of the DHCP subnet configuration.
     * @param subnet
     *            DHCP subnet configuration object.
     * @throws StateAccessException
     *             Data access error.
     */
    @PUT
    @RolesAllowed({AuthRole.ADMIN, AuthRole.TENANT_ADMIN})
    @Path("/{subnetAddr}")
    @Consumes({ VendorMediaType.APPLICATION_DHCP_SUBNET_JSON,
            VendorMediaType.APPLICATION_DHCP_SUBNET_JSON_V2,
            MediaType.APPLICATION_JSON })
    public Response update(@PathParam("subnetAddr") IntIPv4 subnetAddr,
            DhcpSubnet subnet)
            throws StateAccessException, SerializationException {

        if (!authorizer.authorize(context, AuthAction.WRITE, bridgeId)) {
            throw new ForbiddenHttpException(
                    "Not authorized to update this bridge's dhcp config.");
        }

        // Make sure that the DhcpSubnet has the same IP address as the URI.
        subnet.setSubnetPrefix(subnetAddr.toUnicastString());
        subnet.setSubnetLength(subnetAddr.getMaskLength());
        dataClient.dhcpSubnetsUpdate(bridgeId, subnet.toData());
        return Response.ok().build();
    }

    /**
     * Handler to getting a DHCP subnet configuration.
     *
     * @param subnetAddr
     *            Subnet IP from the request.
     * @throws StateAccessException
     *             Data access error.
     * @return A Bridge object.
     */
    @GET
    @PermitAll
    @Path("/{subnetAddr}")
    @Produces({ VendorMediaType.APPLICATION_DHCP_SUBNET_JSON,
            VendorMediaType.APPLICATION_DHCP_SUBNET_JSON_V2,
            MediaType.APPLICATION_JSON })
    public DhcpSubnet get(@PathParam("subnetAddr") IntIPv4 subnetAddr)
            throws StateAccessException, SerializationException {

        if (!authorizer.authorize(context, AuthAction.READ, bridgeId)) {
            throw new ForbiddenHttpException(
                    "Not authorized to view this bridge's dhcp config.");
        }

        Subnet subnetConfig =
            dataClient.dhcpSubnetsGet(bridgeId, IntIPv4.toIPv4Subnet(subnetAddr));

        if (subnetConfig == null) {
            throw new NotFoundHttpException(
                    "The requested resource was not found.");
        }

        DhcpSubnet subnet = new DhcpSubnet(subnetConfig);
        subnet.setParentUri(ResourceUriBuilder.getBridgeDhcps(getBaseUri(),
                bridgeId));

        return subnet;
    }

    /**
     * Handler to deleting a DHCP subnet configuration.
     *
     * @throws org.midonet.midolman.state.StateAccessException
     *             Data access error.
     */
    @DELETE
    @RolesAllowed({AuthRole.ADMIN, AuthRole.TENANT_ADMIN})
    @Path("/{subnetAddr}")
    public void delete(@PathParam("subnetAddr") IntIPv4 subnetAddr)
            throws StateAccessException, SerializationException {

        if (!authorizer.authorize(context, AuthAction.WRITE, bridgeId)) {
            throw new ForbiddenHttpException(
                    "Not authorized to delete dhcp configuration of "
                            + "this bridge.");
        }

        dataClient.dhcpSubnetsDelete(bridgeId, subnetAddr.toIPv4Subnet());
    }

    /**
     * Handler to list DHCP subnet configurations.
     *
     * @throws StateAccessException
     *             Data access error.
     * @return A list of DhcpSubnet objects.
     */
    @GET
    @PermitAll
    @Produces({ VendorMediaType.APPLICATION_DHCP_SUBNET_COLLECTION_JSON,
        VendorMediaType.APPLICATION_DHCP_SUBNET_COLLECTION_JSON_V2})
    public List<DhcpSubnet> list()
            throws StateAccessException, SerializationException {

        if (!authorizer.authorize(context, AuthAction.READ, bridgeId)) {
            throw new ForbiddenHttpException(
                    "Not authorized to view DHCP config of this bridge.");
        }

        List<Subnet> subnetConfigs =
                dataClient.dhcpSubnetsGetByBridge(bridgeId);

        List<DhcpSubnet> subnets = new ArrayList<>();
        URI dhcpsUri = ResourceUriBuilder.getBridgeDhcps(getBaseUri(),
                bridgeId);
        for (Subnet subnetConfig : subnetConfigs) {
            DhcpSubnet subnet = new DhcpSubnet(subnetConfig);
            subnet.setParentUri(dhcpsUri);
            subnets.add(subnet);
        }

        return subnets;
    }

}
