/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.api.network.rest_api;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
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
import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.VendorMediaType;
import org.midonet.api.auth.AuthRole;
import org.midonet.api.network.VTEP;
import org.midonet.api.network.VTEPBinding;
import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.BadRequestHttpException;
import org.midonet.api.rest_api.ConflictHttpException;
import org.midonet.api.rest_api.NotFoundHttpException;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.brain.southbound.vtep.VtepDataClient;
import org.midonet.brain.southbound.vtep.VtepDataClientImpl;
import org.midonet.brain.southbound.vtep.model.PhysicalSwitch;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.data.Bridge;
import org.midonet.cluster.data.ports.VxLanPort;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.StatePathExistsException;
import org.midonet.midolman.state.VtepConnectionState;
import org.midonet.packets.IPv4Addr;
import static org.midonet.api.validation.MessageProperty.NETWORK_ALREADY_BOUND;
import static org.midonet.api.validation.MessageProperty.VTEP_EXISTS;
import static org.midonet.api.validation.MessageProperty.VTEP_NOT_FOUND;
import static org.midonet.api.validation.MessageProperty.getMessage;

public class VtepResource extends AbstractResource {

    private final Random rand = new Random();

    @Inject
    public VtepResource(RestApiConfig config, UriInfo uriInfo,
                        SecurityContext context, Validator validator,
                        DataClient dataClient) {
        super(config, uriInfo, context, dataClient, validator);
    }

    @POST
    @RolesAllowed({AuthRole.ADMIN})
    @Consumes({VendorMediaType.APPLICATION_VTEP_JSON,
               VendorMediaType.APPLICATION_JSON})
    public Response create(VTEP vtep)
            throws SerializationException, StateAccessException {

        validate(vtep);

        try {
            org.midonet.cluster.data.VTEP dataVtep = vtep.toData();
            dataClient.vtepCreate(dataVtep);
            return Response.created(ResourceUriBuilder.getVtep(
                    getBaseUri(), dataVtep.getId().toString())).build();
        } catch(StatePathExistsException ex) {
            throw new ConflictHttpException(getMessage(
                    VTEP_EXISTS, vtep.getManagementIp()));
        }

    }

    private VtepDataClient getVtepClient(IPv4Addr mgmtIp, int mgmtPort) {
        VtepDataClient vtepClient = new VtepDataClientImpl();
        vtepClient.connect(mgmtIp, mgmtPort);
        return vtepClient;
    }

    /**
     * Gets the PhysicalSwitch record from the database of the VTEP at
     * the specified IP and port.
     */
    private PhysicalSwitch getPhysicalSwitch(IPv4Addr mgmtIp, int mgmtPort) {
        VtepDataClient vtepClient = getVtepClient(mgmtIp, mgmtPort);
        List<PhysicalSwitch> switches = vtepClient.listPhysicalSwitches();
        vtepClient.disconnect();
        if (switches.size() == 1)
            return switches.get(0);

        for (PhysicalSwitch ps : switches)
            if (ps.mgmtIps != null && ps.mgmtIps.contains(mgmtIp.toString()))
                return ps;

        return null;
    }

