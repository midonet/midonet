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
import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.auth.AuthRole;
import org.midonet.cluster.data.dhcp.V6Host;
import org.midonet.cluster.rest_api.ConflictHttpException;
import org.midonet.cluster.rest_api.NotFoundHttpException;
import org.midonet.cluster.rest_api.VendorMediaType;
import org.midonet.cluster.rest_api.models.DhcpV6Host;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.StatePathExceptionBase;
import org.midonet.midolman.state.StatePathExistsException;
import org.midonet.packets.IPv6Subnet;

import static org.midonet.cluster.rest_api.conversion.DhcpHostDataConverter.fromData;
import static org.midonet.cluster.rest_api.conversion.DhcpHostDataConverter.toData;

@RequestScoped
public class DhcpV6HostsResource extends AbstractResource {

    private final UUID bridgeId;
    private final IPv6Subnet prefix;

    @Inject
    public DhcpV6HostsResource(RestApiConfig config, UriInfo uriInfo,
                               SecurityContext context, DataClient dataClient,
                               @Assisted UUID bridgeId,
                               @Assisted IPv6Subnet prefix) {
        super(config, uriInfo, context, dataClient, null);
        this.bridgeId = bridgeId;
        this.prefix = prefix;
    }

    /**
     * @return Response object with 201 status code set if successful.
     */
    @POST
    @RolesAllowed({AuthRole.ADMIN, AuthRole.TENANT_ADMIN})
    @Consumes({ VendorMediaType.APPLICATION_DHCPV6_HOST_JSON,
                MediaType.APPLICATION_JSON })
    public Response create(DhcpV6Host host) throws StateAccessException,
                                                   SerializationException {

        authoriser.tryAuthoriseBridge(bridgeId,
                                      "configure DHCP for this bridge.");

        try {
            dataClient.dhcpV6HostCreate(bridgeId, prefix, toData(host));
        } catch (StatePathExistsException e) {
            throw new ConflictHttpException("Host already exists");
        }

        URI dhcpUri = ResourceUriBuilder.getBridgeDhcpV6(getBaseUri(),
                                                         bridgeId, prefix);
        return Response.created(
            ResourceUriBuilder.getDhcpV6Host(dhcpUri, host.clientId)).build();
    }

    @GET
    @PermitAll
    @Path("/{clientId}")
    @Produces({ VendorMediaType.APPLICATION_DHCPV6_HOST_JSON,
            MediaType.APPLICATION_JSON })
    public DhcpV6Host get(@PathParam("clientId") String clientId)
            throws StateAccessException, SerializationException {


        authoriser.tryAuthoriseBridge(bridgeId,
                                      "view this bridge's dhcpV6 config.");

        // The clientId in the URI uses '-' instead of ':'
        clientId = DhcpV6Host.clientIdFromUri(clientId);
        V6Host hostConfig = dataClient.dhcpV6HostGet(bridgeId, prefix, clientId);
        if (hostConfig == null) {
            throw new NotFoundHttpException(
                    "The requested resource was not found.");
        }

        return fromData(hostConfig,
                        ResourceUriBuilder.getBridgeDhcpV6(getBaseUri(),
                                                           bridgeId, prefix));
    }

    @PUT
    @RolesAllowed({AuthRole.ADMIN, AuthRole.TENANT_ADMIN})
    @Path("/{clientId}")
    @Consumes({ VendorMediaType.APPLICATION_DHCPV6_HOST_JSON,
                MediaType.APPLICATION_JSON })
    public Response update(@PathParam("clientId") String clientId,
                           DhcpV6Host host)
            throws StateAccessException, SerializationException {

        authoriser.tryAuthoriseBridge(bridgeId,
                                      "update this bridge's dhcpV6 config.");

        // The clientId in the URI uses '-' instead of ':'
        // Make sure that the DHCPV6Host has the same clientId address as the URI.
        host.clientId = DhcpV6Host.clientIdFromUri(clientId);
        dataClient.dhcpV6HostUpdate(bridgeId, prefix, toData(host));
        return Response.noContent().build();
    }

    @DELETE
    @RolesAllowed({AuthRole.ADMIN, AuthRole.TENANT_ADMIN})
    @Path("/{clientId}")
    public void delete(@PathParam("clientId") String clientId)
            throws StateAccessException, SerializationException {

        authoriser.tryAuthoriseBridge(
            bridgeId, "delete dhcpV6 configuration of this bridge.");

        // The clientId in the URI uses '-' instead of ':'
        clientId = DhcpV6Host.clientIdFromUri(clientId);
        dataClient.dhcpV6HostDelete(bridgeId, prefix, clientId);
    }

    @GET
    @PermitAll
    @Produces({ VendorMediaType.APPLICATION_DHCPV6_HOST_COLLECTION_JSON })
    public List<DhcpV6Host> list()
            throws StateAccessException, SerializationException {


        authoriser.tryAuthoriseBridge(bridgeId,
                                      "view DHCPV6 config of this bridge.");

        List<V6Host> hostConfigs = dataClient.dhcpV6HostsGetByPrefix(bridgeId,
                                                                     prefix);
        List<DhcpV6Host> hosts = new ArrayList<>();
        for (V6Host hostConfig : hostConfigs) {
            URI subnetUri = ResourceUriBuilder.getBridgeDhcpV6(getBaseUri(),
                                                               bridgeId,
                                                               prefix);
            hosts.add(fromData(hostConfig, subnetUri));
        }
        return hosts;
    }

}
