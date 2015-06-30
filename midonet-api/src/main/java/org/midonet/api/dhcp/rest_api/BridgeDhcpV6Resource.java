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

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.annotation.security.PermitAll;
import javax.annotation.security.RolesAllowed;
import javax.validation.Validator;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.servlet.RequestScoped;

import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.dhcp.DhcpSubnet6;
import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.ResourceFactory;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.auth.AuthRole;
import org.midonet.cluster.data.dhcp.Subnet6;
import org.midonet.cluster.rest_api.NotFoundHttpException;
import org.midonet.cluster.rest_api.VendorMediaType;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.packets.IPv6Addr;
import org.midonet.packets.IPv6Subnet;

@RequestScoped
public class BridgeDhcpV6Resource extends AbstractResource {

    private final UUID bridgeId;
    private final ResourceFactory factory;

    @Inject
    public BridgeDhcpV6Resource(RestApiConfig config, UriInfo uriInfo,
                                SecurityContext context,
                                Validator validator,
                                DataClient dataClient,
                                ResourceFactory factory,
                                @Assisted UUID bridgeId) {
        super(config, uriInfo, context, dataClient, validator);
        this.factory = factory;
        this.bridgeId = bridgeId;
    }

    /**
     * Host Assignments resource locator for dhcp.
     *
     * @return DhcpV6HostsResource object to handle sub-resource requests.
     */
    @Path("/{prefix}" + ResourceUriBuilder.DHCPV6_HOSTS)
    public DhcpV6HostsResource getDhcpV6AssignmentsResource(
            @PathParam("prefix") IPv6Subnet prefix) {
        return factory.getDhcpV6AssignmentsResource(bridgeId, prefix);
    }

    /**
     * Handler for creating a DHCPV6 subnet configuration.
     *
     * @param subnet DHCPV6 subnet configuration object.
     * @throws StateAccessException Data access error.
     * @return Response object with 201 status code set if successful.
     */
    @POST
    @RolesAllowed({AuthRole.ADMIN, AuthRole.TENANT_ADMIN})
    @Consumes({ VendorMediaType.APPLICATION_DHCPV6_SUBNET_JSON,
                MediaType.APPLICATION_JSON })
    public Response create(DhcpSubnet6 subnet)
            throws StateAccessException, SerializationException {

        authoriser.tryAuthoriseBridge(bridgeId,
                                      "configure DHCPV6 for this bridge.");

        validate(subnet);

        dataClient.dhcpSubnet6Create(bridgeId, subnet.toData());

        URI dhcpsUri = ResourceUriBuilder.getBridgeDhcpV6s(getBaseUri(),
                bridgeId);
        return Response.created(
                ResourceUriBuilder.getBridgeDhcpV6(
                    dhcpsUri,
                    new IPv6Subnet(IPv6Addr.fromString(subnet.getPrefix()),
                                       subnet.getPrefixLength()))).build();
    }

    /**
     * Handler to updating a host assignment.
     *
     * @param prefix Identifier of the DHCPV6 subnet configuration.
     * @param subnet DHCPV6 subnet configuration object.
     * @throws StateAccessException Data access error.
     */
    @PUT
    @RolesAllowed({AuthRole.ADMIN, AuthRole.TENANT_ADMIN})
    @Path("/{prefix}")
    @Consumes({ VendorMediaType.APPLICATION_DHCPV6_SUBNET_JSON,
            MediaType.APPLICATION_JSON })
    public Response update(@PathParam("prefix") IPv6Subnet prefix,
            DhcpSubnet6 subnet)
            throws StateAccessException, SerializationException {

        authoriser.tryAuthoriseBridge(bridgeId,
                                      "update this bridge's dhcpv6 config.");

        // Make sure that the DhcpSubnet6 has the same IP address as the URI.
        subnet.setPrefix(prefix.getAddress().toString());
        subnet.setPrefixLength(prefix.getPrefixLen());
        dataClient.dhcpSubnet6Update(bridgeId, subnet.toData());
        return Response.ok().build();
    }

    /**
     * Handler to getting a DHCPV6 subnet configuration.
     *
     * @param prefix Subnet IP from the request.
     * @throws StateAccessException Data access error.
     * @return A Bridge object.
     */
    @GET
    @PermitAll
    @Path("/{prefix}")
    @Produces({ VendorMediaType.APPLICATION_DHCPV6_SUBNET_JSON,
            MediaType.APPLICATION_JSON })
    public DhcpSubnet6 get(@PathParam("prefix") IPv6Subnet prefix)
            throws StateAccessException, SerializationException {

        authoriser.tryAuthoriseBridge(bridgeId,
                                      "view this bridge's dhcpv6 config.");

        Subnet6 subnetConfig = dataClient.dhcpSubnet6Get(bridgeId, prefix);
        if (subnetConfig == null) {
            throw new NotFoundHttpException(
                    "The requested resource was not found.");
        }

        DhcpSubnet6 subnet = new DhcpSubnet6(subnetConfig);
        subnet.setParentUri(ResourceUriBuilder.getBridgeDhcpV6s(getBaseUri(),
                                                                bridgeId));

        return subnet;
    }

    /**
     * Handler to deleting a DHCPV6 subnet configuration.
     *
     * @throws StateAccessException Data access error.
     */
    @DELETE
    @RolesAllowed({AuthRole.ADMIN, AuthRole.TENANT_ADMIN})
    @Path("/{prefix}")
    public void delete(@PathParam("prefix") IPv6Subnet prefix)
            throws StateAccessException, SerializationException {

        authoriser.tryAuthoriseBridge(
            bridgeId, "delete dhcpv6 configuration of this bridge.");

        dataClient.dhcpSubnet6Delete(bridgeId, prefix);
    }

    /**
     * Handler to list DHCPV6 subnet configurations.
     *
     * @throws StateAccessException Data access error.
     * @return A list of DhcpSubnet6 objects.
     */
    @GET
    @PermitAll
    @Produces({ VendorMediaType.APPLICATION_DHCPV6_SUBNET_COLLECTION_JSON })
    public List<DhcpSubnet6> list()
            throws StateAccessException, SerializationException {

        authoriser.tryAuthoriseBridge(
            bridgeId, "view DHCPV6 config of this bridge.");

        List<Subnet6> subnetConfigs =
                dataClient.dhcpSubnet6sGetByBridge(bridgeId);

        List<DhcpSubnet6> subnets = new ArrayList<>();
        URI dhcpsUri = ResourceUriBuilder.getBridgeDhcpV6s(getBaseUri(),
                                                           bridgeId);
        for (Subnet6 subnetConfig : subnetConfigs) {
            DhcpSubnet6 subnet = new DhcpSubnet6(subnetConfig);
            subnet.setParentUri(dhcpsUri);
            subnets.add(subnet);
        }

        return subnets;
    }

}