    @GET
    @RolesAllowed({AuthRole.ADMIN})
    @Path("{ipAddr}")
    @Produces({VendorMediaType.APPLICATION_VTEP_JSON,
               MediaType.APPLICATION_JSON})
    public VTEP get(@PathParam("ipAddr") String ipAddrStr)
            throws StateAccessException, SerializationException {
        IPv4Addr ipAddr = parseIPv4Addr(ipAddrStr);
        VTEP vtep = new VTEP(getVtepOrThrow(ipAddr, false));
        vtep.setBaseUri(getBaseUri());

        PhysicalSwitch ps = getPhysicalSwitch(ipAddr, vtep.getManagementPort());

        // TODO: Move this to VTEP.
        if (ps == null) {
            vtep.setConnectionState(VtepConnectionState.ERROR);
        } else {
            vtep.setConnectionState(VtepConnectionState.CONNECTED);
            vtep.setDescription(ps.description);
            vtep.setName(ps.name);
            vtep.setTunnelIpAddrs(new ArrayList<>(ps.tunnelIps));
        }

        return vtep;
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
            VTEP vtep = new VTEP(dataVtep);
            vtep.setBaseUri(getBaseUri());

            // TODO: Connect to VTEP and get additional properties.
            vteps.add(vtep);
        }
        return vteps;
    }

    @DELETE
    @RolesAllowed({AuthRole.ADMIN})
    @Path("{ipAddr}")
    public void delete(@PathParam("ipAddr") String ipAddrStr)
            throws StateAccessException {
        // TODO: Verify that it has no bindings to Midonet networks.

    }

    @POST
    @RolesAllowed({AuthRole.ADMIN})
    @Consumes({VendorMediaType.APPLICATION_VTEP_BINDING_JSON,
            VendorMediaType.APPLICATION_JSON})
    @Path("{ipAddr}/bindings")
    public Response addBinding(@PathParam("ipAddr") String ipAddrStr,
                               VTEPBinding binding)
            throws StateAccessException, SerializationException {

        validate(binding);
        IPv4Addr ipAddr = parseIPv4Addr(ipAddrStr);
        org.midonet.cluster.data.VTEP vtep = getVtepOrThrow(ipAddr, true);

        Bridge bridge = getBridgeOrThrow(binding.getNetworkId(), true);
        if (bridge.getVxLanPortId() != null) {
            VxLanPort port =
                    (VxLanPort)dataClient.portsGet(bridge.getVxLanPortId());
            if (!port.getMgmtIpAddr().equals(ipAddr)) {
                throw new ConflictHttpException(getMessage(
                        NETWORK_ALREADY_BOUND,
                        binding.getNetworkId(), vtep.getId()));
            }
        }

        Integer newPortVni = null;
        VtepDataClient vtepClient = getVtepClient(ipAddr, vtep.getMgmtPort());
        if (bridge.getVxLanPortId() == null) {
            // TODO: Unique VNI.
            newPortVni = rand.nextInt((1 << 24) - 1) + 1;
            // TODO: Make VTEP client take UUID instead of name.
            vtepClient.addLogicalSwitch("midonet-" + binding.getNetworkId(),
                                        newPortVni);
        }

        // TODO: Make this return the actual status, so we can return
        // a more appropriate error to the caller.
        boolean success = vtepClient.bindVlan(
                "midonet-" + binding.getNetworkId(),
                binding.getPortName(), binding.getVlanId(), newPortVni,
                new ArrayList<String>());
        vtepClient.disconnect();

        if (newPortVni != null && success) {
            dataClient.bridgeCreateVxLanPort(bridge.getId(), ipAddr,
                                             vtep.getMgmtPort(), newPortVni);
        }

        if (success) {
            URI uri = ResourceUriBuilder.getVtepBinding(getBaseUri(),
                    ipAddrStr, binding.getPortName(), binding.getVlanId());
            return Response.created(uri).build();
        } else {
            // TODO: Delete logical switch if there are no other bindings.
            // TODO: Better error code. Need to get status from VTEP client.
            return Response.serverError().build();
        }
    }

    @GET
    @RolesAllowed({AuthRole.ADMIN})
    @Produces({VendorMediaType.APPLICATION_VTEP_BINDING_COLLECTION_JSON,
               MediaType.APPLICATION_JSON})
    @Path("{ipAddr}/bindings")
    public List<VTEPBinding> listBindings(@PathParam("ipAddr") String ipAddrStr)
            throws StateAccessException, SerializationException {
        org.midonet.cluster.data.VTEP vtep =
                getVtepOrThrow(parseIPv4Addr(ipAddrStr), true);

        // TODO: Connect to VTEP and get bindings.
        VtepDataClient vtepClient =
                getVtepClient(vtep.getId(), vtep.getMgmtPort());

        List<VTEPBinding> bindings = new ArrayList<>();
        return bindings;
    }

    @DELETE
    @RolesAllowed({AuthRole.ADMIN})
    @Path("{ipAddr}/bindings/{portName}_{vlanId}")
    public void deleteBinding(@PathParam("ipAddr") String ipAddrStr,
                              @PathParam("portName") String portName,
                              @PathParam("vlanId") short vlanId)
            throws StateAccessException, SerializationException {
        org.midonet.cluster.data.VTEP vtep =
                getVtepOrThrow(parseIPv4Addr(ipAddrStr), true);

        // TODO: Connect to the VTEP and delete the binding.

        // TODO: If it's the network's last binding, delete the VXLAN port.
    }

    /**
     * Gets the VTEP record with the specified IP address. If not found,
     * will throw a BadRequestHttpException if badRequest is true, or a
     * NotFoundHttpException otherwise.
     */
    private org.midonet.cluster.data.VTEP getVtepOrThrow(
            IPv4Addr ipAddr, boolean badRequest)
            throws StateAccessException, SerializationException {
        org.midonet.cluster.data.VTEP dataVtep = dataClient.vtepGet(ipAddr);
        if (dataVtep == null) {
            String msg = getMessage(VTEP_NOT_FOUND, ipAddr);
            throw badRequest ? new BadRequestHttpException(msg) :
                               new NotFoundHttpException(msg);
        }
        return dataVtep;
    }
}
