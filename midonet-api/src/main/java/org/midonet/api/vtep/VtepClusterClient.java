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
package org.midonet.api.vtep;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.google.inject.Inject;

import org.opendaylight.controller.sal.utils.Status;
import org.opendaylight.controller.sal.utils.StatusCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.api.network.VTEPPort;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.data.Bridge;
import org.midonet.cluster.data.VTEP;
import org.midonet.cluster.data.ports.VxLanPort;
import org.midonet.cluster.data.vtep.VtepNotConnectedException;
import org.midonet.cluster.data.vtep.VtepStateException;
import org.midonet.cluster.data.vtep.model.LogicalSwitch;
import org.midonet.cluster.data.vtep.model.PhysicalPort;
import org.midonet.cluster.data.vtep.model.PhysicalSwitch;
import org.midonet.cluster.rest_api.BadGatewayHttpException;
import org.midonet.cluster.rest_api.BadRequestHttpException;
import org.midonet.cluster.rest_api.ConflictHttpException;
import org.midonet.cluster.rest_api.GatewayTimeoutHttpException;
import org.midonet.cluster.rest_api.NotFoundHttpException;
import org.midonet.cluster.rest_api.models.VTEPBinding;
import org.midonet.cluster.southbound.vtep.VtepDataClient;
import org.midonet.cluster.southbound.vtep.VtepDataClientFactory;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.NoStatePathException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.packets.IPv4Addr;

import static org.midonet.cluster.rest_api.validation.MessageProperty.VTEP_BINDING_NOT_FOUND;
import static org.midonet.cluster.rest_api.validation.MessageProperty.VTEP_INACCESSIBLE;
import static org.midonet.cluster.rest_api.validation.MessageProperty.VTEP_MUST_USE_SAME_TUNNEL_ZONE;
import static org.midonet.cluster.rest_api.validation.MessageProperty.VTEP_NOT_FOUND;
import static org.midonet.cluster.rest_api.validation.MessageProperty.VTEP_PORT_NOT_FOUND;
import static org.midonet.cluster.rest_api.validation.MessageProperty.VTEP_PORT_VLAN_PAIR_ALREADY_USED;
import static org.midonet.cluster.rest_api.validation.MessageProperty.VTEP_TUNNEL_IP_NOT_FOUND;
import static org.midonet.cluster.rest_api.validation.MessageProperty.getMessage;
import static org.midonet.cluster.southbound.vtep.VtepConstants.bridgeIdToLogicalSwitchName;
import static org.midonet.cluster.southbound.vtep.VtepConstants.logicalSwitchNameToBridgeId;
import static scala.collection.JavaConversions.mapAsJavaMap;

/**
 * Coordinates VtepDataClient and DataClient (Zookeeper) operations.
 */
public class VtepClusterClient {

    private static final Logger log =
            LoggerFactory.getLogger(VtepClusterClient.class);

    private final VtepDataClientFactory provider;
    private final DataClient dataClient;

    private final UUID clientId = UUID.randomUUID();

    @Inject
    public VtepClusterClient(DataClient dataClient,
                             VtepDataClientFactory provider) {
        this.dataClient = dataClient;
        this.provider = provider;
    }

    /**
     * Creates a VTEP client and opens a connection to the VTEP with the
     * specified management IP address and port.
     *
     * @throws GatewayTimeoutHttpException when failing to connect to the VTEP.
     */
    private VtepDataClient connect(IPv4Addr mgmtIp, int mgmtPort) {
        try {
            return provider.connect(mgmtIp, mgmtPort, clientId)
                .awaitConnected();
        } catch (VtepStateException e) {
            log.warn("Unable to connect to VTEP {}:{}", mgmtIp, mgmtPort);
            throw new GatewayTimeoutHttpException(
                    getMessage(VTEP_INACCESSIBLE, mgmtIp, mgmtPort), e);
        } catch (IllegalArgumentException e) {
            log.error("Unsupported endpoint for mock VTEP: {}:{}",
                      mgmtIp, mgmtPort);
            throw new GatewayTimeoutHttpException(
                getMessage(VTEP_INACCESSIBLE, mgmtIp, mgmtPort), e);
        }
    }

