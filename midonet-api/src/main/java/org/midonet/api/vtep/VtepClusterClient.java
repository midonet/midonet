/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.api.vtep;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.inject.Inject;

import org.opendaylight.controller.sal.utils.Status;
import org.opendaylight.controller.sal.utils.StatusCode;
import org.opendaylight.ovsdb.lib.notation.UUID;
import org.opendaylight.ovsdb.plugin.StatusWithUuid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.api.network.VTEPPort;
import org.midonet.api.network.VtepBinding;
import org.midonet.api.rest_api.BadGatewayHttpException;
import org.midonet.api.rest_api.BadRequestHttpException;
import org.midonet.api.rest_api.ConflictHttpException;
import org.midonet.api.rest_api.GatewayTimeoutHttpException;
import org.midonet.api.rest_api.NotFoundHttpException;
import org.midonet.brain.southbound.vtep.VtepDataClient;
import org.midonet.brain.southbound.vtep.model.LogicalSwitch;
import org.midonet.brain.southbound.vtep.model.PhysicalPort;
import org.midonet.brain.southbound.vtep.model.PhysicalSwitch;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.data.Bridge;
import org.midonet.cluster.data.VTEP;
import org.midonet.cluster.data.ports.VlanMacPort;
import org.midonet.cluster.data.ports.VxLanPort;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.NoStatePathException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.packets.IPv4Addr;

import static org.midonet.api.validation.MessageProperty.NETWORK_ALREADY_BOUND;
import static org.midonet.api.validation.MessageProperty.VTEP_BINDING_NOT_FOUND;
import static org.midonet.api.validation.MessageProperty.VTEP_INACCESSIBLE;
import static org.midonet.api.validation.MessageProperty.VTEP_NOT_FOUND;
import static org.midonet.api.validation.MessageProperty.VTEP_PORT_NOT_FOUND;
import static org.midonet.api.validation.MessageProperty.VTEP_PORT_VLAN_PAIR_ALREADY_USED;
import static org.midonet.api.validation.MessageProperty.getMessage;
import static org.midonet.brain.southbound.vtep.VtepConstants.bridgeIdToLogicalSwitchName;
import static org.midonet.brain.southbound.vtep.VtepConstants.logicalSwitchNameToBridgeId;


/**
 * Coordinates VtepDataClient and DataClient (Zookeeper) operations.
 */
public class VtepClusterClient {

    private static final Logger log =
            LoggerFactory.getLogger(VtepClusterClient.class);

    private final VtepDataClientProvider provider;
    private final DataClient dataClient;

    @Inject
    public VtepClusterClient(DataClient dataClient,
                             VtepDataClientProvider provider) {
        this.dataClient = dataClient;
        this.provider = provider;
    }

    /**
     * Creates a VTEP client and opens a connection to the VTEP at
     * mgmtIp:mgmtPort.
     *
     * @throws GatewayTimeoutHttpException when failing to connect to the VTEP.
     */
    private VtepDataClient getVtepClient(IPv4Addr mgmtIp, int mgmtPort) {
        try {
            return provider.getClient(mgmtIp, mgmtPort);
        } catch (IllegalStateException ex) {
            log.warn("Unable to connect to VTEP: ", ex);
            throw new GatewayTimeoutHttpException(
                    getMessage(VTEP_INACCESSIBLE, mgmtIp, mgmtPort), ex);
        }
    }

    /**
     * Gets the VTEP record with the specified IP address.
     *
     * @throws BadRequestHttpException
     *         When unable to get the VTEP record and badRequest is true.
     * @throws NotFoundHttpException
     *         When unable to get the VTEP record and badRequest is false.
     */
    public final VTEP getVtepOrThrow(IPv4Addr ipAddr, boolean badRequest)
            throws StateAccessException, SerializationException {
        org.midonet.cluster.data.VTEP dataVtep = dataClient.vtepGet(ipAddr);
        if (dataVtep == null) {
            String msg = getMessage(VTEP_NOT_FOUND, ipAddr);
            throw badRequest ? new BadRequestHttpException(msg) :
                    new NotFoundHttpException(msg);
        }
        return dataVtep;
    }

