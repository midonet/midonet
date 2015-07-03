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

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
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

import org.midonet.api.ResourceUriBuilder;
import org.midonet.cluster.rest_api.VendorMediaType;
import org.midonet.api.auth.AuthRole;
import org.midonet.api.network.VTEPPort;
import org.midonet.cluster.rest_api.BadRequestHttpException;
import org.midonet.cluster.rest_api.ConflictHttpException;
import org.midonet.cluster.rest_api.GatewayTimeoutHttpException;
import org.midonet.cluster.rest_api.NotFoundHttpException;
import org.midonet.api.rest_api.ResourceFactory;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.api.vtep.VtepClusterClient;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.data.host.Host;
import org.midonet.cluster.data.vtep.VtepNotConnectedException;
import org.midonet.cluster.data.vtep.model.PhysicalSwitch;
import org.midonet.cluster.rest_api.conversion.VTEPDataConverter;
import org.midonet.cluster.rest_api.models.VTEP;
import org.midonet.cluster.rest_api.validation.MessageProperty;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.NoStatePathException;
import org.midonet.midolman.state.NodeNotEmptyStateException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.StatePathExistsException;
import org.midonet.packets.IPv4Addr;

import com.google.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static scala.collection.JavaConversions.asJavaCollection;

import static org.midonet.cluster.rest_api.validation.MessageProperty.VTEP_EXISTS;
import static org.midonet.cluster.rest_api.validation.MessageProperty.VTEP_HAS_BINDINGS;
import static org.midonet.cluster.rest_api.validation.MessageProperty.VTEP_HOST_IP_CONFLICT;
import static org.midonet.cluster.rest_api.validation.MessageProperty.VTEP_NOT_FOUND;
import static org.midonet.cluster.rest_api.validation.MessageProperty.getMessage;

public class VtepResource extends AbstractVtepResource {
    private final static Logger log =
        LoggerFactory.getLogger(VtepResource.class);

    @Inject
    public VtepResource(RestApiConfig config, UriInfo uriInfo,
                        SecurityContext context, Validator validator,
                        DataClient dataClient, ResourceFactory factory,
                        VtepClusterClient vtepClient) {
        super(config, uriInfo, context, validator,
              dataClient, factory, vtepClient);
    }

    @POST
    @RolesAllowed({AuthRole.ADMIN})
    @Consumes({VendorMediaType.APPLICATION_VTEP_JSON,
               MediaType.APPLICATION_JSON})
    public Response create(VTEP vtep)
            throws SerializationException, StateAccessException {

        validate(vtep);

        if (!dataClient.tunnelZonesExists(vtep.tunnelZoneId)) {
            throw new BadRequestHttpException(
                getMessage(MessageProperty.TUNNEL_ZONE_ID_IS_INVALID));
        }

        org.midonet.cluster.data.VTEP dataVtep = VTEPDataConverter.toData(vtep);
        List<InetAddress> vtepIps = new ArrayList<>();

        // Verify there is no conflict between hosts and the VTEP IPs.
        try {
            PhysicalSwitch ps = vtepClient.getPhysicalSwitch(
                IPv4Addr.apply(vtep.managementIp),
                vtep.managementPort);

            // Check all management and tunnel IPs configured for the physical
            // switch.
            addIpsToList(vtepIps, ps.mgmtIpStrings());
            addIpsToList(vtepIps, ps.tunnelIpStrings());

        } catch(GatewayTimeoutHttpException | VtepNotConnectedException e) {
            log.warn("Cannot verify conflicts between hosts and VTEP IPs "
                     + " because VTEP {}:{} is not accessible",
                     vtep.managementIp, vtep.managementPort);
        }

        addIpToList(vtepIps, vtep.managementIp);

        for (Host host : dataClient.hostsGetAll()) {
            for (InetAddress ip : host.getAddresses()) {
                if (vtepIps.contains(ip)) {
                    throw new ConflictHttpException(
                        getMessage(VTEP_HOST_IP_CONFLICT, ip));
                }
            }
        }

        try {
            dataClient.vtepCreate(dataVtep);
            return Response.created(ResourceUriBuilder.getVtep(
                    getBaseUri(), dataVtep.getId().toString())).build();
        } catch(StatePathExistsException ex) {
            throw new ConflictHttpException(
                ex, getMessage(VTEP_EXISTS, vtep.managementIp));
        }
    }