    /**
     * Disconnects the VTEP client.
     */
    private void disconnect(VtepDataClient client) {
        if (null == client)
            return;
        try {
            client.disconnect(clientId, true);
        } catch (VtepStateException ex) {
            log.debug("Unable to disconnect from VTEP: {}", client, ex);
        }
    }

    /**
     * Creates a VTEP client and opens a connection to the specified VTEP. The
     * method updates the missing VTEP configuration.
     *
     * @throws GatewayTimeoutHttpException when failing to connect to the VTEP.
     */
    private VtepDataClient connectAndUpdate(VTEP vtep) {
        VtepDataClient client = connect(vtep.getId(), vtep.getMgmtPort());
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
                                                  int mgmtPort)
        throws VtepNotConnectedException {
        VtepDataClient client = connect(mgmtIp, mgmtPort);
        try {
            return getPhysicalSwitch(client, mgmtIp);
        } finally {
            disconnect(client);
        }
    }

    /**
     * Gets the PhysicalSwitch record with the specified managementIp
     * address using the provided VtepDataClient.
     */
    protected final PhysicalSwitch getPhysicalSwitch(VtepDataClient vtepClient,
                                                     IPv4Addr mgmtIp)
        throws VtepNotConnectedException {
        Collection<PhysicalSwitch> switches = vtepClient.listPhysicalSwitches();

        for (PhysicalSwitch ps : switches)
            if (ps.mgmtIps() != null && ps.mgmtIps().contains(mgmtIp))
                return ps;

        return null;
    }

    public final List<VTEPPort> listPorts(IPv4Addr ipAddr)
            throws SerializationException, StateAccessException {
        VTEP vtep = getVtepOrThrow(ipAddr, false);
        VtepDataClient client = connectAndUpdate(vtep);

        try {
            Collection<PhysicalPort> pports =
                listPhysicalPorts(client, ipAddr, vtep.getMgmtPort());
            List<VTEPPort> vtepPorts = new ArrayList<>(pports.size());
            for (PhysicalPort pport : pports)
                vtepPorts.add(new VTEPPort(pport.name(), pport.description()));
            return vtepPorts;
        } catch (VtepNotConnectedException e) {
            throw new GatewayTimeoutHttpException(
                getMessage(VTEP_INACCESSIBLE, ipAddr, vtep.getMgmtPort()), e);
        } finally {
            disconnect(client);
        }
    }

    /**
     * Gets a list of PhysicalPorts belonging to the specified VTEP using the
     * provided VtepDataClient.
     */
    protected final Collection<PhysicalPort> listPhysicalPorts(
            VtepDataClient vtepClient, IPv4Addr mgmtIp, int mgmtPort)
            throws StateAccessException, VtepNotConnectedException {
        // Get the physical switch.
        PhysicalSwitch ps = getPhysicalSwitch(vtepClient, mgmtIp);
        if (ps == null) {
            throw new GatewayTimeoutHttpException(getMessage(
                VTEP_INACCESSIBLE, mgmtIp, mgmtPort));
        }

        // TODO: Handle error if this fails or returns null.
        return vtepClient.listPhysicalPorts(ps.uuid());
    }