    /**
     * Gets the PhysicalSwitch record from the database of the VTEP at
     * the specified IP and port.
     */
    public final PhysicalSwitch getPhysicalSwitch(IPv4Addr mgmtIp,
                                                  int mgmtPort) {
        VtepDataClient client = getVtepClient(mgmtIp, mgmtPort);
        return getPhysicalSwitch(client, mgmtIp);
    }

    /**
     * Gets the PhysicalSwitch record with the specified managementIp
     * address using the provided VtepDataClient.
     */
    protected final PhysicalSwitch getPhysicalSwitch(VtepDataClient vtepClient,
                                                     IPv4Addr mgmtIp)
    {
        List<PhysicalSwitch> switches = vtepClient.listPhysicalSwitches();
        if (switches.size() == 1)
            return switches.get(0);

        for (PhysicalSwitch ps : switches)
            if (ps.mgmtIps != null && ps.mgmtIps.contains(mgmtIp.toString()))
                return ps;

        return null;
    }

    public final List<VTEPPort> listPorts(IPv4Addr ipAddr)
            throws SerializationException, StateAccessException {
        VTEP vtep = getVtepOrThrow(ipAddr, false);
        VtepDataClient vtepClient =
                getVtepClient(vtep.getId(), vtep.getMgmtPort());

        List<PhysicalPort> pports =
                listPhysicalPorts(vtepClient, ipAddr, vtep.getMgmtPort());
        List<VTEPPort> vtepPorts = new ArrayList<>(pports.size());
        for (PhysicalPort pport : pports)
            vtepPorts.add(new VTEPPort(pport.name, pport.description));
        return vtepPorts;
    }

    /**
     * Gets a list of PhysicalPorts belonging to the specified VTEP using the
     * provided VtepDataClient.
     */
    protected final List<PhysicalPort> listPhysicalPorts(
            VtepDataClient vtepClient, IPv4Addr mgmtIp, int mgmtPort)
            throws StateAccessException {
        // Get the physical switch.
        PhysicalSwitch ps = getPhysicalSwitch(vtepClient, mgmtIp);
        if (ps == null) {
            throw new GatewayTimeoutHttpException(getMessage(
                    VTEP_INACCESSIBLE, mgmtIp, mgmtPort));
        }

        // TODO: Handle error if this fails or returns null.
        return vtepClient.listPhysicalPorts(ps.uuid);
    }


    /**
     * Gets the PhysicalPort named portName from the specified VTEP
     * using the provided VtepDataClient.
     */
    protected final PhysicalPort getPhysicalPort(VtepDataClient vtepClient,
            IPv4Addr mgmtIp, int mgmtPort, String portName)
            throws StateAccessException, SerializationException {
        // Find the requested port.
        List<PhysicalPort> pports =
                listPhysicalPorts(vtepClient, mgmtIp, mgmtPort);
        for (PhysicalPort pport : pports)
            if (pport.name.equals(portName))
                return pport;

        // Switch doesn't have the specified port.
        throw new NotFoundHttpException(getMessage(
                VTEP_PORT_NOT_FOUND, mgmtIp, mgmtPort, portName));
    }


    /**
     * Gets the ID of the bridge bound to the specified port and VLAN ID
     * on the specified VTEP.
     *
     * @param ipAddr VTEP's management IP address
     * @param portName Binding's port name
     * @param vlanId Binding's VLAN ID
     */
    public final java.util.UUID getBoundBridgeId(
            IPv4Addr ipAddr, String portName, short vlanId)
            throws SerializationException, StateAccessException {

        org.midonet.cluster.data.VtepBinding dataBinding;
        try {
            dataBinding = dataClient.vtepGetBinding(ipAddr, portName, vlanId);
        } catch (NoStatePathException ex) {
            throw new NotFoundHttpException(getMessage(VTEP_NOT_FOUND, ipAddr));
        }

        if (dataBinding == null) {
            throw new NotFoundHttpException(getMessage(
                    VTEP_BINDING_NOT_FOUND, ipAddr, vlanId, portName));
        }

        return dataBinding.getNetworkId();
    }



