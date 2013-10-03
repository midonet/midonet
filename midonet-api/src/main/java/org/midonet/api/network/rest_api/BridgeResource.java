/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.network.rest_api;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.servlet.RequestScoped;
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.VendorMediaType;
import org.midonet.api.auth.AuthAction;
import org.midonet.api.auth.AuthRole;
import org.midonet.api.auth.Authorizer;
import org.midonet.api.auth.ForbiddenHttpException;
import org.midonet.api.dhcp.rest_api.BridgeDhcpResource;
import org.midonet.api.dhcp.rest_api.BridgeDhcpV6Resource;
import org.midonet.api.network.Bridge;
import org.midonet.api.network.Bridge.BridgeCreateGroupSequence;
import org.midonet.api.network.Bridge.BridgeUpdateGroupSequence;
import org.midonet.api.network.IP4MacPair;
import org.midonet.api.network.MacPort;
import org.midonet.api.network.auth.BridgeAuthorizer;
import org.midonet.api.rest_api.*;
import org.midonet.api.validation.MessageProperty;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.data.ports.VlanMacPort;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.InvalidStateOperationException;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.StatePathExistsException;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.MAC;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.security.PermitAll;
import javax.annotation.security.RolesAllowed;
import javax.validation.ConstraintViolation;
import javax.validation.Validator;
import javax.validation.groups.Default;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;
import java.net.URI;
import java.util.*;

import static org.midonet.api.ResourceUriBuilder.MAC_TABLE;
import static org.midonet.api.ResourceUriBuilder.VLANS;
import static org.midonet.api.validation.MessageProperty.getMessage;
import static org.midonet.cluster.data.Bridge.UNTAGGED_VLAN_ID;

/**
 * Root resource class for Virtual bridges.
 */
@RequestScoped
public class BridgeResource extends AbstractResource {

    private final static Logger log = LoggerFactory
            .getLogger(BridgeResource.class);

    private final Authorizer authorizer;
    private final Validator validator;
    private final DataClient dataClient;
    private final ResourceFactory factory;

    @Inject
    public BridgeResource(RestApiConfig config, UriInfo uriInfo,
                          SecurityContext context, BridgeAuthorizer authorizer,
                          Validator validator, DataClient dataClient,
                          ResourceFactory factory) {
        super(config, uriInfo, context);
        this.authorizer = authorizer;
        this.validator = validator;
        this.dataClient = dataClient;
        this.factory = factory;
    }

