/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midolman.mgmt.rest_api.resources;

import java.net.URI;
import java.util.List;
import java.util.UUID;
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
import com.midokura.midolman.mgmt.auth.Authorizer;
import com.midokura.midolman.mgmt.auth.UnauthorizedException;
import com.midokura.midolman.mgmt.data.DaoFactory;
import com.midokura.midolman.mgmt.data.dao.DhcpDao;
import com.midokura.midolman.mgmt.data.dto.DhcpSubnet;
import com.midokura.midolman.mgmt.data.dto.RelativeUriResource;
import com.midokura.midolman.mgmt.rest_api.core.ResourceUriBuilder;
import com.midokura.midolman.mgmt.rest_api.core.VendorMediaType;
import com.midokura.midolman.mgmt.rest_api.jaxrs.UnknownRestApiException;
import com.midokura.midolman.packets.IntIPv4;
import com.midokura.midolman.state.NoStatePathException;
import com.midokura.midolman.state.StateAccessException;

public class BridgeDhcpResource {

    private final static Logger log = LoggerFactory
            .getLogger(BridgeDhcpResource.class);
    private final UUID bridgeId;

    public BridgeDhcpResource(UUID bridgeId) {
        this.bridgeId = bridgeId;
    }

    /**
     * Host Assignments resource locator for dhcp.
     *
     * @returns DhcpHostsResource object to handle sub-resource requests.
     */
    @Path("/{subnetAddr}" + ResourceUriBuilder.DHCP_HOSTS)
    public DhcpHostsResource getDhcpAssignmentsResource(
            @PathParam("subnetAddr") IntIPv4 subnetAddr) {
        return new DhcpHostsResource(bridgeId, subnetAddr);
    }

    /**
     * Handler for creating a DHCP subnet configuration.
     *
     * @param subnet
     *            DHCP subnet configuration object.
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
     * @throws UnauthorizedException
     *             Authentication/authorization error.
     * @returns Response object with 201 status code set if successful.
     */
    @POST
    @Consumes({ VendorMediaType.APPLICATION_DHCP_SUBNET_JSON,
            MediaType.APPLICATION_JSON })
    public Response create(DhcpSubnet subnet, @Context SecurityContext context,
                           @Context UriInfo uriInfo, @Context DaoFactory daoFactory,
                           @Context Authorizer authorizer) throws StateAccessException,
            UnauthorizedException {

        log.debug("!!!! subnet: " + subnet);
        DhcpDao dao = daoFactory.getDhcpDao();
        try {
            if (!authorizer.bridgeAuthorized(context, AuthAction.WRITE,
                    bridgeId)) {
                throw new UnauthorizedException(
                        "Not authorized to configure DHCP for this bridge.");
            }
            dao.createSubnet(bridgeId, subnet);
        } catch (StateAccessException e) {
            log.error("StateAccessException error.");
            throw e;
        } catch (UnauthorizedException e) {
            log.error("UnauthorizedException error.");
            throw e;
        } catch (Exception e) {
            log.error("Unhandled error.");
            throw new UnknownRestApiException(e);
        }
        URI dhcpsUri = ResourceUriBuilder.getBridgeDhcps(
                uriInfo.getBaseUri(), bridgeId);
        return Response.created(ResourceUriBuilder.getBridgeDhcp(
                dhcpsUri, IntIPv4.fromString(subnet.getSubnetPrefix(),
                    subnet.getSubnetLength())))
                .build();
    }

    /**
     * Handler to updating a host assignment.
     *
     * @param subnetAddr
     *            Identifier of the DHCP subnet configuration.
     * @param subnet
     *            DHCP subnet configuration object.
     * @param context
     *            Object that holds the security data.
     * @param daoFactory
     *            Data access factory object.
     * @param authorizer
     *            Authorizer object.
     * @throws StateAccessException
     *             Data access error.
     * @throws UnauthorizedException
     *             Authentication/authorization error.
     */
    @PUT
    @Path("/{subnetAddr}")
    @Consumes({ VendorMediaType.APPLICATION_DHCP_SUBNET_JSON,
            MediaType.APPLICATION_JSON })
    public Response update(@PathParam("subnetAddr") IntIPv4 subnetAddr,
            DhcpSubnet subnet, @Context SecurityContext context,
            @Context DaoFactory daoFactory, @Context Authorizer authorizer)
            throws StateAccessException, UnauthorizedException {

        DhcpDao dao = daoFactory.getDhcpDao();
        // Make sure that the DhcpSubnet has the same IP address as the URI.
        subnet.setSubnetPrefix(subnetAddr.toUnicastString());
        subnet.setSubnetLength(subnetAddr.getMaskLength());
        try {
            if (!authorizer.bridgeAuthorized(context, AuthAction.WRITE,
                    bridgeId)) {
                throw new UnauthorizedException(
                        "Not authorized to update this bridge's dhcp config.");
            }
            dao.updateSubnet(bridgeId, subnet);
        } catch (StateAccessException e) {
            log.error("StateAccessException error.");
            throw e;
        } catch (UnauthorizedException e) {
            log.error("UnauthorizedException error.");
            throw e;
        } catch (Exception e) {
            log.error("Unhandled error.");
            throw new UnknownRestApiException(e);
        }
        return Response.ok().build();
    }

