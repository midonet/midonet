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
package org.midonet.api.network.rest_api;

import java.net.URI;
import java.util.List;
import javax.annotation.security.RolesAllowed;
import javax.validation.Validator;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import org.midonet.api.ResourceUriBuilder;
import org.midonet.cluster.rest_api.VendorMediaType;
import org.midonet.cluster.auth.AuthRole;
import org.midonet.api.network.VtepBinding;
import org.midonet.api.rest_api.ResourceFactory;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.api.vtep.VtepClusterClient;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.data.Bridge;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.packets.IPv4Addr;

public class VtepBindingResource extends AbstractVtepResource {

    /** The parent vtep where this binding belongs */
    private final String ipAddrStr;

    @Inject
    public VtepBindingResource(RestApiConfig config, UriInfo uriInfo,
                               SecurityContext context, Validator validator,
                               DataClient dataClient, ResourceFactory factory,
                               VtepClusterClient vtepClient,
                               @Assisted String ipAddrStr) {
        super(config, uriInfo, context, validator,
              dataClient, factory, vtepClient);
        this.ipAddrStr = ipAddrStr;
    }

    @POST
    @RolesAllowed({AuthRole.ADMIN})
    @Consumes({VendorMediaType.APPLICATION_VTEP_BINDING_JSON,
               MediaType.APPLICATION_JSON})
    public Response create(VtepBinding binding)
        throws StateAccessException, SerializationException {
        validate(binding);
        IPv4Addr ipAddr = parseIPv4Addr(ipAddrStr);
        Bridge bridge = getBridgeOrThrow(binding.getNetworkId(), true);
        vtepClient.createBinding(binding, ipAddr, bridge);
        URI uri = ResourceUriBuilder.getVtepBinding(getBaseUri(),
                ipAddrStr, binding.getPortName(), binding.getVlanId());
        return Response.created(uri).build();
    }

    @GET
    @RolesAllowed({AuthRole.ADMIN})
    @Produces({VendorMediaType.APPLICATION_VTEP_BINDING_JSON,
               MediaType.APPLICATION_JSON})
    @Path("{portName}/{vlanId}")
    public VtepBinding get(@PathParam("portName") String portName,
                           @PathParam("vlanId") short vlanId)
        throws SerializationException, StateAccessException {

        java.util.UUID bridgeId = vtepClient.getBoundBridgeId(
                parseIPv4Addr(ipAddrStr), portName, vlanId);
        VtepBinding b = new VtepBinding(ipAddrStr, portName, vlanId, bridgeId);
        b.setBaseUri(getBaseUri());
        return b;
    }

    @GET
    @RolesAllowed({AuthRole.ADMIN})
    @Produces({VendorMediaType.APPLICATION_VTEP_BINDING_COLLECTION_JSON,
               MediaType.APPLICATION_JSON})
    public List<VtepBinding> list() throws StateAccessException,
                                           SerializationException {
        return listVtepBindings(this.ipAddrStr, null);
    }

    @DELETE
    @RolesAllowed({AuthRole.ADMIN})
    @Path("{portName}/{vlanId}")
    public void delete(@PathParam("portName") String portName,
                       @PathParam("vlanId") short vlanId)
            throws StateAccessException, SerializationException {
        vtepClient.deleteBinding(parseIPv4Addr(ipAddrStr), portName, vlanId);
    }

}
