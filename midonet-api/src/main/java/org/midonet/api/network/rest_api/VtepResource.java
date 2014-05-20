/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.api.network.rest_api;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import org.midonet.api.rest_api.BadGatewayHttpException;
import org.midonet.api.rest_api.BadRequestHttpException;
import org.midonet.api.rest_api.ConflictHttpException;
import org.midonet.api.rest_api.GatewayTimeoutHttpException;
import org.midonet.api.rest_api.NotFoundHttpException;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.api.vtep.VtepDataClientProvider;
import org.midonet.brain.southbound.vtep.VtepDataClient;
import org.midonet.brain.southbound.vtep.model.LogicalSwitch;
import org.midonet.brain.southbound.vtep.model.PhysicalPort;
import org.midonet.brain.southbound.vtep.model.PhysicalSwitch;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.data.Bridge;
import org.midonet.cluster.data.Port;
import org.midonet.cluster.data.ports.BridgePort;
import org.midonet.cluster.data.ports.VlanMacPort;
import org.midonet.cluster.data.ports.VxLanPort;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.StatePathExistsException;
import org.midonet.packets.IPv4Addr;

import org.opendaylight.controller.sal.utils.Status;
import org.opendaylight.controller.sal.utils.StatusCode;
import org.opendaylight.ovsdb.lib.notation.UUID;
import org.opendaylight.ovsdb.plugin.StatusWithUuid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.midonet.api.validation.MessageProperty.NETWORK_ALREADY_BOUND;
import static org.midonet.api.validation.MessageProperty.VTEP_BINDING_NOT_FOUND;
import static org.midonet.api.validation.MessageProperty.VTEP_EXISTS;
import static org.midonet.api.validation.MessageProperty.VTEP_INACCESSIBLE;
import static org.midonet.api.validation.MessageProperty.VTEP_NOT_FOUND;
import static org.midonet.api.validation.MessageProperty.VTEP_PORT_NOT_FOUND;
import static org.midonet.api.validation.MessageProperty.getMessage;
import static org.midonet.brain.southbound.vtep.VtepConstants.bridgeIdToLogicalSwitchName;
import static org.midonet.brain.southbound.vtep.VtepConstants.logicalSwitchNameToBridgeId;

public class VtepResource extends AbstractResource {

    private static final Logger log = LoggerFactory.getLogger(
        VtepResource.class);

    private final Random rand = new Random();
    private final VtepDataClientProvider vtepClientProvider;

