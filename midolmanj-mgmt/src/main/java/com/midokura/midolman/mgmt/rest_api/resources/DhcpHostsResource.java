/*
 * Copyright 2012 Midokura Europe SARL
 * Copyright 2012 Midokura PTE LTD.
 */

package com.midokura.midolman.mgmt.rest_api.resources;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.servlet.RequestScoped;
import com.midokura.midolman.mgmt.auth.AuthAction;
import com.midokura.midolman.mgmt.auth.AuthRole;
import com.midokura.midolman.mgmt.auth.authorizer.Authorizer;
import com.midokura.midolman.mgmt.auth.authorizer.BridgeAuthorizer;
import com.midokura.midolman.mgmt.data.dao.DhcpDao;
import com.midokura.midolman.mgmt.data.dto.DhcpHost;
import com.midokura.midolman.mgmt.data.dto.RelativeUriResource;
import com.midokura.midolman.mgmt.http.VendorMediaType;
import com.midokura.midolman.mgmt.jaxrs.ForbiddenHttpException;
import com.midokura.midolman.mgmt.jaxrs.ResourceUriBuilder;
import com.midokura.midolman.state.NoStatePathException;
import com.midokura.midolman.state.StateAccessException;
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
import java.util.List;
import java.util.UUID;

@RequestScoped
public class DhcpHostsResource {

    private final static Logger log = LoggerFactory
            .getLogger(DhcpHostsResource.class);

    private final UUID bridgeId;
    private final IntIPv4 subnet;
    private final SecurityContext context;
    private final UriInfo uriInfo;
    private final Authorizer authorizer;
    private final DhcpDao dao;

    @Inject
    public DhcpHostsResource(UriInfo uriInfo,
                             SecurityContext context,
                             BridgeAuthorizer authorizer,
                             DhcpDao dao,
                             @Assisted UUID bridgeId,
                             @Assisted IntIPv4 subnet) {
        this.context = context;
        this.uriInfo = uriInfo;
        this.authorizer = authorizer;
        this.dao = dao;
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

        dao.createHost(bridgeId, subnet, host);
        URI dhcpUri = ResourceUriBuilder.getBridgeDhcp(uriInfo.getBaseUri(),
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
        DhcpHost host = dao.getHost(bridgeId, subnet, mac);
        if (host != null) {
            host.setParentUri(ResourceUriBuilder.getBridgeDhcp(
                    uriInfo.getBaseUri(), bridgeId, subnet));
        }
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
        dao.updateHost(bridgeId, subnet, host);
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
        try {
            dao.deleteHost(bridgeId, subnet, mac);
        } catch (NoStatePathException e) {
            // Deleting a non-existing record is OK.
            log.warn("The resource does not exist", e);
        }
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

        List<DhcpHost> hosts = dao.getHosts(bridgeId, subnet);

        if (hosts != null) {
            URI dhcpUri = ResourceUriBuilder.getBridgeDhcp(
                    uriInfo.getBaseUri(), bridgeId, subnet);
            for (RelativeUriResource resource : hosts) {
                resource.setParentUri(dhcpUri);
            }
        }
        return hosts;
    }

}
