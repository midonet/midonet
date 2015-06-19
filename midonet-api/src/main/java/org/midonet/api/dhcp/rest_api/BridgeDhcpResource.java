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
import org.midonet.api.auth.AuthRole;
import org.midonet.api.dhcp.DhcpSubnet;
import org.midonet.api.rest_api.AbstractResource;
import org.midonet.cluster.rest_api.NotFoundHttpException;
import org.midonet.api.rest_api.ResourceFactory;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.data.dhcp.Subnet;
import org.midonet.cluster.rest_api.VendorMediaType;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.packets.IPv4Subnet;

@RequestScoped
public class BridgeDhcpResource extends AbstractResource {

    private final UUID bridgeId;
    private final ResourceFactory factory;

    @Inject
    public BridgeDhcpResource(RestApiConfig config, UriInfo uriInfo,
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
     * @return DhcpHostsResource object to handle sub-resource requests.
     */
    @Path("/{subnetAddr}" + ResourceUriBuilder.DHCP_HOSTS)
    public DhcpHostsResource getDhcpAssignmentsResource(
            @PathParam("subnetAddr") IPv4Subnet subnetAddr) {
        return factory.getDhcpAssignmentsResource(bridgeId, subnetAddr);
    }

    /**
     * Handler for creating a DHCP subnet configuration.
     *
     * @param subnet DHCP subnet configuration object.
     * @throws StateAccessException Data access error.
     * @return Response object with 201 status code set if successful.
     */
    @POST
    @RolesAllowed({AuthRole.ADMIN, AuthRole.TENANT_ADMIN})
    @Consumes({ VendorMediaType.APPLICATION_DHCP_SUBNET_JSON,
                VendorMediaType.APPLICATION_DHCP_SUBNET_JSON_V2,
                MediaType.APPLICATION_JSON })
    public Response create(DhcpSubnet subnet) throws StateAccessException,
                                                     SerializationException {

        authoriser.tryAuthoriseBridge(bridgeId,
                                      "configure DHCP for this bridge.");

        validate(subnet);

        dataClient.dhcpSubnetsCreate(bridgeId, subnet.toData());

        URI dhcpsUri = ResourceUriBuilder.getBridgeDhcps(getBaseUri(),
                                                         bridgeId);

        IPv4Subnet subnetAddr = new IPv4Subnet(subnet.getSubnetPrefix(),
                                               subnet.getSubnetLength());

        return Response.created(ResourceUriBuilder.getBridgeDhcp(dhcpsUri,
                                                                 subnetAddr))
                       .build();
    }

    /**
     * Handler to updating a host assignment.
     *
     * @param subnetAddr Identifier of the DHCP subnet configuration.
     * @param subnet DHCP subnet configuration object.
     * @throws StateAccessException Data access error.
     */
    @PUT
    @RolesAllowed({AuthRole.ADMIN, AuthRole.TENANT_ADMIN})
    @Path("/{subnetAddr}")
    @Consumes({ VendorMediaType.APPLICATION_DHCP_SUBNET_JSON,
                VendorMediaType.APPLICATION_DHCP_SUBNET_JSON_V2,
                MediaType.APPLICATION_JSON })
    public Response update(@PathParam("subnetAddr") IPv4Subnet subnetAddr,
            DhcpSubnet subnet) throws StateAccessException,
                                      SerializationException {

        authoriser.tryAuthoriseBridge(bridgeId,
                                      "update this bridge's dhcp config.");

        // Make sure that the DhcpSubnet has the same IP address as the URI.
        subnet.setSubnetPrefix(subnetAddr.toUnicastString());
        subnet.setSubnetLength(subnetAddr.getPrefixLen());
        dataClient.dhcpSubnetsUpdate(bridgeId, subnet.toData());
        return Response.noContent().build();
    }

    /**
     * Handler to getting a DHCP subnet configuration.
     *
     * @param subnetAddr Subnet IP from the request.
     * @throws StateAccessException Data access error.
     * @return A Bridge object.
     */
    @GET
    @PermitAll
    @Path("/{subnetAddr}")
    @Produces({ VendorMediaType.APPLICATION_DHCP_SUBNET_JSON,
            VendorMediaType.APPLICATION_DHCP_SUBNET_JSON_V2,
            MediaType.APPLICATION_JSON })
    public DhcpSubnet get(@PathParam("subnetAddr") IPv4Subnet subnetAddr)
            throws StateAccessException, SerializationException {

        authoriser.tryAuthoriseBridge(bridgeId,
                                      "view this bridge's dhcp config.");

        Subnet subnetConfig = dataClient.dhcpSubnetsGet(bridgeId, subnetAddr);

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
     * @throws StateAccessException Data access error.
     */
    @DELETE
    @RolesAllowed({AuthRole.ADMIN, AuthRole.TENANT_ADMIN})
    @Path("/{subnetAddr}")
    public void delete(@PathParam("subnetAddr") IPv4Subnet subnetAddr)
            throws StateAccessException, SerializationException {
        authoriser.tryAuthoriseBridge(bridgeId,
                                      "delete dhcp configuration of bridge.");
        dataClient.dhcpSubnetsDelete(bridgeId, subnetAddr);
    }

    /**
     * Handler to list DHCP subnet configurations.
     *
     * @throws StateAccessException Data access error.
     * @return A list of DhcpSubnet objects.
     */
    @GET
    @PermitAll
    @Produces({ VendorMediaType.APPLICATION_DHCP_SUBNET_COLLECTION_JSON,
        VendorMediaType.APPLICATION_DHCP_SUBNET_COLLECTION_JSON_V2})
    public List<DhcpSubnet> list()
            throws StateAccessException, SerializationException {

        authoriser.tryAuthoriseBridge(bridgeId,
                                      "view DHCP config of this bridge.");

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