    @GET
    @RolesAllowed({AuthRole.ADMIN})
    @Path("{ipAddr}")
    @Produces({VendorMediaType.APPLICATION_VTEP_JSON,
               MediaType.APPLICATION_JSON})
    public VTEP get(@PathParam("ipAddr") String ipAddrStr)
            throws StateAccessException, SerializationException {

        IPv4Addr ipAddr = parseIPv4Addr(ipAddrStr);
        org.midonet.cluster.data.VTEP dataVtep =
                vtepClient.getVtepOrThrow(ipAddr, false);
        return toApiVtep(dataVtep);
    }

    @GET
    @RolesAllowed({AuthRole.ADMIN})
    @Produces({VendorMediaType.APPLICATION_VTEP_COLLECTION_JSON,
               MediaType.APPLICATION_JSON})
    public List<VTEP> list()
            throws StateAccessException, SerializationException {
        List<org.midonet.cluster.data.VTEP> dataVteps = dataClient.vtepsGetAll();
        List<VTEP> vteps = new ArrayList<>(dataVteps.size());
        for (org.midonet.cluster.data.VTEP dataVtep : dataVteps) {
            vteps.add(toApiVtep(dataVtep));
        }
        return vteps;
    }

    @DELETE
    @RolesAllowed({AuthRole.ADMIN})
    @Path("{ipAddr}")
    public void delete(@PathParam("ipAddr") String ipAddrStr)
            throws SerializationException, StateAccessException {
        try {
            vtepClient.deleteVtep(parseIPv4Addr(ipAddrStr));
        } catch (NoStatePathException ex) {
            throw new NotFoundHttpException(getMessage(
                    VTEP_NOT_FOUND, ipAddrStr));
        } catch (NodeNotEmptyStateException ex) {
            throw new BadRequestHttpException(getMessage(
                    VTEP_HAS_BINDINGS, ipAddrStr));
        }
    }

    @GET
    @RolesAllowed({AuthRole.ADMIN})
    @Path("{ipAddr}" + ResourceUriBuilder.PORTS)
    @Produces({VendorMediaType.APPLICATION_VTEP_PORT_COLLECTION_JSON,
               MediaType.APPLICATION_JSON})
    public List<VTEPPort> listPorts(@PathParam("ipAddr") String ipAddrStr)
            throws SerializationException, StateAccessException {
        return vtepClient.listPorts(parseIPv4Addr(ipAddrStr));
    }

    @Path("/{ipAddr}" + ResourceUriBuilder.BINDINGS)
    public VtepBindingResource getVtepBindingResource(
        @PathParam("ipAddr") String ipAddrStr) {
        return factory.getVtepBindingResource(ipAddrStr);
    }

    private VTEP toApiVtep(org.midonet.cluster.data.VTEP dataVtep) {
        PhysicalSwitch ps;
        try {
            ps = vtepClient.getPhysicalSwitch(dataVtep.getId(),
                                              dataVtep.getMgmtPort());
        } catch (Exception ex) {
            ps = null;
        }

        VTEP apiVtep = VTEPDataConverter.fromData(dataVtep, ps);
        apiVtep.setBaseUri(getBaseUri());
        return apiVtep;
    }

    private static void addIpToList(List<InetAddress> list, String ip) {
        try {
            InetAddress address = InetAddress.getByName(ip);
            list.add(address);
        } catch (UnknownHostException e) {
        }
    }

    private static void addIpsToList(List<InetAddress> list,
                                  scala.collection.Iterable<String> ips) {
        for (String ip : asJavaCollection(ips)) {
            addIpToList(list, ip);
        }
    }
}
