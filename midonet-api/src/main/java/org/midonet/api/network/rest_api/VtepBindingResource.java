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

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.VendorMediaType;
import org.midonet.api.auth.AuthRole;
import org.midonet.api.network.VTEPBinding;
import org.midonet.api.rest_api.ConflictHttpException;
import org.midonet.api.rest_api.NotFoundHttpException;
import org.midonet.api.rest_api.ResourceFactory;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.api.vtep.VtepDataClientProvider;
import org.midonet.brain.southbound.vtep.VtepDataClient;
import org.midonet.brain.southbound.vtep.model.LogicalSwitch;
import org.midonet.brain.southbound.vtep.model.PhysicalPort;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.data.Bridge;
import org.midonet.cluster.data.Port;
import org.midonet.cluster.data.ports.BridgePort;
import org.midonet.cluster.data.ports.VlanMacPort;
import org.midonet.cluster.data.ports.VxLanPort;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.zkManagers.BridgeZkManager;
import org.midonet.packets.IPv4Addr;
import org.opendaylight.controller.sal.utils.Status;
import org.opendaylight.ovsdb.lib.notation.UUID;
import org.opendaylight.ovsdb.plugin.StatusWithUuid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.midonet.api.validation.MessageProperty.NETWORK_ALREADY_BOUND;
import static org.midonet.api.validation.MessageProperty.VTEP_BINDING_NOT_FOUND;
import static org.midonet.api.validation.MessageProperty.getMessage;
import static org.midonet.brain.southbound.vtep.VtepConstants.bridgeIdToLogicalSwitchName;
import static org.midonet.brain.southbound.vtep.VtepConstants.logicalSwitchNameToBridgeId;

public class VtepBindingResource extends AbstractVtepResource {

    private static final Logger log = LoggerFactory.getLogger(
        VtepBindingResource.class);

    private final Random rand = new Random();

    /** The parent vtep where this binding belongs */
    private final String ipAddrStr;

    @Inject
    public VtepBindingResource(RestApiConfig config, UriInfo uriInfo,
                               SecurityContext context, Validator validator,
                               DataClient dataClient, ResourceFactory factory,
                               VtepDataClientProvider vtepClientProvider,
                               @Assisted String ipAddrStr) {
        super(config, uriInfo, context, validator, dataClient, factory,
              vtepClientProvider);
        this.ipAddrStr = ipAddrStr;
    }

    @POST
    @RolesAllowed({AuthRole.ADMIN})
    @Consumes({VendorMediaType.APPLICATION_VTEP_BINDING_JSON,
                  VendorMediaType.APPLICATION_JSON})
    public Response create(VTEPBinding binding)
        throws StateAccessException, SerializationException
    {
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
                throwIfFailed(status);
            }

            // TODO: fill this list with all the host ips where we want the VTEP
            // to flood unknown dst mcasts. Not adding all host's ips because
            // we'd send the same packet to all of them, so for now we add none
            // and will just populate ucasts.
            List<String> floodToIps = new ArrayList<>();

            Status status = vtepClient.bindVlan(lsName, binding.getPortName(),
                                                binding.getVlanId(), newPortVni,
                                                floodToIps);
            throwIfFailed(status);

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