    /**
     * Get the VTEP bindings for the VTEP at IP ipAddrStr, optionally
     * filtering out bindings to bridges other than the one with ID
     * bridgeId.
     *
     * @param ipAddr VTEP's management IP address
     * @param bridgeId ID of bridge to get bindings for. Will return all
     *                 bindings if bridgeId is null.
     */
    public final List<VtepBinding> listVtepBindings(IPv4Addr ipAddr,
                                                    java.util.UUID bridgeId)
            throws SerializationException, StateAccessException {

        List<org.midonet.cluster.data.VtepBinding> dataBindings =
                dataClient.vtepGetBindings(ipAddr);
        List<VtepBinding> apiBindings = new ArrayList<>();
        for (org.midonet.cluster.data.VtepBinding dataBinding : dataBindings) {
            if (bridgeId == null ||
                    bridgeId.equals(dataBinding.getNetworkId())) {
                apiBindings.add(new VtepBinding(
                        ipAddr.toString(), dataBinding.getPortName(),
                        dataBinding.getVlanId(), dataBinding.getNetworkId()));
            }
        }

        return apiBindings;
    }

    /**
     * Private helper method for listVtepBindings that takes a
     * VtepDataClient and VTEP rather than a VTEP's management IP address.
     * This is so that operations which use listVtepBindings (e.g.
     * deletBinding) can reuse the VTEP and VtepDataClient.
     */
    private List<VtepBinding> listVtepBindings(VtepDataClient vtepClient,
            IPv4Addr mgmtIp, int mgmtPort, java.util.UUID bridgeId)
            throws SerializationException, StateAccessException {

        // Build map from OVSDB LogicalSwitch ID to Midonet Bridge ID.
        Map<UUID, java.util.UUID> lsToBridge = new HashMap<>();
        for (LogicalSwitch ls : vtepClient.listLogicalSwitches()) {
            lsToBridge.put(ls.uuid, logicalSwitchNameToBridgeId(ls.name));
        }

        List<VtepBinding> bindings = new ArrayList<>();
        for (PhysicalPort pp :
                listPhysicalPorts(vtepClient, mgmtIp, mgmtPort)) {
            for (Map.Entry<Integer, UUID> e : pp.vlanBindings.entrySet()) {

                java.util.UUID bindingBridgeId =
                        lsToBridge.get(e.getValue());
                // Ignore non-Midonet bindings and bindings to bridges
                // other than the requested one, if applicable.
                if (bindingBridgeId != null &&
                        (bridgeId == null ||
                                bridgeId.equals(bindingBridgeId))) {
                    VtepBinding b = new VtepBinding(
                            mgmtIp.toString(), pp.name,
                            e.getKey().shortValue(), bindingBridgeId);
                    bindings.add(b);
                }
            }
        }

        return bindings;
    }