    /**
     * Handler to getting a DHCP subnet configuration.
     *
     * @param subnetAddr
     *            Subnet IP from the request.
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
     * @throws UnauthorizedException
     *             Authentication/authorization error.
     * @return A Bridge object.
     */
    @GET
    @Path("/{subnetAddr}")
    @Produces({ VendorMediaType.APPLICATION_DHCP_SUBNET_JSON,
            MediaType.APPLICATION_JSON })
    public DhcpSubnet get(@PathParam("subnetAddr") IntIPv4 subnetAddr,
            @Context SecurityContext context,
            @Context UriInfo uriInfo, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer)
            throws StateAccessException, UnauthorizedException {

        DhcpDao dao = daoFactory.getDhcpDao();
        DhcpSubnet subnet;
        try {
            if (!authorizer.bridgeAuthorized(context, AuthAction.READ,
                    bridgeId)) {
                throw new UnauthorizedException(
                        "Not authorized to view this bridge's dhcp config.");
            }
            subnet = dao.getSubnet(bridgeId, subnetAddr);
        } catch (StateAccessException e) {
            log.error("StateAccessException error.");
            throw e;
        } catch (UnauthorizedException e) {
            log.error("UnauthorizedException error.");
            throw e;
        } catch (Exception e) {
            log.error("Unhandled error.");
            throw new UnknownRestApiException(e);
        }
        if (null != subnet)
            subnet.setParentUri(ResourceUriBuilder.getBridgeDhcps(
                uriInfo.getBaseUri(), bridgeId));
        return subnet;
    }

    /**
     * Handler to deleting a DHCP subnet configuration.
     *
     * @param context
     *            Object that holds the security data.
     * @param daoFactory
     *            Data access factory object.
     * @param authorizer
     *            Authorizer object.
     * @throws com.midokura.midolman.state.StateAccessException
     *             Data access error.
     * @throws com.midokura.midolman.mgmt.auth.UnauthorizedException
     *             Authentication/authorization error.
     */
    @DELETE
    @Path("/{subnetAddr}")
    public void delete(@PathParam("subnetAddr") IntIPv4 subnetAddr,
            @Context SecurityContext context,
            @Context DaoFactory daoFactory, @Context Authorizer authorizer)
            throws StateAccessException, UnauthorizedException {

        DhcpDao dao = daoFactory.getDhcpDao();
        try {
            if (!authorizer.bridgeAuthorized(context, AuthAction.WRITE,
                    bridgeId)) {
                throw new UnauthorizedException(
                        "Not authorized to delete dhcp configuration of " +
                                "this bridge.");
            }
            dao.deleteSubnet(bridgeId, subnetAddr);
        } catch (NoStatePathException e) {
            // Deleting a non-existing record is OK.
            log.warn("The resource does not exist", e);
        } catch (StateAccessException e) {
            log.error("StateAccessException error.");
            throw e;
        } catch (UnauthorizedException e) {
            log.error("UnauthorizedException error.");
            throw e;
        } catch (Exception e) {
            log.error("Unhandled error.");
            throw new UnknownRestApiException(e);
        }
    }

    /**
     * Handler to list DHCP subnet configurations.
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
     * @throws UnauthorizedException
     *             Authentication/authorization error.
     * @return A list of DhcpSubnet objects.
     */
    @GET
    @Produces({ VendorMediaType.APPLICATION_DHCP_SUBNET_COLLECTION_JSON })
    public List<DhcpSubnet> list(@Context SecurityContext context,
            @Context UriInfo uriInfo, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException,
            UnauthorizedException {

        DhcpDao dao = daoFactory.getDhcpDao();
        List<DhcpSubnet> subnets = null;
        try {
            if (!authorizer
                    .bridgeAuthorized(context, AuthAction.READ, bridgeId)) {
                throw new UnauthorizedException(
                        "Not authorized to view DHCP config of this bridge.");
            }
            subnets = dao.getSubnets(bridgeId);
        } catch (StateAccessException e) {
            log.error("StateAccessException error.");
            throw e;
        } catch (UnauthorizedException e) {
            log.error("UnauthorizedException error.");
            throw e;
        } catch (Exception e) {
            log.error("Unhandled error.");
            throw new UnknownRestApiException(e);
        }
        URI dhcpsUri = ResourceUriBuilder.getBridgeDhcps(
                uriInfo.getBaseUri(), bridgeId);
        for (RelativeUriResource resource : subnets) {
            resource.setParentUri(dhcpsUri);
        }
        return subnets;
    }

 }