    @Inject
    public VtepResource(RestApiConfig config, UriInfo uriInfo,
                        SecurityContext context, Validator validator,
                        DataClient dataClient,
                        VtepDataClientProvider vtepClientProvider) {
        super(config, uriInfo, context, dataClient, validator);
        this.vtepClientProvider = vtepClientProvider;
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

    /**
     * Creates a VTEP client and opens a connection to the VTEP at
     * mgmtIp:mgmtPort.
     *
     * @param throwOnFailure If true, throws a GatewayTimeoutHttpException
     *                       when failing to connect to VTEP.
     */
    private VtepDataClient getVtepClient(IPv4Addr mgmtIp, int mgmtPort,
                                         boolean throwOnFailure) {
        VtepDataClient vtepClient = vtepClientProvider.makeClient();
        try {
            vtepClient.connect(mgmtIp, mgmtPort);
            return vtepClient;
        } catch (IllegalStateException ex) {
            log.warn("Unable to connect to VTEP: ", ex);
            if (!throwOnFailure)
                return null;

            throw new GatewayTimeoutHttpException(getMessage(
                    VTEP_INACCESSIBLE, mgmtIp, mgmtPort));
        }
    }

    /**
     * Gets the PhysicalSwitch record from the database of the VTEP at
     * the specified IP and port.
     *
     * @param throwOnFailure If true, throws a GatewayTimeoutHttpException
     *                       when failing to connect to VTEP.
     */
    private PhysicalSwitch getPhysicalSwitch(IPv4Addr mgmtIp, int mgmtPort,
                                             boolean throwOnFailure) {
        VtepDataClient vtepClient =
                getVtepClient(mgmtIp, mgmtPort, throwOnFailure);
        if (vtepClient == null)
            return null;

        try {
            return getPhysicalSwitch(vtepClient, mgmtIp);
        } finally {
            vtepClient.disconnect();
        }
    }

    /**
     * Gets the PhysicalSwitch record with the specified managementIp
     * address using the provided VtepDataClient.
     */
    private PhysicalSwitch getPhysicalSwitch(VtepDataClient vtepClient,
                                             IPv4Addr mgmtIp) {

        List<PhysicalSwitch> switches = vtepClient.listPhysicalSwitches();
        if (switches.size() == 1)
            return switches.get(0);

        for (PhysicalSwitch ps : switches)
            if (ps.mgmtIps != null && ps.mgmtIps.contains(mgmtIp.toString()))
                return ps;

        return null;
    }

    /**
     * Gets a list of PhysicalPorts belonging to the specified VTEP
     * using the provided VtepDataClient.
     */
    private List<PhysicalPort> getPhysicalPorts(
            VtepDataClient vtepClient, org.midonet.cluster.data.VTEP vtep)
            throws StateAccessException {
        // Get the physical switch.
        PhysicalSwitch ps = getPhysicalSwitch(vtepClient, vtep.getId());
        if (ps == null) {
            throw new GatewayTimeoutHttpException(getMessage(
                    VTEP_INACCESSIBLE, vtep.getId(), vtep.getMgmtPort()));
        }

        // TODO: Handle error if this fails or returns null.
        return vtepClient.listPhysicalPorts(ps.uuid);
    }

    /**
     * Gets the PhysicalPort named portName from the specified VTEP
     * using the provided VtepDataClient.
     */
    private PhysicalPort getPhysicalPort(VtepDataClient vtepClient,
                                         org.midonet.cluster.data.VTEP vtep,
                                         String portName)
                throws StateAccessException {
        // Find the requested port.
        List<PhysicalPort> pports = getPhysicalPorts(vtepClient, vtep);
        for (PhysicalPort pport : pports)
            if (pport.name.equals(portName))
                return pport;

        // Switch doesn't have the specified port.
        throw new NotFoundHttpException(getMessage(
                VTEP_PORT_NOT_FOUND, vtep.getId(), vtep.getMgmtPort(),
                portName));
    }

    /**
     * Gets the ID of the bridge bound to the specified port and VLAN ID
     * on the specified VTEP.
     *
     * @param ipAddrStr VTEP's management IP address
     * @param portName Binding's port name
     * @param vlanId Binding's VLAN ID
     */
    private java.util.UUID getBoundBridgeId(
            String ipAddrStr, String portName, short vlanId)
            throws SerializationException, StateAccessException {
        org.midonet.cluster.data.VTEP vtep = getVtepOrThrow(ipAddrStr, false);
        VtepDataClient vtepClient =
                getVtepClient(vtep.getId(), vtep.getMgmtPort(), true);

        try {
            PhysicalPort pp = getPhysicalPort(vtepClient, vtep, portName);
            UUID lsUuid = pp.vlanBindings.get((int)vlanId);
            if (lsUuid == null) {
                throw new NotFoundHttpException(getMessage(VTEP_BINDING_NOT_FOUND,
                        vtep.getId(), vtep.getMgmtPort(), vlanId, portName));
            }

            for (LogicalSwitch lswitch : vtepClient.listLogicalSwitches()) {
                if (lswitch.uuid.equals(lsUuid))
                    return logicalSwitchNameToBridgeId(lswitch.name);
            }

            throw new IllegalStateException(
                    "Logical switch with ID " + lsUuid + " should exist but " +
                    "was not returned from VTEP client.");
        } finally {
            vtepClient.disconnect();
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
        org.midonet.cluster.data.VTEP dataVtep = getVtepOrThrow(ipAddr, false);
        PhysicalSwitch ps =
                getPhysicalSwitch(ipAddr, dataVtep.getMgmtPort(), false);

        VTEP vtep = new VTEP(getVtepOrThrow(ipAddr, false), ps);
        vtep.setBaseUri(getBaseUri());
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
            PhysicalSwitch ps = getPhysicalSwitch(
                    dataVtep.getId(), dataVtep.getMgmtPort(), false);
            VTEP vtep = new VTEP(dataVtep, ps);
            vtep.setBaseUri(getBaseUri());
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
        VxLanPort vxlanPort = null;
        if (bridge.getVxLanPortId() != null) {
            vxlanPort = (VxLanPort)dataClient.portsGet(bridge.getVxLanPortId());
            if (!vxlanPort.getMgmtIpAddr().equals(ipAddr)) {
                throw new ConflictHttpException(getMessage(
                        NETWORK_ALREADY_BOUND,
                        binding.getNetworkId(), vtep.getId()));
            }
            log.info("Found VxLanPort {}, vni {}",
                     vxlanPort.getId(), vxlanPort.getVni());
        }

        VtepDataClient vtepClient =
                getVtepClient(ipAddr, vtep.getMgmtPort(), true);
        try {
            Integer newPortVni = null;
            String lsName = bridgeIdToLogicalSwitchName(binding.getNetworkId());
            if (bridge.getVxLanPortId() == null) {
                newPortVni = rand.nextInt((1 << 24) - 1) + 1; // TODO: unique?
                // TODO: Make VTEP client take UUID instead of name.
                StatusWithUuid status =
                        vtepClient.addLogicalSwitch(lsName, newPortVni);
                throwIfFailed(vtepClient, status);
            }

            // TODO: fill this list with all the host ips where we want the VTEP
            // to flood unknown dst mcasts. Not adding all host's ips because
            // we'd send the same packet to all of them, so for now we add none
            // and will just populate ucasts.
            List<String> floodToIps = new ArrayList<>();

            Status status = vtepClient.bindVlan(lsName, binding.getPortName(),
                                                binding.getVlanId(), newPortVni,
                                                floodToIps);
            throwIfFailed(vtepClient, status);

            // For all known macs, instruct the vtep to add a ucast mac entry to
            // tunnel packets over to the right host.
            if (newPortVni != null) {
                log.debug("Preseeding macs from bridge {}",
                          binding.getNetworkId());
                feedUcastRemote(vtepClient, binding.getNetworkId(), lsName);
                vxlanPort = dataClient.bridgeCreateVxLanPort(
                        bridge.getId(), ipAddr, vtep.getMgmtPort(), newPortVni);
                log.debug("New VxLan port created, uuid: {}, vni: {}",
                          vxlanPort.getId(), newPortVni);
            }
        } finally {
            vtepClient.disconnect();
        }

        URI uri = ResourceUriBuilder.getVtepBinding(getBaseUri(),
                ipAddrStr, binding.getPortName(), binding.getVlanId());
        return Response.created(uri).build();
    }

    @GET
    @RolesAllowed({AuthRole.ADMIN})
    @Produces({VendorMediaType.APPLICATION_VTEP_BINDING_JSON,
               MediaType.APPLICATION_JSON})
    @Path("{ipAddr}/bindings/{portName}_{vlanId}")
    public VTEPBinding getBinding(@PathParam("ipAddr") String ipAddrStr,
                                  @PathParam("portName") String portName,
                                  @PathParam("vlanId") short vlanId)
            throws SerializationException, StateAccessException {

        java.util.UUID bridgeId = getBoundBridgeId(ipAddrStr, portName, vlanId);
        return new VTEPBinding(ipAddrStr, portName, vlanId, bridgeId);
    }

    /**
     * Takes a bridge id, reads all its known macs, and writes the corresponding
     * ucast_mac_remote entries to the vtep using the tunnel end point that
     * appropriate to the host where each port is bound.
     *
     * TODO: optimize this. We could issue a single write to the VTEP instead of
     * lots of individual calls.
     *
     * @param vtepClient a vtep client, initialized and ready to use
     * @param bridgeId bridge id
     * @param lsName logical switch name where mac entries are to be added
     * @throws StateAccessException
     * @throws SerializationException
     */
    private void feedUcastRemote(VtepDataClient vtepClient,
                                java.util.UUID bridgeId, String lsName)
        throws StateAccessException, SerializationException
    {
        for (VlanMacPort vmp : dataClient.bridgeGetMacPorts(bridgeId)) {
            Port p = dataClient.portsGet(vmp.portId);
            if (p != null && p.isExterior()) {
                IPv4Addr hostIp =
                    dataClient.vxlanTunnelEndpointFor((BridgePort)p);
                if (hostIp == null) {
                    log.warn("No VxLAN tunnel endpoint for port {}", p.getId());
                } else {
                    log.debug("MAC {} is at host {}", vmp.macAddress, hostIp);
                    vtepClient.addUcastMacRemote(lsName,
                                                 vmp.macAddress.toString(),
                                                 hostIp.toString());
                }
            }
        }
    }

    @GET
    @RolesAllowed({AuthRole.ADMIN})
    @Produces({VendorMediaType.APPLICATION_VTEP_BINDING_COLLECTION_JSON,
               MediaType.APPLICATION_JSON})
    @Path("{ipAddr}/bindings")
    public List<VTEPBinding> listBindings(@PathParam("ipAddr") String ipAddrStr)
            throws StateAccessException, SerializationException {

        org.midonet.cluster.data.VTEP vtep = getVtepOrThrow(ipAddrStr, false);
        VtepDataClient vtepClient =
                getVtepClient(vtep.getId(), vtep.getMgmtPort(), true);

        try {
            Map<UUID, java.util.UUID> lsIdBridgeIdMap = new HashMap<>();
            for (LogicalSwitch ls : vtepClient.listLogicalSwitches()) {
                lsIdBridgeIdMap.put(ls.uuid, logicalSwitchNameToBridgeId(ls.name));
            }

            List<VTEPBinding> bindings = new ArrayList<>();
            for (PhysicalPort pp : getPhysicalPorts(vtepClient, vtep)) {
                for (Map.Entry<Integer, UUID> e : pp.vlanBindings.entrySet()) {

                    java.util.UUID bridgeId = lsIdBridgeIdMap.get(e.getValue());
                    if (bridgeId != null) { // Ignore non-Midonet bindings.
                        VTEPBinding binding = new VTEPBinding(ipAddrStr, pp.name,
                                e.getKey().shortValue(), bridgeId);
                        binding.setBaseUri(getBaseUri());
                        bindings.add(binding);
                    }
                }
            }

            return bindings;

        } finally {
            vtepClient.disconnect();
        }
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

    private org.midonet.cluster.data.VTEP getVtepOrThrow(
            String ipAddrStr, boolean badRequest)
            throws StateAccessException, SerializationException {
        return getVtepOrThrow(parseIPv4Addr(ipAddrStr), badRequest);
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

    private void throwIfFailed(
            VtepDataClient vtepClient, Status status) {

        if (status.isSuccess())
            return;

        if (status.getCode() == StatusCode.BADREQUEST)
            throw new BadRequestHttpException(status.getDescription());
        if (status.getCode() == StatusCode.CONFLICT)
            throw new ConflictHttpException(status.getDescription());
        if (status.getCode() == StatusCode.NOTFOUND)
            throw new NotFoundHttpException(status.getDescription());

        log.warn("Unexpected response from VTEP: " + status);
        throw new BadGatewayHttpException(status.getDescription());
    }
}