    /**
     * Creates the specified binding on the VTEP at ipAddr.
     *
     * @param binding Binding to create.
     * @param ipAddr VTEP's management IP address.
     * @param bridge Binding's target bridge.
     */
    public final void createBinding(VtepBinding binding,
                                    IPv4Addr ipAddr, Bridge bridge)
            throws SerializationException, StateAccessException {

        VTEP vtep = getVtepOrThrow(ipAddr, true);

        // Get the VXLAN port and make sure it's not already bound
        // to another VTEP.
        Integer newPortVni = null;
        if (bridge.getVxLanPortId() != null) {
            VxLanPort vxlanPort =
                    (VxLanPort)dataClient.portsGet(bridge.getVxLanPortId());
            if (!vxlanPort.getMgmtIpAddr().equals(ipAddr)) {
                throw new ConflictHttpException(getMessage(
                        NETWORK_ALREADY_BOUND, binding.getNetworkId(), ipAddr));
            }
            log.info("Found VxLanPort {}, vni {}",
                    vxlanPort.getId(), vxlanPort.getVni());
        } else {
            // Need to create a VXLAN port.
            VtepDataClient vc = getVtepClient(vtep.getId(), vtep.getMgmtPort());
            List<PhysicalSwitch> physSwitches = vc.listPhysicalSwitches();
            IPv4Addr tunIp = vtep.getId(); // default to the VTEP's mgmt ip
            for (PhysicalSwitch ps : physSwitches) {
                if (ps.mgmtIps.contains(vtep.getId().toString())) {
                    if (!ps.tunnelIps.isEmpty()) {
                        tunIp = IPv4Addr.fromString(
                            ps.tunnelIps.iterator().next());
                        break;
                    }
                }
            }
            newPortVni = dataClient.getNewVni();
            VxLanPort vxlanPort = dataClient.bridgeCreateVxLanPort(
                    bridge.getId(), ipAddr, vtep.getMgmtPort(), newPortVni,
                    tunIp, vtep.getTunnelZoneId());
            log.debug("New VxLan port created, uuid: {}, vni: {}, tunIp: {}",
                      new Object[]{vxlanPort.getId(), newPortVni, tunIp});
        }

        // Create the binding in Midonet.
        dataClient.vtepAddBinding(
                ipAddr, binding.getPortName(),
                binding.getVlanId(), binding.getNetworkId());

        VtepDataClient vtepClient =
                getVtepClient(vtep.getId(), vtep.getMgmtPort());

        PhysicalPort pp = getPhysicalPort(vtepClient, vtep.getId(),
                                          vtep.getMgmtPort(),
                                          binding.getPortName());
        if (pp == null) {
            throw new NotFoundHttpException(getMessage(
                VTEP_PORT_NOT_FOUND, vtep.getId(), vtep.getMgmtPort(),
                binding.getPortName()
            ));
        }

        UUID lsUuid = pp.vlanBindings.get((int) binding.getVlanId());
        if (lsUuid != null) {
            // TODO: when the new getLogicalSwitch operation gets merged, we
            // should use it here to include it in the error message
            throw new ConflictHttpException(getMessage(
                VTEP_PORT_VLAN_PAIR_ALREADY_USED, vtep.getId(),
                vtep.getMgmtPort(), binding.getPortName(), binding.getVlanId()
            ));
        }

        // Create the logical switch on the VTEP if necessary.
        String lsName = bridgeIdToLogicalSwitchName(binding.getNetworkId());
        if (newPortVni != null) {
            StatusWithUuid status =
                    vtepClient.addLogicalSwitch(lsName, newPortVni);

            // Ignore CONFLICT error. It's fine if the LS already exists.
            if (status.getCode() != StatusCode.CONFLICT)
                throwIfFailed(status);


            // For all known macs, instruct the vtep to add a ucast
            // MAC entry to tunnel packets over to the right host.
            log.debug("Preseeding macs from bridge {}",
                    binding.getNetworkId());
            try {
                feedUcastRemote(vtepClient, binding.getNetworkId(), lsName);
            } catch (Exception ex) {
                log.error("Call to feedUcastRemote failed.", ex);
            }
        }

        // TODO: fill this list with all the host ips where we want
        // the VTEP to flood unknown dst mcasts. Not adding all host's
        // IPs because we'd send the same packet to all of them, so
        // for now we add none and will just populate ucasts.
        List<String> floodToIps = new ArrayList<>();
        Status status = vtepClient.bindVlan(lsName, binding.getPortName(),
                binding.getVlanId(), newPortVni, floodToIps);
        throwIfFailed(status);
    }

