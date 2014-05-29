/*
 * Copyright (c) 2012-2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.api.dhcp.rest_api;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.servlet.RequestScoped;
import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.VendorMediaType;
import org.midonet.api.auth.ForbiddenHttpException;
import org.midonet.api.dhcp.DhcpV6Host;
import org.midonet.api.network.auth.BridgeAuthorizer;
import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.NotFoundHttpException;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.api.auth.AuthAction;
import org.midonet.api.auth.AuthRole;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.data.dhcp.V6Host;
import org.midonet.packets.IPv6Subnet;
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
public class DhcpV6HostsResource extends AbstractResource {

    private final static Logger log = LoggerFactory
            .getLogger(DhcpV6HostsResource.class);

    private final UUID bridgeId;
    private final IPv6Subnet prefix;
    private final BridgeAuthorizer authorizer;

    @Inject
    public DhcpV6HostsResource(RestApiConfig config, UriInfo uriInfo,
                               SecurityContext context,
                               BridgeAuthorizer authorizer,
                               DataClient dataClient,
                               @Assisted UUID bridgeId,
                               @Assisted IPv6Subnet prefix) {
        super(config, uriInfo, context, dataClient);
        this.authorizer = authorizer;
        this.bridgeId = bridgeId;
        this.prefix = prefix;
    }

    /**
     * Handler for creating a DHCPV6 host assignment.
     *
     * @param host
     *            DHCPV6 host assignment object.
     * @throws org.midonet.midolman.state.StateAccessException
     *             Data access error.
     * @return Response object with 201 status code set if successful.
     */
    @POST
    @RolesAllowed({AuthRole.ADMIN, AuthRole.TENANT_ADMIN})
    @Consumes({ VendorMediaType.APPLICATION_DHCPV6_HOST_JSON,
            MediaType.APPLICATION_JSON })
    public Response create(DhcpV6Host host)
            throws StateAccessException, SerializationException {

        if (!authorizer.authorize(context, AuthAction.WRITE, bridgeId)) {
            throw new ForbiddenHttpException(
                    "Not authorized to configure DHCP for this bridge.");
        }

        dataClient.dhcpV6HostCreate(bridgeId, prefix, host.toData());
        URI dhcpUri = ResourceUriBuilder.getBridgeDhcpV6(getBaseUri(),
                bridgeId, prefix);
        return Response.created(
                ResourceUriBuilder.getDhcpV6Host(dhcpUri, host.getClientId()))
                .build();
    }

    /**
     * Handler to getting a DHCPV6 host assignment.
     *
     * @param clientId
     *            clientId of the host.
     * @throws StateAccessException
     *             Data access error.
     * @return A DhcpV6Host object.
     */
    @GET
    @PermitAll
    @Path("/{clientId}")
    @Produces({ VendorMediaType.APPLICATION_DHCPV6_HOST_JSON,
            MediaType.APPLICATION_JSON })
    public DhcpV6Host get(@PathParam("clientId") String clientId)
            throws StateAccessException, SerializationException {

        if (!authorizer.authorize(context, AuthAction.READ, bridgeId)) {
            throw new ForbiddenHttpException(
                    "Not authorized to view this bridge's dhcpV6 config.");
        }

        // The clientId in the URI uses '-' instead of ':'
        clientId = ResourceUriBuilder.clientIdFromUri(clientId);
        V6Host hostConfig = dataClient.dhcpV6HostGet(bridgeId, prefix, clientId);
        if (hostConfig == null) {
            throw new NotFoundHttpException(
                    "The requested resource was not found.");
        }

        DhcpV6Host host = new DhcpV6Host(hostConfig);
        host.setParentUri(ResourceUriBuilder.getBridgeDhcpV6(
              getBaseUri(), bridgeId, prefix));

        return host;
    }

    /**
     * Handler to updating a host assignment.
     *
     * @param clientId
     *            client ID of the host.
     * @param host
     *            Host assignment object.
     * @throws StateAccessException
     *             Data access error.
     */
    @PUT
    @RolesAllowed({AuthRole.ADMIN, AuthRole.TENANT_ADMIN})
    @Path("/{clientId}")
    @Consumes({ VendorMediaType.APPLICATION_DHCPV6_HOST_JSON,
            MediaType.APPLICATION_JSON })
    public Response update(@PathParam("clientId") String clientId, DhcpV6Host host)
            throws StateAccessException, SerializationException {

        if (!authorizer.authorize(context, AuthAction.WRITE, bridgeId)) {
            throw new ForbiddenHttpException(
                    "Not authorized to update this bridge's dhcpV6 config.");
        }

        // The clientId in the URI uses '-' instead of ':'
        clientId = ResourceUriBuilder.clientIdFromUri(clientId);
        // Make sure that the DhcpV6Host has the same clientId address as the URI.
        host.setClientId(clientId);
        dataClient.dhcpV6HostUpdate(bridgeId, prefix, host.toData());
        return Response.ok().build();
    }

    /**
     * Handler to deleting a DHCP host assignment.
     *
     * @param clientId
     *            clientId address of the host.
     * @throws org.midonet.midolman.state.StateAccessException
     *             Data access error.
     */
    @DELETE
    @RolesAllowed({AuthRole.ADMIN, AuthRole.TENANT_ADMIN})
    @Path("/{clientId}")
    public void delete(@PathParam("clientId") String clientId)
            throws StateAccessException, SerializationException {

        if (!authorizer.authorize(context, AuthAction.WRITE, bridgeId)) {
            throw new ForbiddenHttpException(
                    "Not authorized to delete dhcpV6 configuration of "
                            + "this bridge.");
        }

        // The clientId in the URI uses '-' instead of ':'
        clientId = ResourceUriBuilder.clientIdFromUri(clientId);
        dataClient.dhcpV6HostDelete(bridgeId, prefix, clientId);
    }

    /**
     * Handler to list DHCPV6 host assignments.
     *
     * @throws StateAccessException
     *             Data access error.
     * @return A list of DhcpV6Host objects.
     */
    @GET
    @PermitAll
    @Produces({ VendorMediaType.APPLICATION_DHCPV6_HOST_COLLECTION_JSON })
    public List<DhcpV6Host> list()
            throws StateAccessException, SerializationException {

        if (!authorizer.authorize(context, AuthAction.READ, bridgeId)) {
            throw new ForbiddenHttpException(
                    "Not authorized to view DHCPV6 config of this bridge.");
        }

        List<V6Host> hostConfigs = dataClient.dhcpV6HostsGetByPrefix(bridgeId,
                prefix);
        List<DhcpV6Host> hosts = new ArrayList<>();
        URI dhcpUri = ResourceUriBuilder.getBridgeDhcpV6(
                getBaseUri(), bridgeId, prefix);
        for (V6Host hostConfig : hostConfigs) {
            DhcpV6Host host = new DhcpV6Host(hostConfig);
            host.setParentUri(dhcpUri);
            hosts.add(host);
        }
        return hosts;
    }

}
