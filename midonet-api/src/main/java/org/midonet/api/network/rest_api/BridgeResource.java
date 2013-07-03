/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.network.rest_api;

import com.google.inject.Inject;
import com.google.inject.servlet.RequestScoped;
import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.VendorMediaType;
import org.midonet.api.auth.ForbiddenHttpException;
import org.midonet.api.network.IP4MacPair;
import org.midonet.api.network.MacPort;
import org.midonet.api.network.auth.BridgeAuthorizer;
import org.midonet.api.rest_api.*;
import org.midonet.api.auth.AuthAction;
import org.midonet.api.auth.AuthRole;
import org.midonet.api.auth.Authorizer;
import org.midonet.api.dhcp.rest_api.BridgeDhcpResource;
import org.midonet.api.dhcp.rest_api.BridgeDhcpV6Resource;
import org.midonet.api.network.Bridge;
import org.midonet.api.network.Bridge.BridgeCreateGroupSequence;
import org.midonet.api.network.Bridge.BridgeUpdateGroupSequence;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.InvalidStateOperationException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.cluster.DataClient;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.IntIPv4;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
                    "The requested resource was not found.");
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

        UUID id = dataClient.bridgesCreate(bridge.toData());
        return Response.created(
                ResourceUriBuilder.getBridge(getBaseUri(), id))
                .build();
    }

    /**
     * Handler to list tenant bridges.
     *
     * @throws StateAccessException
     *             Data access error.
     * @return A list of Bridge objects.
     */
    @GET
    @PermitAll
    @Produces({ VendorMediaType.APPLICATION_BRIDGE_COLLECTION_JSON,
            MediaType.APPLICATION_JSON })
    public List<Bridge> list(@QueryParam("tenant_id") String tenantId)
            throws StateAccessException,
                   SerializationException {

        if (tenantId == null) {
            throw new BadRequestHttpException(
                    "Currently tenant_id is required for search.");
        }

        // Tenant ID query string is a special parameter that is used to check
        // authorization.
        if (!Authorizer.isAdminOrOwner(context, tenantId)) {
            throw new ForbiddenHttpException(
                    "Not authorized to view bridges of this request.");
        }

        List<org.midonet.cluster.data.Bridge> dataBridges =
                dataClient.bridgesFindByTenant(tenantId);
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
     * Handler to list the MAC table's entries..
     *
     * @throws StateAccessException
     *             Data access error.
     * @return A list of MacPort objects.
     */
    @GET
    @PermitAll
    @Path("/{id}" + ResourceUriBuilder.MAC_TABLE)
    @Produces({ VendorMediaType.APPLICATION_MAC_PORT_COLLECTION_JSON })
    public List<MacPort> list(@PathParam("id") UUID id)
            throws StateAccessException, SerializationException {
        if (!authorizer.authorize(context, AuthAction.READ, id)) {
            throw new ForbiddenHttpException(
                "Not authorized to view this bridge's MAC table.");
        }

        URI bridgeUri = ResourceUriBuilder.getBridge(getBaseUri(), id);
        Map<MAC, UUID> macPortMap = dataClient.bridgeGetMacPorts(id);
        List<MacPort> macPortList = new ArrayList<MacPort>();
        for (Map.Entry<MAC, UUID> entry : macPortMap.entrySet()) {
            MacPort mp = new MacPort(
                entry.getKey().toString(), entry.getValue());
            mp.setParentUri(bridgeUri);
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
    @Path("/{id}" + ResourceUriBuilder.MAC_TABLE)
    @Consumes({ VendorMediaType.APPLICATION_MAC_PORT_JSON,
        MediaType.APPLICATION_JSON })
    public Response addMacPort(@PathParam("id") UUID id, MacPort mp)
            throws StateAccessException, SerializationException {
        if (!authorizer.authorize(context, AuthAction.WRITE, id)) {
            throw new ForbiddenHttpException(
                "Not authorized to add to this bridge's MAC table.");
        }

        mp.setBridgeId(id);
        Set<ConstraintViolation<MacPort>> violations = validator.validate(
            mp, MacPort.MacPortGroupSequence.class);
        if (!violations.isEmpty()) {
            throw new BadRequestHttpException(violations);
        }

        dataClient.bridgeAddMacPort(id, MAC.fromString(mp.getMacAddr()),
            mp.getPortId());
        URI bridgeUri = ResourceUriBuilder.getBridge(getBaseUri(), id);
        return Response.created(
            ResourceUriBuilder.getMacPort(bridgeUri, mp))
            .build();
    }

    /**
     * Handler to getting a MAC table entry.
     *
     * @param macPortString
     *            MacPort entry in the mac table.
     * @throws StateAccessException
     *             Data access error.
     * @return A MacPort object.
     */
    @GET
    @PermitAll
    @Path("/{id}" + ResourceUriBuilder.MAC_TABLE + "/{mac_port}")
    @Produces({ VendorMediaType.APPLICATION_MAC_PORT_JSON,
        MediaType.APPLICATION_JSON })
    public MacPort get(@PathParam("id") UUID id,
                       @PathParam("mac_port") String macPortString)
        throws StateAccessException, SerializationException {

        if (!authorizer.authorize(context, AuthAction.READ, id)) {
            throw new ForbiddenHttpException(
                "Not authorized to view this bridge's mac table.");
        }

        // The mac in the URI uses '-' instead of ':'
        MAC mac = ResourceUriBuilder.macPortToMac(macPortString);
        UUID port = ResourceUriBuilder.macPortToUUID(macPortString);
        if (!dataClient.bridgeHasMacPort(id, mac, port)) {
            throw new NotFoundHttpException(
                "The requested resource was not found.");
        } else {
            MacPort mp = new MacPort(mac.toString(), port);
            mp.setParentUri(ResourceUriBuilder.getBridge(getBaseUri(), id));
            return mp;
        }
    }

    /**
     * Handler to deleting a MAC table entry.
     *
     * @param macPortString
     *            MacPort entry in the mac table.
     * @throws org.midonet.midolman.state.StateAccessException
     *             Data access error.
     */
    @DELETE
    @RolesAllowed({AuthRole.ADMIN, AuthRole.TENANT_ADMIN})
    @Path("/{id}" + ResourceUriBuilder.MAC_TABLE + "/{mac_port}")
    public void delete(@PathParam("id") UUID id,
                       @PathParam("mac_port") String macPortString)
            throws StateAccessException, SerializationException {

        if (!authorizer.authorize(context, AuthAction.WRITE, id)) {
            throw new ForbiddenHttpException(
                "Not authorized to delete from this bridge's MAC table.");
        }

        dataClient.bridgeDeleteMacPort(id,
            ResourceUriBuilder.macPortToMac(macPortString),
            ResourceUriBuilder.macPortToUUID(macPortString));
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
                "The requested resource was not found.");
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
}
