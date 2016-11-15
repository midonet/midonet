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
package org.midonet.cluster.rest_api.validation;

import java.util.ResourceBundle;

public class MessageProperty {

    // Definitions for these strings are in ValidationMessages.properties
    public static final String ALLOWED_VALUES =
            "{midokura.javarx.AllowedValue.message}";
    public static final String PEERING_ENTRY_NOT_FOUND=
            "{midokura.javarx.PeeringEntryNotFound.message}";
    public static final String ARP_ENTRY_NOT_FOUND=
            "{midokura.javarx.ArpEntryNotFound.message}";
    public static final String BGP_NOT_UNIQUE =
            "{midokura.javarx.BgpNotUnique.message}";
    public static final String BRIDGE_HAS_MAC_PORT =
            "{midokura.javarx.BridgeHasMacPort.message}";
    public static final String BRIDGE_HAS_VLAN =
            "{midokura.javarx.BridgeHasVlan.message}";
    public static final String CONTAINER_UNSCHEDULABLE =
            "{midokura.javarx.ContainerUnschedulable.message}";
    public static final String FRAG_POLICY_INVALID_FOR_L4_RULE =
            "{midokura.javarx.FragPolicyInvalidForL4Rule.message}";
    public static final String FRAG_POLICY_INVALID_FOR_NAT_RULE =
            "{midokura.javarx.FragPolicyInvalidForNatRule.message}";
    public static final String FRAG_POLICY_UNDEFINED =
            "{midokura.javarx.FragPolicyUndefined.message}";
    public static final String HOST_DELETION_NOT_ALLOWED_ACTIVE =
            "{midokura.javarx.HostDeletionNotAllowedActive.message}";
    public static final String HOST_DELETION_NOT_ALLOWED_BINDINGS =
            "{midokura.javarx.HostDeletionNotAllowedBindings.message}";
    public static final String HOST_FLOODING_PROXY_WEIGHT_IS_NULL =
            "{midokura.javarx.HostFloodingProxyWeightIsNull}";
    public static final String HOST_CONTAINER_WEIGHT_IS_NULL =
            "{midokura.javarx.HostContainerWeightIsNull}";
    public static final String HOST_CONTAINER_LIMIT_IS_NULL =
            "{midokura.javarx.HostContainerLimitIsNull}";
    public static final String HOST_ENFORCE_CONTAINER_LIMIT_IS_NULL =
            "{midokura.javarx.HostEnforceContainerLimitIsNull}";
    public static final String HOST_ID_IS_INVALID =
            "{midokura.javarx.HostIdIsInvalid.message}";
    public static final String HOST_INTERFACE_IS_USED =
            "{midokura.javarx.HostInterfaceIsAlreadyUsed.message}";
    public static final String HOST_IS_NOT_IN_ANY_TUNNEL_ZONE =
            "{midokura.javarx.HostIsNotInAnyTunnelZone.message}";
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
    public static final String MAPPING_DISASSOCIATION_IS_REQUIRED =
            "{midokura.javarx.MappingDisassociationIsRequired.message}";
    public static final String MAPPING_STATUS_IS_PENDING =
            "{midokura.javarx.MappingStatusIsPending.message}";
    public static final String MAX_VALUE =
            "{midokura.javarx.MaxValue.message}";
    public static final String MIN_VALUE =
            "{midokura.javarx.MinValue.message}";
    public static final String NETWORK_SUBNET_NOT_FOUND =
            "{midokura.javarx.NetworkSubnetNotFound.message}";
    public static final String NON_NULL =
            "{midokura.javarx.NonNull.message}";
    public static final String POOL_MEMBER_WEIGHT_NEGATIVE =
            "{midokura.javarx.PoolMemberWeightNegative.message}";
    public static final String PORT_ID_IS_INVALID =
            "{midokura.javarx.PortIdIsInvalid.message}";
    public static final String PORT_GROUP_ID_IS_INVALID =
            "{midokura.javarx.PortGroupIdIsInvalid.message}";
    public static final String PORT_NOT_VXLAN_PORT =
            "{midokura.javarx.PortNotVxLanPort.message}";
    public static final String PORT_ALREADY_BOUND =
            "{midokura.javarx.PortAlreadyBound.message}";
    public static final String PORTS_LINKABLE =
            "{midokura.javarx.PortsLinkable.message}";
    public static final String RANGE_NOT_VALID =
            "{midokura.javarx.RangeNotValid.message}";
    public static final String RESOURCE_EXISTS =
            "{midokura.javarx.ResourceExists.message}";
    public static final String RESOURCE_NOT_FOUND =
            "{midokura.javarx.ResourceNotFound.message}";
    public static final String ROUTER_ID_IS_INVALID_IN_LB =
            "{midokura.javarx.RouterIdIsInvalidInLoadBalancer.message}";
    public static final String ROUTE_NEXT_HOP_PORT_NOT_NULL =
            "{midokura.javarx.RouteNextHopPortValid.message}";
    public static final String ROUTE_NEXT_HOP_PORT_INVALID =
            "{midokura.javarx.RouteNextHopPortInvalid.message}";
    public static final String ROUTER_ID_IS_INVALID =
            "{midokura.javarx.RouterIdIsInvalid.message}";
    public static final String SUBNET_HAS_HOST =
            "{midokura.javarx.SubnetHasHost.message}";
    public static final String TENANT_UNMODIFIABLE =
            "{midokura.javarx.TenantUnmodifiable.message}";
    public static final String TUNNEL_ZONE_ID_IS_INVALID =
            "{midokura.javarx.TunnelZoneIdIsInvalid.message}";
    public static final String TUNNEL_ZONE_NOT_VTEP =
            "{midokura.javarx.TunnelZoneNotVtep.message}";
    public static final String UNIQUE_TUNNEL_ZONE_NAME_TYPE =
            "{midokura.javarx.TunnelZoneNameExists.message}";
    public static final String ONLY_ONE_GW_DEV_SUPPORTED =
            "{midokura.javarx.OnlyOneGatewayDeviceSupported.message}";
    public static final String TUNNEL_ZONE_MEMBER_EXISTS =
            "{midokura.javarx.TunnelZoneMemberExists.message}";
    public static final String UNSUPPORTED_GATEWAY_DEVICE =
        "{midokura.javarx.UnsupportedGatewayDevice.message}";
    public static final String VLAN_ID_MATCHES_PORT_VLAN_ID =
            "{midokura.javarx.VlanIdMatchesPortVlanId.message}";
    public static final String VALUE_IS_INVALID =
            "{midokura.javarx.ValueIsInvalid.message}";
    public static final String VALUE_IS_NOT_IN_ENUMS =
            "{midokura.javarx.ValueIsNotEnums.message}";
    public static final String VTEP_BINDING_NOT_FOUND =
            "{midokura.javarx.VtepBindingNotFound}";
    public static final String VTEP_EXISTS =
            "{midokura.javarx.VtepExists.message}";
    public static final String VTEP_HOST_IP_CONFLICT =
            "{midokura.javarx.VtepHostIpConflict.message}";
    public static final String VTEP_HAS_BINDINGS =
            "{midokura.javarx.VtepHasBindings.message}";
    public static final String VTEP_INACCESSIBLE =
            "{midokura.javarx.VtepInaccessible.message}";
    public static final String VTEP_MUST_USE_SAME_TUNNEL_ZONE =
            "{midokura.javarx.VtepMustUseSameTunnelZone.message}";
    public static final String VTEP_PORT_NOT_FOUND =
            "{midokura.javarx.VtepPortNotFound.message}";
    public static final String VTEP_PORT_VLAN_PAIR_ALREADY_USED =
            "{midokura.javarx.VtepPortVlanPairAlreadyUsed.message}";
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
