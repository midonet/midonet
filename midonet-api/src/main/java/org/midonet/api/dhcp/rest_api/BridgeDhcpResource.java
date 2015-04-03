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

import org.midonet.api.dhcp.DhcpSubnet;
import org.midonet.api.network.auth.BridgeAuthorizer;
import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.ResourceFactory;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.brain.services.rest_api.ResourceUriBuilder;
import org.midonet.brain.services.rest_api.VendorMediaType;
import org.midonet.brain.services.rest_api.auth.AuthAction;
import org.midonet.brain.services.rest_api.auth.AuthRole;
import org.midonet.brain.services.rest_api.auth.ForbiddenHttpException;
import org.midonet.brain.services.rest_api.rest_api.NotFoundHttpException;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.backend.zookeeper.StateAccessException;
import org.midonet.cluster.data.dhcp.Subnet;
import org.midonet.packets.IPv4Subnet;
import org.midonet.util.serialization.SerializationException;

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

    @Path("/{subnetAddr}" + ResourceUriBuilder.DHCP_HOSTS)
    public DhcpHostsResource getDhcpAssignmentsResource(
            @PathParam("subnetAddr") IPv4Subnet subnetAddr) {
        return factory.getDhcpAssignmentsResource(bridgeId, subnetAddr);
    }

    /**
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

    @PUT
    @RolesAllowed({AuthRole.ADMIN, AuthRole.TENANT_ADMIN})
    @Path("/{subnetAddr}")
    @Consumes({ VendorMediaType.APPLICATION_DHCP_SUBNET_JSON,
            VendorMediaType.APPLICATION_DHCP_SUBNET_JSON_V2,
            MediaType.APPLICATION_JSON })
    public Response update(@PathParam("subnetAddr") IPv4Subnet subnetAddr,
            DhcpSubnet subnet)
            throws StateAccessException, SerializationException {

        if (!authorizer.authorize(context, AuthAction.WRITE, bridgeId)) {
            throw new ForbiddenHttpException(
                    "Not authorized to update this bridge's dhcp config.");
        }

        // Make sure that the DhcpSubnet has the same IP address as the URI.
        subnet.setSubnetPrefix(subnetAddr.toUnicastString());
        subnet.setSubnetLength(subnetAddr.getPrefixLen());
        dataClient.dhcpSubnetsUpdate(bridgeId, subnet.toData());
        return Response.ok().build();
    }

    @GET
    @PermitAll
    @Path("/{subnetAddr}")
    @Produces({ VendorMediaType.APPLICATION_DHCP_SUBNET_JSON,
            VendorMediaType.APPLICATION_DHCP_SUBNET_JSON_V2,
            MediaType.APPLICATION_JSON })
    public DhcpSubnet get(@PathParam("subnetAddr") IPv4Subnet subnetAddr)
            throws StateAccessException, SerializationException {

        if (!authorizer.authorize(context, AuthAction.READ, bridgeId)) {
            throw new ForbiddenHttpException(
                    "Not authorized to view this bridge's dhcp config.");
        }

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

    @DELETE
    @RolesAllowed({AuthRole.ADMIN, AuthRole.TENANT_ADMIN})
    @Path("/{subnetAddr}")
    public void delete(@PathParam("subnetAddr") IPv4Subnet subnetAddr)
            throws StateAccessException, SerializationException {

        if (!authorizer.authorize(context, AuthAction.WRITE, bridgeId)) {
            throw new ForbiddenHttpException(
                    "Not authorized to delete dhcp configuration of "
                            + "this bridge.");
        }

        dataClient.dhcpSubnetsDelete(bridgeId, subnetAddr);
    }

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