    /**
     * Deletes the binding for the specified port and VLAN ID from
     * the VTEP at ipAddr. Will also delete the VxLanPort on the
     * binding's target bridge if the VTEP has no other bindings for
     * that bridge.
     */
    public final void deleteBinding(IPv4Addr ipAddr,
                                    String portName, short vlanId)
            throws SerializationException, StateAccessException {

        VTEP vtep = getVtepOrThrow(ipAddr, true);
        VtepDataClient vtepClient = getVtepClient(ipAddr, vtep.getMgmtPort());

        // Go through all the bindings and find the one with the
        // specified portName and vlanId. Get its networkId, and
        // remember whether we saw any others with the same networkId.
        java.util.UUID affectedNwId = null;
        Set<java.util.UUID> boundLogicalSwitchUuids = new HashSet<>();
        List<VtepBinding> bindings = listVtepBindings(
                vtepClient, ipAddr, vtep.getMgmtPort(), null);
        for (VtepBinding binding : bindings) {
            java.util.UUID nwId = binding.getNetworkId();
            if (binding.getPortName().equals(portName) &&
                    binding.getVlanId() == vlanId) {
                affectedNwId = nwId;
            } else {
                boundLogicalSwitchUuids.add(nwId);
            }
        }

        if (affectedNwId == null) {
            log.warn("No bindings found for port {} and vlan {}",
                     portName, vlanId);
            throw new NotFoundHttpException(getMessage(
                    VTEP_BINDING_NOT_FOUND, ipAddr, vlanId, portName));
        }

        // Delete the binding in Midonet.
        dataClient.vtepDeleteBinding(ipAddr, portName, vlanId);

        // Delete the binding on the VTEP.
        Status st = vtepClient.deleteBinding(portName, vlanId);
        throwIfFailed(st);

        // If that was the only binding for this network, delete
        // the VXLAN port and logical switch.
        if (!boundLogicalSwitchUuids.contains(affectedNwId)) {
            deleteVxLanPort(vtepClient, affectedNwId);
        }

        log.debug("Delete binding on vtep {}, port {}, vlan {} completed",
                new Object[]{ipAddr, portName, vlanId});
    }

    public void deleteVxLanPort(VxLanPort vxLanPort)
            throws SerializationException, StateAccessException {
        deleteVxLanPort(getVtepClient(vxLanPort.getMgmtIpAddr(),
                                      vxLanPort.getMgmtPort()),
                        vxLanPort.getDeviceId());
    }

    private void deleteVxLanPort(VtepDataClient vtepClient,
                                 java.util.UUID networkId)
            throws SerializationException, StateAccessException {

        // Delete Midonet VXLAN port. This also deletes all
        // associated bindings.
        dataClient.bridgeDeleteVxLanPort(networkId);

        // Delete the corresponding logical switch on the VTEP.
        Status st = vtepClient.deleteLogicalSwitch(
            bridgeIdToLogicalSwitchName(networkId));
        throwIfFailed(st);
    }

    public void deleteVtep(IPv4Addr ipAddr) throws
            SerializationException, StateAccessException {
        // Delete the VTEP from Zookeeper. Need to fetch it first to
        // get the management port to connect to the actual VTEP.
        dataClient.vtepDelete(ipAddr);
    }

    /**
     * Converts the provided ODL Status object to the corresponding HTTP
     * exception and throws it. Does nothing if Status indicates success.
     */
    private void throwIfFailed(Status status) {

        if (status.isSuccess())
            return;

        switch(status.getCode()) {
            case BADREQUEST:
                throw new BadRequestHttpException(status.getDescription());
            case CONFLICT:
                throw new ConflictHttpException(status.getDescription());
            case NOTFOUND:
                throw new NotFoundHttpException(status.getDescription());
            default:
                log.error("Unexpected response from VTEP: " + status);
                throw new BadGatewayHttpException(status.getDescription());
        }
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
            throws StateAccessException, SerializationException {

        Map<VlanMacPort, IPv4Addr> mappings =
            dataClient.bridgeGetMacPortsWithVxTunnelEndpoint(bridgeId);

        for (Map.Entry<VlanMacPort, IPv4Addr> e : mappings.entrySet()) {
            VlanMacPort vmp = e.getKey();
            IPv4Addr hostIp = e.getValue();
            if (hostIp == null) {
                log.warn("No VxLAN tunnel endpoint for port {}", vmp.portId);
            } else {
                log.debug("MAC {} is at host {}", vmp.macAddress, hostIp);
                vtepClient.addUcastMacRemote(lsName, vmp.macAddress.toString(),
                                             hostIp.toString());
            }
        }
    }

}
