/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.api.vtep;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.google.inject.Inject;

import org.opendaylight.controller.sal.utils.Status;
import org.opendaylight.controller.sal.utils.StatusCode;
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
import org.midonet.brain.southbound.vtep.model.PhysicalPort;
import org.midonet.brain.southbound.vtep.model.PhysicalSwitch;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.data.Bridge;
import org.midonet.cluster.data.VTEP;
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
import static org.midonet.api.validation.MessageProperty.VTEP_TUNNEL_IP_NOT_FOUND;
import static org.midonet.api.validation.MessageProperty.getMessage;
import static org.midonet.brain.southbound.vtep.VtepConstants.bridgeIdToLogicalSwitchName;

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
     * Creates a VTEP client and opens a connection to the VTEP with the
     * specified management IP address and port.
     *
     * @throws GatewayTimeoutHttpException when failing to connect to the VTEP.
     */
    private VtepDataClient getVtepClient(IPv4Addr mgmtIp, int mgmtPort) {
        try {
            return provider.getClient(mgmtIp, mgmtPort);
        } catch (Exception ex) {
            log.warn("Unable to connect to VTEP: ", ex);
            throw new GatewayTimeoutHttpException(
                    getMessage(VTEP_INACCESSIBLE, mgmtIp, mgmtPort), ex);
        }
    }

    /**
     * Creates a VTEP client and opens a connection to the specified VTEP. The
     * method updates the missing VTEP configuration.
     *
     * @throws GatewayTimeoutHttpException when failing to connect to the VTEP.
     */
    private VtepDataClient getVtepClientAndUpdate(VTEP vtep) {
        VtepDataClient client = getVtepClient(vtep.getId(), vtep.getMgmtPort());
        IPv4Addr tunnelIp = client.getTunnelIp();
        if (!Objects.equals(vtep.getTunnelIp(), tunnelIp)) {
            try {
                dataClient.vtepUpdate(vtep.setTunnelIp(tunnelIp));
            } catch (StateAccessException | SerializationException e) {
                log.warn("Failed to update VTEP configuration: {}",
                         e.getMessage());
            }
        }
        return client;
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
        VtepDataClient vtepClient = getVtepClientAndUpdate(vtep);

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
    protected final PhysicalPort getPhysicalPortOrThrow(
        VtepDataClient vtepClient,
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
     * Creates the specified binding on the VTEP at ipAddr.
     *
     * We will store the configuration first in ZK. Additionally, if this is not
     * the first binding of the given bridge we will also add the binding to the
     * VTEP db. First bindings are completely handled by the VxGW Service, and
     * will fail if the VTEP is unreachable.
     *
     * If the VTEP is reachable, we will also validate that the physical port is
     * present in it.
     *
     * When this is not the first binding of the bridge we will try to access
     * the VTEP database to inject the new binding. However, when it is the
     * first binding of the bridge, all configuration (adding a logical switch
     * and the binding) will be done by the VTEP.
     *
     * TODO: remove all writes to the VTEP and make the VxGwService deal with it
     *
     * @param binding Binding to create.
     * @param mgmtIp VTEP's management IP address.
     * @param bridge Binding's target bridge.
     *
     * @throws org.midonet.api.rest_api.GatewayTimeoutHttpException when the
     * VTEP is unreachable and this prevents us from completing the operation
     * successfully.
     * @throws org.midonet.api.rest_api.ConflictHttpException if the binding
     * already exists in the Midonet store.
     * @throws org.midonet.api.rest_api.NotFoundHttpException when the physical
     * port is not present in the VTEP (only checked if the VTEP is reachable).
     */
    public final void createBinding(VtepBinding binding, IPv4Addr mgmtIp,
                                    Bridge bridge)
            throws SerializationException, StateAccessException {

        // Let's see if the bridge is already bound to a VTEP
        if (bridge.getVxLanPortId() == null) {
            createFirstNetworkBinding(mgmtIp, bridge, binding);
        } else {
            VxLanPort vxlanPort =
                (VxLanPort)dataClient.portsGet(bridge.getVxLanPortId());
            if (!vxlanPort.getMgmtIpAddr().equals(mgmtIp)) {
                throw new ConflictHttpException(getMessage(
                    NETWORK_ALREADY_BOUND, binding.getNetworkId(), mgmtIp));
            }
            createAdditionalNetworkBinding(mgmtIp, bridge, vxlanPort, binding);
        }
    }

    /**
     * Adds a new binding to a bridge that *already* has at least one binding
     * to the VTEP.
     */
    private void createAdditionalNetworkBinding(IPv4Addr mgmtIp,
                                                Bridge bridge,
                                                VxLanPort vxlanPort,
                                                VtepBinding binding)
        throws SerializationException, StateAccessException {

        log.info("Adding binding for network {} to VTEP {} with VNI {}",
                 bridge.getId(), mgmtIp, vxlanPort.getVni());

        VTEP vtep = getVtepOrThrow(mgmtIp, true);
        int mgmtPort = vtep.getMgmtPort();

        // Try to validate the port if the VTEP is reachable
        VtepDataClient vtepCli;
        try {
            vtepCli = getVtepClientAndUpdate(vtep);
            getPhysicalPortOrThrow(vtepCli, mgmtIp, mgmtPort,
                                   binding.getPortName());
        } catch (GatewayTimeoutHttpException e) {
            log.warn("VTEP {} unreachable; continue without validating "
                     + "physical port", mgmtIp);
            vtepCli = null;
        }

        // Create the binding in Midonet, or throw with a Conflict HTTP error
        tryStoreBinding(mgmtIp, mgmtPort, binding.getPortName(),
                        binding.getVlanId(), binding.getNetworkId());

        // Try to store the binding in the VTEP if it's reachable
        if (null == vtepCli) {
            log.warn("Binding stored in Midonet, but could not be written to "
                     + "VTEP {}, will be done by VxLanGatewayService", mgmtIp);
        } else {
            String lsName = bridgeIdToLogicalSwitchName(bridge.getId());
            Status status = vtepCli.bindVlan(lsName, binding.getPortName(),
                                             binding.getVlanId(),
                                             vxlanPort.getVni(),
                                             new ArrayList<IPv4Addr>());
            if (StatusCode.CONFLICT.equals(status.getCode())) {
                log.warn("Binding was already present in VTEP");
            } else if (status.isSuccess()) {
                log.warn("Binding persisted, but could not be written to "
                         + "VTEP {}, relying on VxlanGatewayService to "
                         + "consolidate", status);
            }
        }
    }

    /**
     * Performs the FIRST binding of a bridge to a VTEP. It will require that
     * the VTEP is reachable so we can extract the tunnel IP and verify the
     * physical port existence. But we will not write to the VTEP, that will be
     * left to the VxGwService.
     */
    private void createFirstNetworkBinding(IPv4Addr mgmtIp, Bridge bridge,
                                           VtepBinding binding)

        throws SerializationException, StateAccessException {

        log.info("Create first binding for network {} to VTEP {}",
                 bridge.getId(), mgmtIp);

        VTEP vtep = getVtepOrThrow(mgmtIp, true);
        int mgmtPort = vtep.getMgmtPort();
        IPv4Addr tunnelIp = vtep.getTunnelIp();
        try {
            // Try to connect to the VTEP to get the latest tunnel IP.
            VtepDataClient vtepCli = getVtepClientAndUpdate(vtep);
            tunnelIp = vtepCli.getTunnelIp();

            // Validate that the physical port does exist
            getPhysicalPortOrThrow(vtepCli, mgmtIp, mgmtPort,
                                   binding.getPortName());

        } catch (GatewayTimeoutHttpException e) {
            log.warn("VTEP {} unreachable to create binding: using the "
                     + "default tunnel IP and no checking of the physical port",
                     mgmtIp);
        }

        // Validate there exists a tunnel IP either in the VTEP or ZK
        if (null == tunnelIp) {
            throw new NotFoundHttpException(
                getMessage(VTEP_TUNNEL_IP_NOT_FOUND, mgmtIp, mgmtPort,
                           binding.getPortName()
                )
            );
        }

        // Create the VXLAN port.
        VxLanPort vxlanPort = dataClient.bridgeCreateVxLanPort(
            bridge.getId(), mgmtIp, vtep.getMgmtPort(),
            dataClient.getNewVni(), tunnelIp, vtep.getTunnelZoneId());

        try {
            tryStoreBinding(mgmtIp, mgmtPort, binding.getPortName(),
                            binding.getVlanId(), binding.getNetworkId());
        } catch (ConflictHttpException e) {
            dataClient.bridgeDeleteVxLanPort(bridge.getId()); // rollback
            throw e;
        }

        log.debug("First binding of network {} to VTEP {}. VxlanPort in " +
                  "bridge has id {}, vni: {}, vtep tunnelIp {}. Delegating" +
                  "VTEP config on the VxlanGatewayService",
                  bridge.getId(), mgmtIp, vxlanPort.getId(), vxlanPort.getVni(),
                  vxlanPort.getTunnelIp());
    }

    /**
     * Tries to store a binding in the Midonet store, but will throw if the
     * port/vlan is already taken by any bridge (including the given one)
     *
     * @throws org.midonet.api.rest_api.ConflictHttpException if the binding
     * already exsts.
     */
    private void tryStoreBinding(IPv4Addr mgmtIp, int mgmtPort,
                                 String physPortName, short vlanId,
                                 java.util.UUID bridgeId)
        throws StateAccessException {
        List<org.midonet.cluster.data.VtepBinding>
            bindings = dataClient.vtepGetBindings(mgmtIp);

        for (org.midonet.cluster.data.VtepBinding binding : bindings) {
            if (binding.getVlanId() == vlanId &&
                binding.getPortName().equals(physPortName)) {
                throw new ConflictHttpException(getMessage(
                    VTEP_PORT_VLAN_PAIR_ALREADY_USED, mgmtIp, mgmtPort,
                    physPortName, vlanId, binding.getNetworkId()
                ));
            }
        }
        dataClient.vtepAddBinding(mgmtIp, physPortName, vlanId, bridgeId);
    }

    /**
     * Deletes the binding for the specified port and VLAN ID from
     * the VTEP at ipAddr. Will also delete the VxLanPort on the
     * binding's target bridge if the VTEP has no other bindings for
     * that bridge.
     */
    public final void deleteBinding(IPv4Addr mgmtIp,
                                    String portName, short vlan)
            throws SerializationException, StateAccessException {

        org.midonet.cluster.data.VtepBinding binding =
            dataClient.vtepGetBinding(mgmtIp, portName, vlan);

        if (binding == null) {
            log.warn("Binding to port {}, vlan {} not found", portName, vlan);
            throw new NotFoundHttpException(getMessage(VTEP_BINDING_NOT_FOUND,
                                                       mgmtIp, vlan, portName));
        }

        java.util.UUID bridgeId = binding.getNetworkId();

        dataClient.vtepDeleteBinding(mgmtIp, portName, vlan);
        boolean lastBinding = dataClient.bridgeGetVtepBindings(bridgeId).isEmpty();
        if (lastBinding) {
            dataClient.bridgeDeleteVxLanPort(bridgeId);
        }

        log.debug("Delete binding on VTEP {}, port {}, VLAN {} persisted",
                  mgmtIp, portName, vlan);

        // Now try to write it to the VTEP
        VTEP vtep = getVtepOrThrow(mgmtIp, true);
        VtepDataClient vtepClient;
        try {
            vtepClient = getVtepClientAndUpdate(vtep);
        } catch (GatewayTimeoutHttpException e) {
            log.warn("VTEP {} unreachable but binding deletion is persisted, " +
                     "VxLanGatewayService will consolidate state", mgmtIp);
            return;
        }

        // Delete the binding on the VTEP
        Status st = vtepClient.deleteBinding(portName, vlan);
        if (!st.isSuccess()) {
            log.warn("Error deleting binding from VTEP {}", st);
        }
        if (lastBinding && !st.getCode().equals(StatusCode.NOSERVICE)) {
            String lsName = bridgeIdToLogicalSwitchName(bridgeId);
            st = vtepClient.deleteLogicalSwitch(lsName);
            if (!st.isSuccess()) {
                log.warn("Error deleting logical switch from VTEP {}", st);
            }
        }
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
        String ls = bridgeIdToLogicalSwitchName(networkId);
        Status st = vtepClient.deleteLogicalSwitch(ls);
        if (st.getCode() == StatusCode.NOTFOUND) {
            log.warn("Logical Switch {} was already gone from the VTEP", ls);
        } else {
            throwIfFailed(st);
        }
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
}