    /**
     * Gets the PhysicalPort named portName from the specified VTEP
     * using the provided VtepDataClient.
     */
    protected final PhysicalPort getPhysicalPortOrThrow(
        VtepDataClient vtepClient,
        IPv4Addr mgmtIp, int mgmtPort, String portName)
            throws StateAccessException, SerializationException,
                   VtepNotConnectedException {

        // Find the requested port.
        for (PhysicalPort port : listPhysicalPorts(vtepClient, mgmtIp, mgmtPort))
            if (port.name().equals(portName))
                return port;

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
    public final UUID getBoundBridgeId(
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
    public final List<VTEPBinding> listVtepBindings(IPv4Addr ipAddr,
                                                    UUID bridgeId)
            throws SerializationException, StateAccessException {

        List<org.midonet.cluster.data.VtepBinding> dataBindings =
                dataClient.vtepGetBindings(ipAddr);
        List<VTEPBinding> apiBindings = new ArrayList<>();
        for (org.midonet.cluster.data.VtepBinding dataBinding : dataBindings) {
            if (bridgeId == null ||
                    bridgeId.equals(dataBinding.getNetworkId())) {
                VTEPBinding b = new VTEPBinding();
                b.mgmtIp = ipAddr.toString();
                b.portName = dataBinding.getPortName();
                b.vlanId = dataBinding.getVlanId();
                b.networkId = dataBinding.getNetworkId();
                apiBindings.add(b);
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
    @SuppressWarnings("unused")
    private List<VTEPBinding> listVtepBindings(VtepDataClient vtepClient,
            IPv4Addr mgmtIp, int mgmtPort, UUID bridgeId)
            throws SerializationException, StateAccessException,
                   VtepNotConnectedException {

        // Build map from OVSDB LogicalSwitch ID to Midonet Bridge ID.
        Map<UUID, UUID> lsToBridge = new HashMap<>();
        for (LogicalSwitch ls : vtepClient.listLogicalSwitches()) {
            lsToBridge.put(ls.uuid(), logicalSwitchNameToBridgeId(ls.name()));
        }

        List<VTEPBinding> bindings = new ArrayList<>();
        for (PhysicalPort pp :
                listPhysicalPorts(vtepClient, mgmtIp, mgmtPort)) {
            for (Map.Entry<Integer, UUID> e :
                mapAsJavaMap(pp.vlanBindings()).entrySet()) {

                UUID bindingBridgeId = lsToBridge.get(e.getValue());
                // Ignore non-Midonet bindings and bindings to bridges
                // other than the requested one, if applicable.
                if (bindingBridgeId != null &&
                        (bridgeId == null ||
                                bridgeId.equals(bindingBridgeId))) {
                    VTEPBinding b = new VTEPBinding();
                    b.mgmtIp = mgmtIp.toString();
                    b.portName = pp.name();
                    b.vlanId = e.getKey().shortValue();
                    b.networkId = bindingBridgeId;
                    bindings.add(b);
                }
            }
        }

        return bindings;
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
     * @throws GatewayTimeoutHttpException when the VTEP is unreachable and
     * this prevents us from completing the operation successfully.
     * @throws ConflictHttpException if the binding already exists in the
     * MidoNet store.
     * @throws NotFoundHttpException when the physical port is not present in
     * the VTEP (only checked if the VTEP is reachable).
     */
    public final void createBinding(VTEPBinding binding, IPv4Addr mgmtIp,
                                    Bridge bridge)
            throws SerializationException, StateAccessException {

        // Get the VTEP
        VTEP vtep = getVtepOrThrow(mgmtIp, true);

        try {
            // We're assuming here that Bridge has been retrieved migrating the
            // data in old vxlanPortId property to vxlanPortIds, this should've
            // been done by the BridgeZkManager. We therefore don't need to
            // inspect vxlanPortId at all.
            List<UUID> vxlanPortIds = bridge.getVxLanPortIds();
            VxLanPort vxlanPort = null;  // for the vtep of the new binding
            UUID tzId = null;
            if (vxlanPortIds != null) {
                for (UUID id : vxlanPortIds) {
                    VxLanPort port = (VxLanPort) dataClient.portsGet(id);
                    tzId = port.getTunnelZoneId();
                    if (port.getMgmtIpAddr().equals(mgmtIp)) {
                        // got the same vtep, not the first binding then
                        vxlanPort = port;
                        break;
                    }
                }
            }

            if (vxlanPort == null) {
                log.debug("First binding to VTEP at {}", mgmtIp);
                // For new VTEPs, let's validate the tunnel zone
                if (tzId != null && !vtep.getTunnelZoneId().equals(tzId)) {
                    log.error("Tunnel zones must match");
                    throw new BadRequestHttpException(
                        getMessage(VTEP_MUST_USE_SAME_TUNNEL_ZONE, tzId)
                    );
                }
                createFirstNetworkBinding(vtep, bridge, binding);
            } else {
                log.debug("Additional binding to VTEP at {}", mgmtIp);
                createAdditionalNetworkBinding(vtep, bridge, vxlanPort, binding);
            }

        } catch (VtepNotConnectedException e) {
            throw new GatewayTimeoutHttpException(
                getMessage(VTEP_INACCESSIBLE, mgmtIp, vtep.getMgmtPort()), e);
        }
    }

    /**
     * Adds a new binding to a bridge that *already* has at least one binding
     * to the VTEP.
     */
    private void createAdditionalNetworkBinding(VTEP vtep,
                                                Bridge bridge,
                                                VxLanPort vxlanPort,
                                                VTEPBinding binding)
        throws SerializationException, StateAccessException,
               VtepNotConnectedException {

        IPv4Addr mgmtIp = vtep.getId();
        int mgmtPort = vtep.getMgmtPort();

        log.info("Adding a new binding to network {} to VTEP {} with VNI {}",
                 bridge.getId(), mgmtIp, vxlanPort.getVni());

        // Try to validate the port if the VTEP is reachable.
        VtepDataClient client = null;
        try {
            client = connectAndUpdate(vtep);
        } catch (GatewayTimeoutHttpException e) {
            log.warn("VTEP {} unreachable; will try to continue without " +
                     "validating physical port..", mgmtIp);
        }

        try {
            getPhysicalPortOrThrow(client, mgmtIp, mgmtPort, binding.portName);

            // Create the binding in Midonet or throw with a Conflict HTTP error
            tryStoreBinding(mgmtIp, mgmtPort, binding.portName, binding.vlanId,
                            binding.networkId);

            // Try to store the binding in the VTEP if it's reachable
            if (client == null) {
                log.warn(
                    "Binding stored in Midonet, but could not be written to "
                    + "VTEP {}, will be done by VxLanGatewayService", mgmtIp);
            } else {
                String lsName = bridgeIdToLogicalSwitchName(bridge.getId());
                Status status = client.bindVlan(lsName, binding.portName,
                                                binding.vlanId,
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
        } finally {
            disconnect(client);
        }
    }

    /**
     * Performs the FIRST binding of a bridge to a VTEP. It will require that
     * the VTEP is reachable so we can extract the tunnel IP and verify the
     * physical port existence. But we will not write to the VTEP, that will be
     * left to the VxGwService.
     */
    private void createFirstNetworkBinding(VTEP vtep, Bridge bridge,
                                           VTEPBinding binding)
        throws SerializationException, StateAccessException,
               VtepNotConnectedException {

        IPv4Addr mgmtIp = vtep.getId();
        int mgmtPort = vtep.getMgmtPort();
        IPv4Addr tunnelIp = vtep.getTunnelIp();

        VtepDataClient client = null;
        try {
            // Try to connect to the VTEP to get the latest tunnel IP.
            client = connectAndUpdate(vtep);
            tunnelIp = client.getTunnelIp();

            // Validate that the physical port does exist
            getPhysicalPortOrThrow(client, mgmtIp, mgmtPort, binding.portName);

        } catch (GatewayTimeoutHttpException e) {
            log.warn("VTEP {} unreachable to create binding: using the "
                     + "default tunnel IP and no checking of the physical port",
                     mgmtIp);
        } finally {
            disconnect(client);
        }

        // Validate there exists a tunnel IP either in the VTEP or ZK
        if (null == tunnelIp) {
            throw new NotFoundHttpException(
                getMessage(VTEP_TUNNEL_IP_NOT_FOUND, mgmtIp, mgmtPort,
                           binding.portName)
            );
        }

        // The VNI is created ONLY on the first binding of a given Neutron
        // network to a VTEP. All further bindings from the same OR different
        // VTEPs MUST use the same VNI. If we're here (createFirstNetworkBinding
        // it means we are on the first binding of a VTEP, so we need to figure
        // whether there is a binding already from a different VTEP on the
        // same network
        int vni;

        if (bridge.getVxLanPortIds() == null ||
            bridge.getVxLanPortIds().isEmpty()) {
            vni = dataClient.getNewVni(); // first time we bind this network
        } else {
            // other bindings to other VTEPs exist, reuse the VNI
            UUID pId = bridge.getVxLanPortIds().get(0);
            VxLanPort vxPort = (VxLanPort)dataClient.portsGet(pId);
            vni = vxPort.getVni();
        }

        // Create the VXLAN port.
        VxLanPort vxlanPort = dataClient.bridgeCreateVxLanPort(
            bridge.getId(), mgmtIp, vtep.getMgmtPort(), vni, tunnelIp,
            vtep.getTunnelZoneId());

        try {
            tryStoreBinding(mgmtIp, mgmtPort, binding.portName, binding.vlanId,
                            binding.networkId);
        } catch (ConflictHttpException e) {
            dataClient.bridgeDeleteVxLanPort(vxlanPort); // rollback
            throw e;
        }

        log.debug("First binding of network {} to VTEP {}. VxlanPort in " +
                  "bridge has id {}, vni: {}, vtep tunnelIp {}. Delegating " +
                  "VTEP config on the VxlanGatewayService",
                  bridge.getId(), mgmtIp, vxlanPort.getId(), vxlanPort.getVni(),
                  vxlanPort.getTunnelIp());
    }

    /**
     * Tries to store a binding in the Midonet store, but will throw if the
     * port/vlan is already taken by any bridge (including the given one)
     *
     * @throws ConflictHttpException if the binding
     * already exsts.
     */
    private void tryStoreBinding(IPv4Addr mgmtIp, int mgmtPort,
                                 String physPortName, short vlanId,
                                 UUID bridgeId)
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
            log.warn("Binding to port {}, VLAN {} not found", portName, vlan);
            throw new NotFoundHttpException(getMessage(VTEP_BINDING_NOT_FOUND,
                                                       mgmtIp, vlan, portName));
        }

        dataClient.vtepDeleteBinding(mgmtIp, portName, vlan);

        UUID bridgeId = binding.getNetworkId();
        boolean lastBinding =
            dataClient.bridgeGetVtepBindings(bridgeId, mgmtIp).isEmpty();

        if (lastBinding) {
            dataClient.bridgeDeleteVxLanPort(bridgeId, mgmtIp);
        }

        log.debug("Delete binding on VTEP {}, port {}, VLAN {} persisted",
                  mgmtIp, portName, vlan);

        // Now try to write it to the VTEP
        VTEP vtep = getVtepOrThrow(mgmtIp, true);
        VtepDataClient client;
        try {
            client = connectAndUpdate(vtep);
        } catch (GatewayTimeoutHttpException e) {
            log.warn("VTEP {} unreachable but binding deletion is persisted, " +
                     "VxLanGatewayService will consolidate state", mgmtIp);
            return;
        }

        try {
            // Delete the binding on the VTEP
            Status st = client.deleteBinding(portName, vlan);
            if (!st.isSuccess()) {
                log.warn("Error deleting binding from VTEP {}", st);
            }
            if (lastBinding && !st.getCode().equals(StatusCode.NOSERVICE)) {
                String lsName = bridgeIdToLogicalSwitchName(bridgeId);
                st = client.deleteLogicalSwitch(lsName);
                if (!st.isSuccess()) {
                    log.warn("Error deleting logical switch from VTEP {}", st);
                }
            }
        } finally {
            disconnect(client);
        }
    }

    public void deleteVxLanPort(VxLanPort vxLanPort)
            throws SerializationException, StateAccessException {
        VtepDataClient client = connect(vxLanPort.getMgmtIpAddr(),
                                        vxLanPort.getMgmtPort());
        try {
            deleteVxLanPort(client, vxLanPort);
        } finally {
            disconnect(client);
        }
    }

    private void deleteVxLanPort(VtepDataClient vtepClient, VxLanPort vxlanPort)
            throws SerializationException, StateAccessException {

        // Delete Midonet VXLAN port. This also deletes all
        // associated bindings.
        dataClient.bridgeDeleteVxLanPort(vxlanPort);

        // Delete the corresponding logical switch on the VTEP.
        String ls = bridgeIdToLogicalSwitchName(vxlanPort.getDeviceId());
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