    /**
     * Handler to deleting a bridge.
     *
     * @param id
     *            Bridge ID from the request.
     * @throws StateAccessException
     *             Data access error.
     */
    @DELETE
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Path("{id}")
    public void delete(@PathParam("id") UUID id)
            throws StateAccessException,
            InvalidStateOperationException, SerializationException {

        org.midonet.cluster.data.Bridge bridgeData =
                dataClient.bridgesGet(id);
        if (bridgeData == null) {
            return;
        }

        if (!authorizer.authorize(context, AuthAction.WRITE, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to delete this bridge.");
        }

        dataClient.bridgesDelete(id);
    }

    /**
     * Handler to getting a bridge.
     *
     * @param id
     *            Bridge ID from the request.
     * @throws StateAccessException
     *             Data access error.
     * @return A Bridge object.
     */
    @GET
    @PermitAll
    @Path("{id}")
    @Produces({ VendorMediaType.APPLICATION_BRIDGE_JSON,
            MediaType.APPLICATION_JSON })
    public Bridge get(@PathParam("id") UUID id)
            throws StateAccessException, SerializationException {

        if (!authorizer.authorize(context, AuthAction.READ, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to view this bridge.");
        }

        org.midonet.cluster.data.Bridge bridgeData =
                dataClient.bridgesGet(id);
        if (bridgeData == null) {
            throw new NotFoundHttpException(
                    getMessage(MessageProperty.RESOURCE_NOT_FOUND));
        }

        // Convert to the REST API DTO
        Bridge bridge = new Bridge(bridgeData);
        bridge.setBaseUri(getBaseUri());

        return bridge;
    }

    /**
     * Port resource locator for bridges.
     *
     * @param id
     *            Bridge ID from the request.
     * @returns BridgePortResource object to handle sub-resource requests.
     */
    @Path("/{id}" + ResourceUriBuilder.PORTS)
    public PortResource.BridgePortResource getPortResource(@PathParam("id") UUID id) {
        return factory.getBridgePortResource(id);
    }

    /**
     * DHCP resource locator for bridges.
     *
     * @param id
     *            Bridge ID from the request.
     * @returns BridgeDhcpResource object to handle sub-resource requests.
     */
    @Path("/{id}" + ResourceUriBuilder.DHCP)
    public BridgeDhcpResource getBridgeDhcpResource(@PathParam("id") UUID id) {
        return factory.getBridgeDhcpResource(id);
    }

    /**
     * DHCPV6 resource locator for bridges.
     *
     * @param id
     *            Bridge ID from the request.
     * @returns BridgeDhcpV6Resource object to handle sub-resource requests.
     */
    @Path("/{id}" + ResourceUriBuilder.DHCPV6)
    public BridgeDhcpV6Resource getBridgeDhcpV6Resource(@PathParam("id") UUID id) {
        return factory.getBridgeDhcpV6Resource(id);
    }

    /**
     * Peer port resource locator for bridges.
     *
     * @param id
     *            Bridge ID from the request.
     * @returns BridgePeerPortResource object to handle sub-resource requests.
     */
    @Path("/{id}" + ResourceUriBuilder.PEER_PORTS)
    public PortResource.BridgePeerPortResource getBridgePeerPortResource(
            @PathParam("id") UUID id) {
        return factory.getBridgePeerPortResource(id);
    }

    /**
     * Handler to updating a bridge.
     *
     * @param id
     *            Bridge ID from the request.
     * @param bridge
     *            Bridge object.
     * @throws StateAccessException
     *             Data access error.
     */
    @PUT
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Path("{id}")
    @Consumes({ VendorMediaType.APPLICATION_BRIDGE_JSON,
            MediaType.APPLICATION_JSON })
    public void update(@PathParam("id") UUID id, Bridge bridge)
            throws StateAccessException,
            InvalidStateOperationException, SerializationException {

        bridge.setId(id);

        Set<ConstraintViolation<Bridge>> violations = validator.validate(
                bridge, BridgeUpdateGroupSequence.class);
        if (!violations.isEmpty()) {
            throw new BadRequestHttpException(violations);
        }

        if (!authorizer.authorize(context, AuthAction.WRITE, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to update this bridge.");
        }

        dataClient.bridgesUpdate(bridge.toData());
    }

    /**
     * Handler for creating a tenant bridge.
     *
     * @param bridge
     *            Bridge object.
     * @throws StateAccessException
     *             Data access error.
     * @returns Response object with 201 status code set if successful.
     */
    @POST
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Consumes({ VendorMediaType.APPLICATION_BRIDGE_JSON,
            MediaType.APPLICATION_JSON })
    public Response create(Bridge bridge)
            throws StateAccessException, InvalidStateOperationException,
                    SerializationException{

        Set<ConstraintViolation<Bridge>> violations = validator.validate(
                bridge, BridgeCreateGroupSequence.class);
        if (!violations.isEmpty()) {
            throw new BadRequestHttpException(violations);
        }

        if (!Authorizer.isAdminOrOwner(context, bridge.getTenantId())) {
            throw new ForbiddenHttpException(
                    "Not authorized to add bridge to this tenant.");
        }

        try {
            UUID id = dataClient.bridgesCreate(bridge.toData());
            return Response.created(
                    ResourceUriBuilder.getBridge(getBaseUri(), id))
                    .build();
        } catch (StatePathExistsException ex) {
            throw new BadRequestHttpException(
                    MessageProperty.getMessage(
                            MessageProperty.IS_UNIQUE_BRIDGE_NAME));
        }
    }

    /**
     * Handler to list all bridges.
     *
     * @throws StateAccessException
     *             Data access error.
     * @return A list of Bridge objects.
     */
    @GET
    @RolesAllowed({ AuthRole.ADMIN })
    @Produces({ VendorMediaType.APPLICATION_BRIDGE_COLLECTION_JSON,
            MediaType.APPLICATION_JSON })
    public List<Bridge> list(@QueryParam("tenant_id") String tenantId)
            throws StateAccessException, SerializationException {

        List<org.midonet.cluster.data.Bridge> dataBridges = null;
        if (tenantId == null) {
            dataBridges = dataClient.bridgesGetAll();
        } else {
            dataBridges = dataClient.bridgesFindByTenant(tenantId);
        }
        List<Bridge> bridges = new ArrayList<Bridge>();
        if (dataBridges != null) {
            for (org.midonet.cluster.data.Bridge dataBridge :
                    dataBridges) {
                Bridge bridge = new Bridge(dataBridge);
                bridge.setBaseUri(getBaseUri());
                bridges.add(bridge);
            }
        }
        return bridges;
    }

    /*
+     * MAC table access
+     */

    /**
     * Handler to list the MAC table's entries with V1 semantics, meaning that
     * it returns only entries not associated with a particular VLAN and does
     * not serialize the VLAN field.
     *
     * @throws StateAccessException
     *             Data access error.
     * @return A list of MacPort objects.
     */
    @GET
    @PermitAll
    @Path("/{id}" + MAC_TABLE)
    @Produces({ VendorMediaType.APPLICATION_MAC_PORT_COLLECTION_JSON })
    public List<MacPort> list(@PathParam("id") UUID id)
            throws StateAccessException, SerializationException {
        return listHelper(id, UNTAGGED_VLAN_ID);
    }

    /**
     * Handler to list the MAC table's entries with V2 semantics, meaning that
     * it does serialize the VLAN field. Returns all MAC ports regardless of
     * VLAN association.
     *
     * @throws StateAccessException
     *             Data access error.
     * @return A list of MacPort objects.
     */
    @GET
    @PermitAll
    @Path("/{id}" + MAC_TABLE)
    @Produces({ VendorMediaType.APPLICATION_MAC_PORT_COLLECTION_JSON_V2 })
    public List<MacPort> listV2(@PathParam("id") UUID id)
            throws StateAccessException, SerializationException {
        return listHelper(id, null);
    }

    /**
     * Handler to list the MAC table's entries with V2 semantics, meaning that
     * it does serialize the VLAN field. Returns only those MAC ports associated
     * with the specified VLAN ID
     *
     * @param id Bridge's UUID.
     * @param vlanId ID of the VLAN whose MAC table is requested. Specify 0 to
     *               request MAC ports not associated with a VLAN.
     *
     * @throws StateAccessException
     *             Data access error.
     * @return A list of MacPort objects.
     */
    @GET
    @PermitAll
    @Path("/{id}" + VLANS + "/{vlanId}" + MAC_TABLE)
    @Produces({ VendorMediaType.APPLICATION_MAC_PORT_COLLECTION_JSON_V2 })
    public List<MacPort> list(@PathParam("id") UUID id,
                              @PathParam("vlanId") short vlanId)
            throws StateAccessException, SerializationException {
        return listHelper(id, vlanId);
    }

    protected List<MacPort> listHelper(UUID id, Short vlanId)
            throws StateAccessException, SerializationException {
        if (!authorizer.authorize(context, AuthAction.READ, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to view this bridge's MAC table.");
        }

        assertBridgeExists(id);
        if (vlanId != null && vlanId != UNTAGGED_VLAN_ID)
            assertBridgeHasVlan(id, vlanId);

        List<VlanMacPort> ports = (vlanId == null) ?
                dataClient.bridgeGetMacPorts(id) :
                dataClient.bridgeGetMacPorts(id, vlanId);

        List<MacPort> macPortList = new ArrayList<MacPort>();
        for (VlanMacPort port : ports) {
            MacPort mp = new MacPort(port.macAddress.toString(), port.portId);
            mp.setParentUri(ResourceUriBuilder.getBridge(getBaseUri(), id));
            mp.setVlanId(port.vlanId);
            macPortList.add(mp);
        }
        return macPortList;
    }

    /**
     * Handler for creating a MAC table entry.
     *
     * @param mp
     *            MacPort entry for the mac table.
     * @throws org.midonet.midolman.state.StateAccessException
     *             Data access error.
     * @returns Response object with 201 status code set if successful.
     */
    @POST
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Path("/{id}" + MAC_TABLE)
    @Consumes({ VendorMediaType.APPLICATION_MAC_PORT_JSON,
                VendorMediaType.APPLICATION_MAC_PORT_JSON_V2,
                MediaType.APPLICATION_JSON })
    public Response addMacPort(@PathParam("id") UUID id, MacPort mp)
            throws StateAccessException, SerializationException {
        return addMacPortHelper(id, UNTAGGED_VLAN_ID, mp);
    }

    @POST
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Path("/{id}" + VLANS + "/{vlanId}" + MAC_TABLE)
    @Consumes({ VendorMediaType.APPLICATION_MAC_PORT_JSON_V2,
                MediaType.APPLICATION_JSON })
    public Response addMacPort(@PathParam("id") UUID id,
                               @PathParam("vlanId") Short vlanId, MacPort mp)
            throws StateAccessException, SerializationException {
        return addMacPortHelper(id, vlanId, mp);
    }

    private Response addMacPortHelper(UUID id, short vlanId, MacPort mp)
            throws StateAccessException, SerializationException {
        if (!authorizer.authorize(context, AuthAction.WRITE, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to add to this bridge's MAC table.");
        }

        // Need to set these properties for validation.
        mp.setBridgeId(id);
        mp.setVlanId(vlanId);
        Set<ConstraintViolation<MacPort>> violations = validator.validate(
                mp, MacPort.MacPortGroupSequence.class);
        if (!violations.isEmpty()) {
            throw new BadRequestHttpException(violations);
        }

        dataClient.bridgeAddMacPort(id, vlanId,
                MAC.fromString(mp.getMacAddr()), mp.getPortId());
        URI bridgeUri = ResourceUriBuilder.getBridge(getBaseUri(), id);

        // Need to set MacPort's vlanId so getMacPort constructs the right URI.
        mp.setVlanId(vlanId);
        return Response.created(
                ResourceUriBuilder.getMacPort(bridgeUri, mp))
                .build();
    }

    /**
     * Handler to getting a MAC table entry.
     *
     * @param id
     *      Bridge's UUID.
     * @param macAddress
     *      MAC address of mapping to get, in URI format,
     *      e.g., 12-34-56-78-9a-bc.
     * @param portId
     *      UUID of port in the MAC-port mapping to get.
     * @throws StateAccessException
     *      Data access error.
     * @return A MacPort object.
     */
    @GET
    @PermitAll
    @Path("/{id}" + MAC_TABLE + "/{mac}_{portId}")
    @Produces({ VendorMediaType.APPLICATION_MAC_PORT_JSON,
            VendorMediaType.APPLICATION_MAC_PORT_JSON_V2,
            MediaType.APPLICATION_JSON })
    public MacPort get(@PathParam("id") UUID id,
                       @PathParam("mac") String macAddress,
                       @PathParam("portId") UUID portId)
            throws StateAccessException, SerializationException {
        return getHelper(id, UNTAGGED_VLAN_ID, macAddress, portId);
    }

    /**
     * Handler to getting a MAC table entry.
     *
     * @param id
     *      Bridge's UUID.
     * @param macAddress
     *      MAC address of mapping to get, in URI format,
     *      e.g., 12-34-56-78-9a-bc.
     * @param portId
     *      UUID of port in the MAC-port mapping to get.
     * @throws StateAccessException
     *      Data access error.
     * @return A MacPort object.
     */
    @GET
    @PermitAll
    @Path("/{id}" + VLANS + "/{vlanId}" + MAC_TABLE + "/{mac}_{portId}")
    @Produces({ VendorMediaType.APPLICATION_MAC_PORT_JSON_V2,
            MediaType.APPLICATION_JSON })
    public MacPort get(@PathParam("id") UUID id,
                       @PathParam("vlanId") Short vlanId,
                       @PathParam("mac") String macAddress,
                       @PathParam("portId") UUID portId)
            throws StateAccessException, SerializationException {
        return getHelper(id, vlanId, macAddress, portId);
    }

    public MacPort getHelper(UUID id, short vlanId,
                             String macAddress, UUID portId)
            throws StateAccessException, SerializationException {
        if (!authorizer.authorize(context, AuthAction.READ, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to view this bridge's mac table.");
        }

        assertBridgeExists(id);
        assertBridgeExists(id);
        if (vlanId != UNTAGGED_VLAN_ID)
            assertBridgeHasVlan(id, vlanId);

        MAC mac = validateMacAddress(macAddress);
        if (!dataClient.bridgeHasMacPort(id, vlanId, mac, portId)) {
            throw new NotFoundHttpException(
                    getMessage(MessageProperty.RESOURCE_NOT_FOUND));
        }

        MacPort mp = new MacPort(mac.toString(), portId);
        mp.setVlanId(vlanId);
        mp.setParentUri(ResourceUriBuilder.getBridge(getBaseUri(), id));
        return mp;
    }


    /**
     * Handler to deleting a MAC table entry.
     *
     * @param id
     *      Bridge UUID.
     * @param macAddress
     *      MAC address of MAC-port mapping to delete.
     * @param portId
     *      UUID of port in MAC-port mapping to delete.
     * @throws org.midonet.midolman.state.StateAccessException
     *      Data access error.
     */
    @DELETE
    @RolesAllowed({AuthRole.ADMIN, AuthRole.TENANT_ADMIN})
    @Path("/{id}" + MAC_TABLE + "/{mac}_{portId}")
    public void delete(@PathParam("id") UUID id,
                       @PathParam("mac") String macAddress,
                       @PathParam("portId") UUID portId)
            throws StateAccessException, SerializationException {
        deleteHelper(id, UNTAGGED_VLAN_ID, macAddress, portId);
    }

    /**
     * Handler to deleting a MAC table entry.
     *
     * @param id
     *      Bridge UUID.
     * @param vlanId
     *      VLAN ID of MAC-port mapping to delete.
     * @param macAddress
     *      MAC address of MAC-port mapping to delete.
     * @param portId
     *      UUID of port in MAC-port mapping to delete.
     * @throws org.midonet.midolman.state.StateAccessException
     *      Data access error.
     */
    @DELETE
    @RolesAllowed({AuthRole.ADMIN, AuthRole.TENANT_ADMIN})
    @Path("/{id}" + VLANS + "/{vlanId}" + MAC_TABLE + "/{mac}_{portId}")
    public void delete(@PathParam("id") UUID id,
                       @PathParam("vlanId") short vlanId,
                       @PathParam("mac") String macAddress,
                       @PathParam("portId") UUID portId)
            throws StateAccessException, SerializationException {
        deleteHelper(id, vlanId, macAddress, portId);
    }

    private void deleteHelper(UUID id, Short vlanId,
                              String macAddress, UUID portId)
            throws StateAccessException, SerializationException {
        if (!authorizer.authorize(context, AuthAction.WRITE, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to delete from this bridge's MAC table.");
        }

        assertBridgeExists(id);
        if (vlanId != UNTAGGED_VLAN_ID)
            assertBridgeHasVlan(id, vlanId);
        MAC mac = validateMacAddress(macAddress);

        dataClient.bridgeDeleteMacPort(id, vlanId, mac, portId);
    }

    /*
+     * ARP table access
+     */

    /**
     * Handler to list the ARP table's entries..
     *
     * @throws StateAccessException
     *             Data access error.
     * @return A list of IP4MacPair objects.
     */
    @GET
    @PermitAll
    @Path("/{id}" + ResourceUriBuilder.ARP_TABLE)
    @Produces({ VendorMediaType.APPLICATION_IP4_MAC_COLLECTION_JSON })
    public List<IP4MacPair> listArpEntries(@PathParam("id") UUID id)
        throws StateAccessException, SerializationException {
        if (!authorizer.authorize(context, AuthAction.READ, id)) {
            throw new ForbiddenHttpException(
                "Not authorized to view this bridge's ARP table.");
        }

        URI bridgeUri = ResourceUriBuilder.getBridge(getBaseUri(), id);
        Map<IPv4Addr, MAC> IP4MacPairMap = dataClient.bridgeGetIP4MacPairs(id);
        List<IP4MacPair> IP4MacPairList = new ArrayList<IP4MacPair>();
        for (Map.Entry<IPv4Addr, MAC> entry : IP4MacPairMap.entrySet()) {
            IP4MacPair pair = new IP4MacPair(
                entry.getKey().toString(), entry.getValue().toString());
            pair.setParentUri(bridgeUri);
            IP4MacPairList.add(pair);
        }
        return IP4MacPairList;
    }

    /**
     * Handler for creating a ARP table entry.
     *
     * @param mp
     *            IP4MacPair entry for the ARP table.
     * @throws org.midonet.midolman.state.StateAccessException
     *             Data access error.
     * @returns Response object with 201 status code set if successful.
     */
    @POST
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Path("/{id}" + ResourceUriBuilder.ARP_TABLE)
    @Consumes({ VendorMediaType.APPLICATION_IP4_MAC_JSON,
        MediaType.APPLICATION_JSON })
    public Response addArpEntry(@PathParam("id") UUID id, IP4MacPair mp)
        throws StateAccessException, SerializationException {
        if (!authorizer.authorize(context, AuthAction.WRITE, id)) {
            throw new ForbiddenHttpException(
                "Not authorized to add to this bridge's ARP table.");
        }

        Set<ConstraintViolation<IP4MacPair>> violations = validator.validate(
            mp, Default.class);
        if (!violations.isEmpty()) {
            throw new BadRequestHttpException(violations);
        }

        dataClient.bridgeAddIp4Mac(id,
            IPv4Addr.fromString(mp.getIp()), MAC.fromString(mp.getMac()));
        URI bridgeUri = ResourceUriBuilder.getBridge(getBaseUri(), id);
        return Response.created(
            ResourceUriBuilder.getIP4MacPair(bridgeUri, mp))
            .build();
    }

    /**
     * Handler to getting a ARP table entry.
     *
     * @param IP4MacPairString
     *            IP4MacPair entry in the ARP table.
     * @throws StateAccessException
     *             Data access error.
     * @return A IP4MacPair object.
     */
    @GET
    @PermitAll
    @Path("/{id}" + ResourceUriBuilder.ARP_TABLE + "/{mac_port}")
    @Produces({ VendorMediaType.APPLICATION_IP4_MAC_JSON,
        MediaType.APPLICATION_JSON })
    public IP4MacPair getArpEntry(@PathParam("id") UUID id,
                       @PathParam("mac_port") String IP4MacPairString)
        throws StateAccessException, SerializationException {

        if (!authorizer.authorize(context, AuthAction.READ, id)) {
            throw new ForbiddenHttpException(
                "Not authorized to view this bridge's mac table.");
        }

        // The mac in the URI uses '-' instead of ':'
        IPv4Addr ip = ResourceUriBuilder.ip4MacPairToIP4(IP4MacPairString);
        MAC mac = ResourceUriBuilder.ip4MacPairToMac(IP4MacPairString);
        if (!dataClient.bridgeHasIP4MacPair(id, ip, mac)) {
            throw new NotFoundHttpException(
                    getMessage(MessageProperty.RESOURCE_NOT_FOUND));
        } else {
            IP4MacPair mp = new IP4MacPair(ip.toString(), mac.toString());
            mp.setParentUri(ResourceUriBuilder.getBridge(getBaseUri(), id));
            return mp;
        }
    }

    /**
     * Handler to deleting a ARP table entry.
     *
     * @param IP4MacPairString
     *            IP4MacPair entry in the ARP table.
     * @throws org.midonet.midolman.state.StateAccessException
     *             Data access error.
     */
    @DELETE
    @RolesAllowed({AuthRole.ADMIN, AuthRole.TENANT_ADMIN})
    @Path("/{id}" + ResourceUriBuilder.ARP_TABLE + "/{ip4_mac}")
    public void deleteArpEntry(@PathParam("id") UUID id,
                       @PathParam("ip4_mac") String IP4MacPairString)
        throws StateAccessException, SerializationException {

        if (!authorizer.authorize(context, AuthAction.WRITE, id)) {
            throw new ForbiddenHttpException(
                "Not authorized to delete from this bridge's MAC table.");
        }

        dataClient.bridgeDeleteIp4Mac(id,
            ResourceUriBuilder.ip4MacPairToIP4(IP4MacPairString),
            ResourceUriBuilder.ip4MacPairToMac(IP4MacPairString));
    }

    private void assertBridgeExists(UUID id) throws StateAccessException {
        if (!dataClient.bridgeExists(id))
            throw new NotFoundHttpException(
                    getMessage(MessageProperty.BRIDGE_EXISTS, id));
    }

    private void assertBridgeHasVlan(UUID id, short vlanId)
            throws StateAccessException {
        if (!dataClient.bridgeHasMacTable(id, vlanId))
            throw new NotFoundHttpException(
                    getMessage(MessageProperty.BRIDGE_HAS_VLAN, vlanId));
    }

    private void assertBridgeHasMacPort(UUID id, Short vlanId,
                                        String macAddress, UUID portId)
            throws StateAccessException {
        if (!dataClient.bridgeHasMacPort(
                id, vlanId, MAC.fromString(macAddress), portId))
            throw new NotFoundHttpException(
                    getMessage(MessageProperty.BRIDGE_HAS_MAC_PORT));
    }

    private MAC validateMacAddress(String macAddress) {
        try {
            return ResourceUriBuilder.macFromUri(macAddress);
        } catch (IllegalArgumentException ex) {
            throw new BadRequestHttpException(
                    getMessage(MessageProperty.MAC_URI_FORMAT));
        }
    }
}
