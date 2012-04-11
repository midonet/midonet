/*
 * Copyright 2012 Midokura Europe SARL
 * Copyright 2012 Midokura PTE LTD.
 */

package com.midokura.midolman.mgmt.rest_api.resources;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import javax.annotation.security.PermitAll;
import javax.annotation.security.RolesAllowed;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.auth.AuthAction;
import com.midokura.midolman.mgmt.auth.AuthRole;
import com.midokura.midolman.mgmt.auth.Authorizer;
import com.midokura.midolman.mgmt.data.DaoFactory;
import com.midokura.midolman.mgmt.data.dao.DhcpDao;
import com.midokura.midolman.mgmt.data.dto.DhcpHost;
import com.midokura.midolman.mgmt.data.dto.RelativeUriResource;
import com.midokura.midolman.mgmt.rest_api.core.ResourceUriBuilder;
import com.midokura.midolman.mgmt.rest_api.core.VendorMediaType;
import com.midokura.midolman.mgmt.rest_api.jaxrs.ForbiddenHttpException;
import com.midokura.midolman.packets.IntIPv4;
import com.midokura.midolman.state.NoStatePathException;
import com.midokura.midolman.state.StateAccessException;

public class DhcpHostsResource {
    private final static Logger log = LoggerFactory
            .getLogger(DhcpHostsResource.class);
    private final UUID bridgeId;
    private final IntIPv4 subnet;

    public DhcpHostsResource(UUID bridgeId, IntIPv4 subnet) {
        this.bridgeId = bridgeId;
        this.subnet = subnet;
    }

    /**
     * Handler for creating a DHCP host assignment.
     *
     * @param host
     *            DHCP host assignment object.
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
     * @returns Response object with 201 status code set if successful.
     */
    @POST
    @RolesAllowed({AuthRole.ADMIN, AuthRole.TENANT_ADMIN})
    @Consumes({ VendorMediaType.APPLICATION_DHCP_HOST_JSON,
            MediaType.APPLICATION_JSON })
    public Response create(DhcpHost host, @Context SecurityContext context,
            @Context UriInfo uriInfo, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException {

        if (!authorizer.bridgeAuthorized(context, AuthAction.WRITE, bridgeId)) {
            throw new ForbiddenHttpException(
                    "Not authorized to configure DHCP for this bridge.");
        }

        DhcpDao dao = daoFactory.getDhcpDao();
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
     * @param context
     *            Object that holds the security data.
     * @param uriInfo
     *            Object that holds the request URI data.
     * @param daoFactory
     *            Data access factory object.
     * @param authorizer
     *            Authorizer object.
     * @throws StateAccessException
     *             Data access error.
     * @return A DhcpHost object.
     */
    @GET
    @PermitAll
    @Path("/{mac}")
    @Produces({ VendorMediaType.APPLICATION_DHCP_HOST_JSON,
            MediaType.APPLICATION_JSON })
    public DhcpHost get(@PathParam("mac") String mac,
            @Context SecurityContext context, @Context UriInfo uriInfo,
            @Context DaoFactory daoFactory, @Context Authorizer authorizer)
            throws StateAccessException {

        if (!authorizer.bridgeAuthorized(context, AuthAction.READ, bridgeId)) {
            throw new ForbiddenHttpException(
                    "Not authorized to view this bridge's dhcp config.");
        }

        // The mac in the URI uses '-' instead of ':'
        mac = ResourceUriBuilder.macFromUri(mac);
        DhcpDao dao = daoFactory.getDhcpDao();
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
     * @param context
     *            Object that holds the security data.
     * @param daoFactory
     *            Data access factory object.
     * @param authorizer
     *            Authorizer object.
     * @throws StateAccessException
     *             Data access error.
     */
    @PUT
    @RolesAllowed({AuthRole.ADMIN, AuthRole.TENANT_ADMIN})
    @Path("/{mac}")
    @Consumes({ VendorMediaType.APPLICATION_DHCP_HOST_JSON,
            MediaType.APPLICATION_JSON })
    public Response update(@PathParam("mac") String mac, DhcpHost host,
            @Context SecurityContext context, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException {

        if (!authorizer.bridgeAuthorized(context, AuthAction.WRITE, bridgeId)) {
            throw new ForbiddenHttpException(
                    "Not authorized to update this bridge's dhcp config.");
        }

        // The mac in the URI uses '-' instead of ':'
        mac = ResourceUriBuilder.macFromUri(mac);
        // Make sure that the DhcpHost has the same mac address as the URI.
        host.setMacAddr(mac);
        DhcpDao dao = daoFactory.getDhcpDao();
        dao.updateHost(bridgeId, subnet, host);
        return Response.ok().build();
    }

    /**
     * Handler to deleting a DHCP host assignment.
     *
     * @param mac
     *            mac address of the host.
     * @param context
     *            Object that holds the security data.
     * @param daoFactory
     *            Data access factory object.
     * @param authorizer
     *            Authorizer object.
     * @throws com.midokura.midolman.state.StateAccessException
     *             Data access error.
     */
    @DELETE
    @RolesAllowed({AuthRole.ADMIN, AuthRole.TENANT_ADMIN})
    @Path("/{mac}")
    public void delete(@PathParam("mac") String mac,
            @Context SecurityContext context, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException {

        if (!authorizer.bridgeAuthorized(context, AuthAction.WRITE, bridgeId)) {
            throw new ForbiddenHttpException(
                    "Not authorized to delete dhcp configuration of "
                            + "this bridge.");
        }

        // The mac in the URI uses '-' instead of ':'
        mac = ResourceUriBuilder.macFromUri(mac);
        DhcpDao dao = daoFactory.getDhcpDao();
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
     * @param context
     *            Object that holds the security data.
     * @param uriInfo
     *            Object that holds the request URI data.
     * @param daoFactory
     *            Data access factory object.
     * @param authorizer
     *            Authorizer object.
     * @throws StateAccessException
     *             Data access error.
     * @return A list of DhcpHost objects.
     */
    @GET
    @PermitAll
    @Produces({ VendorMediaType.APPLICATION_DHCP_HOST_COLLECTION_JSON })
    public List<DhcpHost> list(@Context SecurityContext context,
            @Context UriInfo uriInfo, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException {

        if (!authorizer.bridgeAuthorized(context, AuthAction.READ, bridgeId)) {
            throw new ForbiddenHttpException(
                    "Not authorized to view DHCP config of this bridge.");
        }

        DhcpDao dao = daoFactory.getDhcpDao();
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
