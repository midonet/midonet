/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.api.dhcp.rest_api;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.servlet.RequestScoped;
import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.VendorMediaType;
import org.midonet.api.auth.ForbiddenHttpException;
import org.midonet.api.dhcp.DhcpHost;
import org.midonet.api.network.auth.BridgeAuthorizer;
import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.NotFoundHttpException;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.api.auth.AuthAction;
import org.midonet.api.auth.AuthRole;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.data.dhcp.Host;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.IPv4Subnet;
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

    private final UUID bridgeId;
    private final IPv4Subnet subnet;
    private final BridgeAuthorizer authorizer;

    @Inject
    public DhcpHostsResource(RestApiConfig config, UriInfo uriInfo,
                             SecurityContext context,
                             BridgeAuthorizer authorizer,
                             DataClient dataClient,
                             @Assisted UUID bridgeId,
                             @Assisted IPv4Subnet subnet) {
        super(config, uriInfo, context, dataClient);
        this.authorizer = authorizer;
        this.bridgeId = bridgeId;
        this.subnet = subnet;
    }

    /**
     * Handler for creating a DHCP host assignment.
     *
     * @param host
     *            DHCP host assignment object.
     * @throws org.midonet.midolman.state.StateAccessException
     *             Data access error.
     * @returns Response object with 201 status code set if successful.
     */
    @POST
    @RolesAllowed({AuthRole.ADMIN, AuthRole.TENANT_ADMIN})
    @Consumes({ VendorMediaType.APPLICATION_DHCP_HOST_JSON,
                VendorMediaType.APPLICATION_DHCP_HOST_JSON_V2,
            MediaType.APPLICATION_JSON })
    public Response create(DhcpHost host)
            throws StateAccessException, SerializationException {

        if (!authorizer.authorize(context, AuthAction.WRITE, bridgeId)) {
            throw new ForbiddenHttpException(
                    "Not authorized to configure DHCP for this bridge.");
        }

        Host h = host.toData();
        dataClient.dhcpHostsCreate(bridgeId, subnet, h);
        // Update the Bridge's ARP table.
        dataClient.bridgeAddIp4Mac(bridgeId, h.getIp(), h.getMAC());
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
                VendorMediaType.APPLICATION_DHCP_HOST_JSON_V2,
            MediaType.APPLICATION_JSON })
    public DhcpHost get(@PathParam("mac") String mac)
            throws StateAccessException, SerializationException {

        if (!authorizer.authorize(context, AuthAction.READ, bridgeId)) {
            throw new ForbiddenHttpException(
                    "Not authorized to view this bridge's dhcp config.");
        }

        // The mac in the URI uses '-' instead of ':'
        mac = ResourceUriBuilder.macStrFromUri(mac);
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
                VendorMediaType.APPLICATION_DHCP_HOST_JSON_V2,
            MediaType.APPLICATION_JSON })
    public Response update(@PathParam("mac") String mac, DhcpHost host)
            throws StateAccessException, SerializationException {

        if (!authorizer.authorize(context, AuthAction.WRITE, bridgeId)) {
            throw new ForbiddenHttpException(
                    "Not authorized to update this bridge's dhcp config.");
        }

        // The mac in the URI uses '-' instead of ':'
        mac = ResourceUriBuilder.macStrFromUri(mac);
        // Make sure that the DhcpHost has the same mac address as the URI.
        host.setMacAddr(mac);

        // Get the old host info so it's not lost.
        Host oldHost = dataClient.dhcpHostsGet(bridgeId, subnet, mac);

        Host newHost = host.toData();
        dataClient.dhcpHostsUpdate(bridgeId, subnet, newHost);

        // Update the bridge's arp table.
        dataClient.bridgeDeleteIp4Mac(
            bridgeId, oldHost.getIp(), oldHost.getMAC());
        dataClient.bridgeAddIp4Mac(bridgeId, newHost.getIp(), newHost.getMAC());

        return Response.ok().build();
    }

    /**
     * Handler to deleting a DHCP host assignment.
     *
     * @param mac
     *            mac address of the host.
     * @throws org.midonet.midolman.state.StateAccessException
     *             Data access error.
     */
    @DELETE
    @RolesAllowed({AuthRole.ADMIN, AuthRole.TENANT_ADMIN})
    @Path("/{mac}")
    public void delete(@PathParam("mac") String mac)
            throws StateAccessException, SerializationException {

        if (!authorizer.authorize(context, AuthAction.WRITE, bridgeId)) {
            throw new ForbiddenHttpException(
                    "Not authorized to delete dhcp configuration of "
                            + "this bridge.");
        }

        // The mac in the URI uses '-' instead of ':'
        mac = ResourceUriBuilder.macStrFromUri(mac);
        // Get the old dhcp host assignment.
        Host h = dataClient.dhcpHostsGet(bridgeId, subnet, mac);
        dataClient.dhcpHostsDelete(bridgeId, subnet, mac);
        // Update the bridge's arp table.
        dataClient.bridgeDeleteIp4Mac(bridgeId, h.getIp(), h.getMAC());
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
    @Produces({ VendorMediaType.APPLICATION_DHCP_HOST_COLLECTION_JSON,
                VendorMediaType.APPLICATION_DHCP_HOST_COLLECTION_JSON_V2})
    public List<DhcpHost> list()
            throws StateAccessException, SerializationException {

        if (!authorizer.authorize(context, AuthAction.READ, bridgeId)) {
            throw new ForbiddenHttpException(
                    "Not authorized to view DHCP config of this bridge.");
        }

        List<Host> hostConfigs = dataClient.dhcpHostsGetBySubnet(bridgeId, subnet);
        List<DhcpHost> hosts = new ArrayList<>();
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
