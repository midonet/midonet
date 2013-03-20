/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.validation;

public class MessageProperty {

    public static final String ALLOWED_VALUES =
            "{midokura.javarx.AllowedValue.message}";
    public static final String IS_UNIQUE_ROUTER_NAME =
            "{midokura.javarx.IsUniqueRouterName.message}";
    public static final String IS_UNIQUE_BRIDGE_NAME =
            "{midokura.javarx.IsUniqueBridgeName.message}";
    public static final String IS_UNIQUE_PORT_GROUP_NAME =
            "{midokura.javarx.IsUniquePortGroupName.message}";
    public static final String IS_UNIQUE_CHAIN_NAME =
            "{midokura.javarx.IsUniqueChainName.message}";
    public static final String MAC_PORT_ON_BRIDGE =
        "{midokura.javarx.MacPortOnBridge.message}";
    public static final String ROUTE_NEXT_HOP_PORT_NOT_NULL =
            "{midokura.javarx.RouteNextHopPortValid.message}";
    public static final String PORT_ID_IS_INVALID =
            "{midokura.javarx.PortIdIsInvalid.message}";
    public static final String PORT_GROUP_ID_IS_INVALID =
            "{midokura.javarx.PortGroupIdIsInvalid.message}";
    public static final String TUNNEL_ZONE_ID_IS_INVALID =
            "{midokura.javarx.TunnelZoneIdIsInvalid.message}";
    public static final String TUNNEL_ZONE_MEMBER_EXISTS =
            "{midokura.javarx.TunnelZoneMemberExists.message}";
    public static final String HOST_ID_IS_INVALID =
            "{midokura.javarx.HostIdIsInvalid.message}";
    public static final String PORTS_LINKABLE =
            "{midokura.javarx.PortsLinkable.message}";
    public static final String BGP_NOT_UNIQUE =
            "{midokura.javarx.IsUniqueBgpInPort.message}";

}
