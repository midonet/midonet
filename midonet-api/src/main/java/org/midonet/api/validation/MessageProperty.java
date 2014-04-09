/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.validation;

import java.util.ResourceBundle;

public class MessageProperty {

    // Definitions for these strings are in ValidationMessages.properties
    public static final String ALLOWED_VALUES =
            "{midokura.javarx.AllowedValue.message}";
    public static final String ARP_ENTRY_NOT_FOUND=
            "{midokura.javarx.ArpEntryNotFound.message}";
    public static final String BGP_NOT_UNIQUE =
            "{midokura.javarx.BgpNotUnique.message}";
    public static final String BRIDGE_HAS_MAC_PORT =
            "{midokura.javarx.BridgeHasMacPort.message}";
    public static final String BRIDGE_HAS_VLAN =
            "{midokura.javarx.BridgeHasVlan.message}";
    public static final String FRAG_POLICY_INVALID_FOR_L4_RULE =
            "{midokura.javarx.FragPolicyInvalidForL4Rule.message}";
    public static final String FRAG_POLICY_INVALID_FOR_NAT_RULE =
            "{midokura.javarx.FragPolicyInvalidForNatRule.message}";
    public static final String FRAG_POLICY_UNDEFINED =
            "{midokura.javarx.FragPolicyUndefined.message}";
    public static final String HOST_ID_IS_INVALID =
            "{midokura.javarx.HostIdIsInvalid.message}";
    public static final String HOST_INTERFACE_IS_USED =
            "{midokura.javarx.HostInterfaceIsAlreadyUsed.message}";
    public static final String IP_ADDR_GROUP_ID_EXISTS =
            "{midokura.javarx.IpAddrGroupIdExists.message}";
    public static final String IP_ADDR_INVALID =
            "{midokura.javarx.IpAddrInvalid.message}";
    public static final String IP_ADDR_INVALID_WITH_PARAM =
            "{midokura.javarx.IpAddrInvalidWithParam.message}";
    public static final String IS_UNIQUE_CHAIN_NAME =
            "{midokura.javarx.IsUniqueChainName.message}";
    public static final String IS_UNIQUE_PORT_GROUP_NAME =
            "{midokura.javarx.IsUniquePortGroupName.message}";
    public static final String MAC_ADDRESS_INVALID =
            "{midokura.javarx.MacAddressInvalid.message}";
    public static final String MAC_MASK_INVALID =
            "{midokura.javarx.MacMaskInvalid.message}";
    public static final String MAC_PORT_ON_BRIDGE =
            "{midokura.javarx.MacPortOnBridge.message}";
    public static final String MAC_URI_FORMAT =
            "{midokura.javarx.MacUriFormat.message}";
    public static final String MAPPING_STATUS_IS_PENDING =
            "{midokura.javarx.MappingStatusIsPending.message}";
    public static final String NETWORK_ALREADY_BOUND =
            "{midokura.javarx.NetworkAlreadyBoundToVtep.message}";
    public static final String NO_VXLAN_PORT =
            "{midokura.javarx.NoVxlanPort.message}";
    public static final String PORT_ID_IS_INVALID =
            "{midokura.javarx.PortIdIsInvalid.message}";
    public static final String PORT_GROUP_ID_IS_INVALID =
            "{midokura.javarx.PortGroupIdIsInvalid.message}";
    public static final String PORTS_LINKABLE =
            "{midokura.javarx.PortsLinkable.message}";
    public static final String RESOURCE_EXISTS =
            "{midokura.javarx.ResourceExists.message}";
    public static final String RESOURCE_NOT_FOUND =
            "{midokura.javarx.ResourceNotFound.message}";
    public static final String ROUTER_ID_IS_INVALID_IN_LB =
            "{midokura.javarx.RouterIdIsInvalidInLoadBalancer.message}";
    public static final String ROUTE_NEXT_HOP_PORT_NOT_NULL =
            "{midokura.javarx.RouteNextHopPortValid.message}";
    public static final String ROUTER_ID_IS_INVALID =
            "{midokura.javarx.RouterIdIsInvalid.message}";
    public static final String TUNNEL_ZONE_ID_IS_INVALID =
            "{midokura.javarx.TunnelZoneIdIsInvalid.message}";
    public static final String UNIQUE_TUNNEL_ZONE_NAME_TYPE =
            "{midokura.javarx.TunnelZoneNameExists.message}";
    public static final String TUNNEL_ZONE_MEMBER_EXISTS =
            "{midokura.javarx.TunnelZoneMemberExists.message}";
    public static final String VLAN_ID_MATCHES_PORT_VLAN_ID =
            "{midokura.javarx.VlanIdMatchesPortVlanId.message}";
    public static final String VALUE_IS_INVALID =
            "{midokura.javarx.ValueIsInvalid.message}";
    public static final String VALUE_IS_NOT_IN_ENUMS =
            "{midokura.javarx.ValueIsNotEnums.message}";
    public static final String VTEP_NOT_FOUND =
            "{midokura.javarx.VtepNotFound.message}";
    public static final String VTEP_EXISTS =
            "{midokura.javarx.VtepExists.message}";
    public static final String VXLAN_PORT_ID_NOT_SETTABLE =
            "{midokura.javarx.VxLanPortIdNotSettable.message}";

    private static ResourceBundle resourceBundle =
            ResourceBundle.getBundle("ValidationMessages");

    /**
     * Loads a message from the ValidationMessages properties file and
     * interpolates the specified arguments using String.format().
     * @param key Key of message to load. Possible values are enumerated as
     *            static members of MessageProperty.
     * @param args Arguments to interpolate.
     * @return Requested message, with args interpolated.
     */
    public static String getMessage(String key, Object... args) {
        if (key.startsWith("{") && key.endsWith("}"))
            key = key.substring(1, key.length() - 1);
        String template = resourceBundle.getString(key);
        return (args.length == 0) ?
                template : String.format(template, args);
    }
}