            URI uri = ResourceUriBuilder.getVtepBinding(getBaseUri(),
                        ipAddrStr, binding.getPortName(), binding.getVlanId());
            return Response.created(uri).build();
        } finally {
            vtepClient.disconnect();
        }

    }

    @GET
    @RolesAllowed({AuthRole.ADMIN})
    @Produces({VendorMediaType.APPLICATION_VTEP_BINDING_JSON,
               VendorMediaType.APPLICATION_JSON})
    @Path("{portName}_{vlanId}")
    public VTEPBinding get(@PathParam("portName") String portName,
                           @PathParam("vlanId") short vlanId)
        throws SerializationException, StateAccessException {

        java.util.UUID bridgeId = getBoundBridgeId(ipAddrStr, portName, vlanId);
        VTEPBinding b = new VTEPBinding(ipAddrStr, portName, vlanId, bridgeId);
        b.setBaseUri(getBaseUri());
        return b;
    }

    @GET
    @RolesAllowed({AuthRole.ADMIN})
    @Produces({VendorMediaType.APPLICATION_VTEP_BINDING_COLLECTION_JSON,
                  MediaType.APPLICATION_JSON})
    public List<VTEPBinding> list() throws StateAccessException,
                                           SerializationException {

        org.midonet.cluster.data.VTEP vtep = getVtepOrThrow(ipAddrStr, false);
        VtepDataClient vtepClient =
            getVtepClient(vtep.getId(), vtep.getMgmtPort(), true);

        try {
            Map<UUID, java.util.UUID> lsToBridge = new HashMap<>();
            for (LogicalSwitch ls : vtepClient.listLogicalSwitches()) {
                lsToBridge.put(ls.uuid, logicalSwitchNameToBridgeId(ls.name));
            }

            List<VTEPBinding> bindings = new ArrayList<>();
            for (PhysicalPort pp : getPhysicalPorts(vtepClient, vtep)) {
                for (Map.Entry<Integer, UUID> e : pp.vlanBindings.entrySet()) {

                    java.util.UUID bridgeId = lsToBridge.get(e.getValue());
                    if (bridgeId != null) { // Ignore non-Midonet bindings.
                        VTEPBinding b =  new VTEPBinding(ipAddrStr, pp.name,
                                                         e.getKey().shortValue(),
                                                         bridgeId);
                        b.setBaseUri(getBaseUri());
                        bindings.add(b);
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
    @Path("{portName}_{vlanId}")
    public void delete(@PathParam("portName") String portName,
                       @PathParam("vlanId") short vlanId)
        throws StateAccessException, SerializationException
    {

        org.midonet.cluster.data.VTEP vtep =
            getVtepOrThrow(parseIPv4Addr(ipAddrStr), true);

        java.util.UUID affectedNwId = null;
        ListMultimap<java.util.UUID, VTEPBinding> logicalSwitchBindings =
            ArrayListMultimap.create();
        for (VTEPBinding binding : this.list()) {
            java.util.UUID nwId = binding.getNetworkId();
            if (binding.getPortName().equals(portName) &&
                binding.getVlanId() == vlanId) {
                // this is the binding we'll delete, just note we found it
                affectedNwId = nwId;
            } else {
                logicalSwitchBindings.put(nwId, binding);
            }
        }

        if (affectedNwId == null) {
            log.warn("No bindings found for port {} and vlan {}",
                     portName, vlanId);
            throw new NotFoundHttpException(VTEP_BINDING_NOT_FOUND);
        }

        // delete this binding
        VtepDataClient vtepClient = getVtepClient(
            IPv4Addr.fromString(ipAddrStr), vtep.getMgmtPort(), true);
        Status st = null;
        try {
            st = vtepClient.deleteBinding(portName, vlanId);
            throwIfFailed(st);
        } finally {
            vtepClient.disconnect();
        }

        // check if the logical switch had any more bindings
        List<VTEPBinding> bindings = logicalSwitchBindings.get(affectedNwId);
        if (bindings == null || bindings.isEmpty()) {
            Bridge b = dataClient.bridgesGet(affectedNwId);
            log.debug("No bindings left for network {}, deleting VxLAN port {}",
                      affectedNwId, b.getVxLanPortId());
            dataClient.portsDelete(b.getVxLanPortId());
            b.setVxLanPortId(null);
            try {
                dataClient.bridgesUpdate(b);
            } catch (BridgeZkManager.VxLanPortIdUpdateException e) {
                throw new IllegalStateException("Not allowed to null vxlan " +
                                                "port in bridge " + b.getId());
            }
        }

        log.debug("Delete binding on vtep {}, port {}, vlan {} completed",
                  new Object[]{ipAddrStr, portName, vlanId});

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

    /**
     * Gets the ID of the bridge bound to the specified port and VLAN ID
     * on the specified VTEP.
     *
     * @param ipAddrStr VTEP's management IP address
     * @param portName Binding's port name
     * @param vlanId Binding's VLAN ID
     */
    protected final java.util.UUID getBoundBridgeId(
        String ipAddrStr, String portName, short vlanId)
        throws SerializationException, StateAccessException
    {

        org.midonet.cluster.data.VTEP vtep = getVtepOrThrow(ipAddrStr, false);
        VtepDataClient vtepClient =
            getVtepClient(vtep.getId(), vtep.getMgmtPort(), true);

        try {
            PhysicalPort pp = getPhysicalPort(vtepClient, vtep, portName);
            UUID lsUuid = pp.vlanBindings.get((int)vlanId);
            if (lsUuid == null) {
                throw new NotFoundHttpException(
                    getMessage(VTEP_BINDING_NOT_FOUND, vtep.getId(),
                               vtep.getMgmtPort(), vlanId, portName)
                );
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

}
