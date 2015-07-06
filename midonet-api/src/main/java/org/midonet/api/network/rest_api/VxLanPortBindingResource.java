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

import java.util.List;
import java.util.UUID;

import javax.annotation.security.RolesAllowed;
import javax.validation.Validator;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;

import org.midonet.api.auth.AuthRole;
import org.midonet.api.rest_api.ResourceFactory;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.cluster.southbound.vtep.VtepClusterClient;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.data.Port;
import org.midonet.cluster.data.ports.VxLanPort;
import org.midonet.cluster.rest_api.BadRequestHttpException;
import org.midonet.cluster.rest_api.NotFoundHttpException;
import org.midonet.cluster.rest_api.VendorMediaType;
import org.midonet.cluster.rest_api.models.VTEPBinding;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.packets.IPv4Addr;

import static org.midonet.cluster.rest_api.validation.MessageProperty.PORT_NOT_VXLAN_PORT;
import static org.midonet.cluster.rest_api.validation.MessageProperty.RESOURCE_NOT_FOUND;
import static org.midonet.cluster.rest_api.validation.MessageProperty.VTEP_BINDING_NOT_FOUND;
import static org.midonet.cluster.rest_api.validation.MessageProperty.getMessage;

public class VxLanPortBindingResource extends AbstractVtepResource {

    /** ID of VXLAN port to get bindings for. */
    private UUID vxLanPortId;

    @Inject
    public VxLanPortBindingResource(
            RestApiConfig config, UriInfo uriInfo,
            SecurityContext context, Validator validator,
            DataClient dataClient, ResourceFactory factory,
            VtepClusterClient vtepClient, @Assisted UUID vxLanPortId) {
        super(config, uriInfo, context, validator,
              dataClient, factory, vtepClient);
        this.vxLanPortId = vxLanPortId;
    }

    @GET
    @RolesAllowed({AuthRole.ADMIN})
    @Produces({VendorMediaType.APPLICATION_VTEP_BINDING_JSON,
               MediaType.APPLICATION_JSON})
    @Path("{portName}/{vlanId}")
    public VTEPBinding get(@PathParam("portName") String portName,
                           @PathParam("vlanId") short vlanId)
            throws SerializationException, StateAccessException {

        // Get the ID of the bridge in the specified binding.
        VxLanPort vxLanPort = getVxLanPort(vxLanPortId);
        IPv4Addr ipAddr = vxLanPort.getMgmtIpAddr();
        java.util.UUID boundBridgeId =
                vtepClient.getBoundBridgeId(ipAddr, portName, vlanId);

        // Make sure it matches the VXLAN port's bridge ID.
        if (!boundBridgeId.equals(vxLanPort.getDeviceId())) {
            throw new NotFoundHttpException(getMessage(
                    VTEP_BINDING_NOT_FOUND, ipAddr, vlanId, portName));
        }

        VTEPBinding b = new VTEPBinding();
        b.mgmtIp = ipAddr.toString();
        b.portName = portName;
        b.vlanId = vlanId;
        b.networkId = boundBridgeId;
        b.setBaseUri(getBaseUri());
        return b;
    }

    @GET
    @RolesAllowed({AuthRole.ADMIN})
    @Produces({VendorMediaType.APPLICATION_VTEP_BINDING_COLLECTION_JSON,
            MediaType.APPLICATION_JSON})
    public List<VTEPBinding> list() throws StateAccessException,
            SerializationException {

        VxLanPort vxLanPort = getVxLanPort(vxLanPortId);
        return listVtepBindings(vxLanPort.getMgmtIpAddr().toString(),
                                vxLanPort.getDeviceId());
    }

    /**
     * Gets a VxLanPort with the specified ID.
     *
     * @throws NotFoundHttpException
     *         if no port with the specified ID exists.
     *
     * @throws BadRequestHttpException
     *         if the port with the specified ID is not a VxLanPort.
     */
    private VxLanPort getVxLanPort(UUID vxLanPortId)
            throws SerializationException, StateAccessException {
        Port<?, ?> port = dataClient.portsGet(vxLanPortId);
        if (port == null) {
            throw new NotFoundHttpException(getMessage(
                    RESOURCE_NOT_FOUND, "port", vxLanPortId));
        }

        if (!(port instanceof VxLanPort)) {
            throw new BadRequestHttpException(getMessage(
                    PORT_NOT_VXLAN_PORT, vxLanPortId));
        }

        return (VxLanPort)port;
    }
}
