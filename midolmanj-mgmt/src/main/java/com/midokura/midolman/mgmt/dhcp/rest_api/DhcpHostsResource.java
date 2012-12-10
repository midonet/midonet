/*
 * Copyright 2012 Midokura Europe SARL
 * Copyright 2012 Midokura PTE LTD.
 */

package com.midokura.midolman.mgmt.dhcp.rest_api;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.servlet.RequestScoped;
import com.midokura.midolman.mgmt.ResourceUriBuilder;
import com.midokura.midolman.mgmt.VendorMediaType;
import com.midokura.midolman.mgmt.auth.AuthAction;
import com.midokura.midolman.mgmt.auth.AuthRole;
import com.midokura.midolman.mgmt.auth.Authorizer;
import com.midokura.midolman.mgmt.auth.ForbiddenHttpException;
import com.midokura.midolman.mgmt.dhcp.DhcpHost;
import com.midokura.midolman.mgmt.network.auth.BridgeAuthorizer;
import com.midokura.midolman.mgmt.rest_api.AbstractResource;
import com.midokura.midolman.mgmt.rest_api.NotFoundHttpException;
import com.midokura.midolman.mgmt.rest_api.RestApiConfig;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midonet.cluster.DataClient;
import com.midokura.midonet.cluster.data.dhcp.Host;
import com.midokura.packets.IntIPv4;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.security.PermitAll;
import javax.annotation.security.RolesAllowed;
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
public class DhcpHostsResource extends AbstractResource {

    private final static Logger log = LoggerFactory
            .getLogger(DhcpHostsResource.class);

    private final UUID bridgeId;
    private final IntIPv4 subnet;
    private final Authorizer authorizer;
    private final DataClient dataClient;

    @Inject
    public DhcpHostsResource(RestApiConfig config, UriInfo uriInfo,
                             SecurityContext context,
                             BridgeAuthorizer authorizer,
                             DataClient dataClient,
                             @Assisted UUID bridgeId,
                             @Assisted IntIPv4 subnet) {
        super(config, uriInfo, context);
        this.authorizer = authorizer;
        this.dataClient = dataClient;
        this.bridgeId = bridgeId;
        this.subnet = subnet;
    }

    /**
     * Handler for creating a DHCP host assignment.
     *
     * @param host
     *            DHCP host assignment object.
     * @throws com.midokura.midolman.state.StateAccessException
     *             Data access error.
     * @returns Response object with 201 status code set if successful.
     */
    @POST
    @RolesAllowed({AuthRole.ADMIN, AuthRole.TENANT_ADMIN})
    @Consumes({ VendorMediaType.APPLICATION_DHCP_HOST_JSON,
            MediaType.APPLICATION_JSON })
    public Response create(DhcpHost host) throws StateAccessException {

        if (!authorizer.authorize(context, AuthAction.WRITE, bridgeId)) {
            throw new ForbiddenHttpException(
                    "Not authorized to configure DHCP for this bridge.");
        }

        dataClient.dhcpHostsCreate(bridgeId, subnet, host.toData());
        URI dhcpUri = ResourceUriBuilder.getBridgeDhcp(getBaseUri(),
                bridgeId, subnet);
        return Response.created(
                ResourceUriBuilder.getDhcpHost(dhcpUri, host.getMacAddr()))
                .build();
    }

    /**
     * Handler to getting a DHCP host assignment.
     *
     * @param mac
     *            mac address of the host.
     * @throws StateAccessException
     *             Data access error.
     * @return A DhcpHost object.
     */
    @GET
    @PermitAll
    @Path("/{mac}")
    @Produces({ VendorMediaType.APPLICATION_DHCP_HOST_JSON,
            MediaType.APPLICATION_JSON })
    public DhcpHost get(@PathParam("mac") String mac)
            throws StateAccessException {

        if (!authorizer.authorize(context, AuthAction.READ, bridgeId)) {
            throw new ForbiddenHttpException(
                    "Not authorized to view this bridge's dhcp config.");
        }

        // The mac in the URI uses '-' instead of ':'
        mac = ResourceUriBuilder.macFromUri(mac);
        Host hostConfig = dataClient.dhcpHostsGet(bridgeId, subnet, mac);
        if (hostConfig == null) {
            throw new NotFoundHttpException(
                    "The requested resource was not found.");
        }

        DhcpHost host = new DhcpHost(hostConfig);
        host.setParentUri(ResourceUriBuilder.getBridgeDhcp(
              getBaseUri(), bridgeId, subnet));

        return host;
    }

    /**
     * Handler to updating a host assignment.
     *
     * @param mac
     *            mac address of the host.
     * @param host
     *            Host assignment object.
     * @throws StateAccessException
     *             Data access error.
     */
    @PUT
    @RolesAllowed({AuthRole.ADMIN, AuthRole.TENANT_ADMIN})
    @Path("/{mac}")
    @Consumes({ VendorMediaType.APPLICATION_DHCP_HOST_JSON,
            MediaType.APPLICATION_JSON })
    public Response update(@PathParam("mac") String mac, DhcpHost host)
            throws StateAccessException {

        if (!authorizer.authorize(context, AuthAction.WRITE, bridgeId)) {
            throw new ForbiddenHttpException(
                    "Not authorized to update this bridge's dhcp config.");
        }

        // The mac in the URI uses '-' instead of ':'
        mac = ResourceUriBuilder.macFromUri(mac);
        // Make sure that the DhcpHost has the same mac address as the URI.
        host.setMacAddr(mac);
        dataClient.dhcpHostsUpdate(bridgeId, subnet, host.toData());
        return Response.ok().build();
    }

    /**
     * Handler to deleting a DHCP host assignment.
     *
     * @param mac
     *            mac address of the host.
     * @throws com.midokura.midolman.state.StateAccessException
     *             Data access error.
     */
    @DELETE
    @RolesAllowed({AuthRole.ADMIN, AuthRole.TENANT_ADMIN})
    @Path("/{mac}")
    public void delete(@PathParam("mac") String mac)
            throws StateAccessException {

        if (!authorizer.authorize(context, AuthAction.WRITE, bridgeId)) {
            throw new ForbiddenHttpException(
                    "Not authorized to delete dhcp configuration of "
                            + "this bridge.");
        }

        // The mac in the URI uses '-' instead of ':'
        mac = ResourceUriBuilder.macFromUri(mac);
        dataClient.dhcpHostsDelete(bridgeId, subnet, mac);
    }

    /**
     * Handler to list DHCP host assignments.
     *
     * @throws StateAccessException
     *             Data access error.
     * @return A list of DhcpHost objects.
     */
    @GET
    @PermitAll
    @Produces({ VendorMediaType.APPLICATION_DHCP_HOST_COLLECTION_JSON })
    public List<DhcpHost> list() throws StateAccessException {

        if (!authorizer.authorize(context, AuthAction.READ, bridgeId)) {
            throw new ForbiddenHttpException(
                    "Not authorized to view DHCP config of this bridge.");
        }

        List<Host> hostConfigs = dataClient.dhcpHostsGetBySubnet(bridgeId,
                subnet);
        List<DhcpHost> hosts = new ArrayList<DhcpHost>();
        URI dhcpUri = ResourceUriBuilder.getBridgeDhcp(
                getBaseUri(), bridgeId, subnet);
        for (Host hostConfig : hostConfigs) {
            DhcpHost host = new DhcpHost(hostConfig);
            host.setParentUri(dhcpUri);
            hosts.add(host);
        }
        return hosts;
    }

}
