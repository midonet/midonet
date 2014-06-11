/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.api.vtep;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import com.google.inject.Inject;

import org.opendaylight.controller.sal.utils.Status;
import org.opendaylight.ovsdb.lib.notation.UUID;
import org.opendaylight.ovsdb.plugin.StatusWithUuid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.api.network.VTEPBinding;
import org.midonet.api.network.VTEPPort;
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
import org.midonet.cluster.data.Port;
import org.midonet.cluster.data.VTEP;
import org.midonet.cluster.data.ports.BridgePort;
import org.midonet.cluster.data.ports.VlanMacPort;
import org.midonet.cluster.data.ports.VxLanPort;
import org.midonet.midolman.serialization.SerializationException;
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
    public static final Random rand = new Random();

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
        VtepDataClient vtepClient = provider.get();
        try {
            vtepClient.connect(mgmtIp, mgmtPort);
            return vtepClient;
        } catch (IllegalStateException ex) {
            log.warn("Unable to connect to VTEP: ", ex);
            throw new GatewayTimeoutHttpException(getMessage(VTEP_INACCESSIBLE, mgmtIp, mgmtPort),
                ex);
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
        try {
            return getPhysicalSwitch(client, mgmtIp);
        } finally {
            client.disconnect();
        }
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
        try {
            List<PhysicalPort> pports =
                    listPhysicalPorts(vtepClient, ipAddr, vtep.getMgmtPort());
            List<VTEPPort> vtepPorts = new ArrayList<>(pports.size());
            for (PhysicalPort pport : pports)
                vtepPorts.add(new VTEPPort(pport.name, pport.description));
            return vtepPorts;
        } finally {
            vtepClient.disconnect();
        }
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

        VTEP vtep = getVtepOrThrow(ipAddr, false);
        VtepDataClient vtepClient =
                getVtepClient(vtep.getId(), vtep.getMgmtPort());

        try {
            PhysicalPort pp = getPhysicalPort(vtepClient, vtep.getId(),
                                              vtep.getMgmtPort(), portName);
            UUID lsUuid = pp.vlanBindings.get((int)vlanId);
            if (lsUuid == null) {
                throw new NotFoundHttpException(
                        getMessage(VTEP_BINDING_NOT_FOUND,
                                vtep.getId(), vlanId, portName));
            }

            for (LogicalSwitch lswitch : vtepClient.listLogicalSwitches()) {
                if (lswitch.uuid.equals(lsUuid))
                    return logicalSwitchNameToBridgeId(lswitch.name);
            }

            throw new IllegalStateException("Logical switch with ID " + lsUuid +
                    " should exist but was not returned from VTEP client.");
        } finally {
            vtepClient.disconnect();
        }
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
    public final List<VTEPBinding> listVtepBindings(IPv4Addr ipAddr,
                                                    java.util.UUID bridgeId)
            throws SerializationException, StateAccessException {

        // Get VTEP client.
        VTEP vtep = getVtepOrThrow(ipAddr, false);
        VtepDataClient vtepClient =
                getVtepClient(vtep.getId(), vtep.getMgmtPort());
        try {
            return listVtepBindings(vtepClient, ipAddr,
                                    vtep.getMgmtPort(), bridgeId);
        } finally {
            vtepClient.disconnect();
        }
    }

    /**
     * Private helper method for listVtepBindings that takes a
     * VtepDataClient and VTEP rather than a VTEP's management IP address.
     * This is so that operations which use listVtepBindings (e.g.
     * deletBinding) can reuse the VTEP and VtepDataClient.
     */
    private List<VTEPBinding> listVtepBindings(VtepDataClient vtepClient,
            IPv4Addr mgmtIp, int mgmtPort, java.util.UUID bridgeId)
            throws SerializationException, StateAccessException {

        // Build map from OVSDB LogicalSwitch ID to Midonet Bridge ID.
        Map<UUID, java.util.UUID> lsToBridge = new HashMap<>();
        for (LogicalSwitch ls : vtepClient.listLogicalSwitches()) {
            lsToBridge.put(ls.uuid, logicalSwitchNameToBridgeId(ls.name));
        }

        List<VTEPBinding> bindings = new ArrayList<>();
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
                    VTEPBinding b = new VTEPBinding(
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
    public final void createBinding(VTEPBinding binding,
                                    IPv4Addr ipAddr, Bridge bridge)
            throws SerializationException, StateAccessException {

        // Get the VXLAN port and make sure it's not already bound
        // to another VTEP.
        if (bridge.getVxLanPortId() != null) {
            VxLanPort vxlanPort =
                    (VxLanPort)dataClient.portsGet(bridge.getVxLanPortId());
            if (!vxlanPort.getMgmtIpAddr().equals(ipAddr)) {
                throw new ConflictHttpException(getMessage(
                        NETWORK_ALREADY_BOUND, binding.getNetworkId(), ipAddr));
            }
            log.info("Found VxLanPort {}, vni {}",
                    vxlanPort.getId(), vxlanPort.getVni());
        }

        VTEP vtep = getVtepOrThrow(ipAddr, true);
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

        UUID lsUuid = pp.vlanBindings.get((int)binding.getVlanId());
        if (lsUuid != null) {
            // TODO: when the new getLogicalSwitch operation gets merged, we
            // should use it here to include it in the error message
            throw new ConflictHttpException(getMessage(
                VTEP_PORT_VLAN_PAIR_ALREADY_USED, vtep.getId(),
                vtep.getMgmtPort(), binding.getPortName(), binding.getVlanId()
            ));
        }

        try {
            Integer newPortVni = null;
            String lsName = bridgeIdToLogicalSwitchName(binding.getNetworkId());
            if (bridge.getVxLanPortId() == null) {
                newPortVni = rand.nextInt((1 << 24) - 1) + 1; // TODO: unique?
                // TODO: Make VTEP client take UUID instead of name.
                StatusWithUuid status =
                        vtepClient.addLogicalSwitch(lsName, newPortVni);
                throwIfFailed(status);
            }

            // TODO: fill this list with all the host ips where we want
            // the VTEP to flood unknown dst mcasts. Not adding all host's
            // IPs because we'd send the same packet to all of them, so
            // for now we add none and will just populate ucasts.
            List<String> floodToIps = new ArrayList<>();

            Status status = vtepClient.bindVlan(lsName, binding.getPortName(),
                    binding.getVlanId(), newPortVni, floodToIps);
            throwIfFailed(status);

            // For all known macs, instruct the vtep to add a ucast
            // MAC entry to tunnel packets over to the right host.
            if (newPortVni != null) {
                log.debug("Preseeding macs from bridge {}",
                        binding.getNetworkId());
                VxLanPort vxlanPort = dataClient.bridgeCreateVxLanPort(
                        bridge.getId(), ipAddr, vtep.getMgmtPort(), newPortVni);
                feedUcastRemote(vtepClient, binding.getNetworkId(), lsName);
                log.debug("New VxLan port created, uuid: {}, vni: {}",
                        vxlanPort.getId(), newPortVni);
            }
        } finally {
            vtepClient.disconnect();
        }
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
        try {
            // Go through all the bindings and find the one with the
            // specified portName and vlanId. Get its networkId, and
            // remember whether we saw any others with the same networkId.
            java.util.UUID affectedNwId = null;
            Set<java.util.UUID> boundLogicalSwitchUuids = new HashSet<>();
            List<VTEPBinding> bindings = listVtepBindings(
                    vtepClient, ipAddr, vtep.getMgmtPort(), null);
            for (VTEPBinding binding : bindings) {
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

            // Delete the binding.
            Status st = vtepClient.deleteBinding(portName, vlanId);
            throwIfFailed(st);

            // If that was the only binding for this network, delete
            // the VXLAN port.
            if (!boundLogicalSwitchUuids.contains(affectedNwId)) {
                dataClient.bridgeDeleteVxLanPort(affectedNwId);
            }

            log.debug("Delete binding on vtep {}, port {}, vlan {} completed",
                    new Object[]{ipAddr, portName, vlanId});
        } finally {
            vtepClient.disconnect();
        }
    }

    public void deleteVxLanPort(VxLanPort vxLanPort)
            throws SerializationException, StateAccessException {

        VtepDataClient vtepClient = getVtepClient(vxLanPort.getMgmtIpAddr(),
                                                  vxLanPort.getMgmtPort());
        try {
            Status st = vtepClient.deleteLogicalSwitch(
                    bridgeIdToLogicalSwitchName(vxLanPort.getDeviceId()));
            throwIfFailed(st);

            dataClient.bridgeDeleteVxLanPort(vxLanPort.getDeviceId());
        } finally {
            vtepClient.disconnect();
        }
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
        for (VlanMacPort vmp : dataClient.bridgeGetMacPorts(bridgeId)) {
            Port<?, ?> p = dataClient.portsGet(vmp.portId);
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

}
