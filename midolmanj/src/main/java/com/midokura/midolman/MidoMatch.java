/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman;

import org.openflow.protocol.OFMatch;

import com.midokura.packets.MAC;
import com.midokura.sdn.flows.PacketMatch;

/**
 * MidoMatch extends OFMatch and sets the wildcard bits automatically.
 *
 * @author ddumitriu
 *
 */
public class MidoMatch extends OFMatch implements PacketMatch {

    public MidoMatch() {
        // this.wildcards correctly set to OFPFW_ALL in OFMatch's ctor
    }

    @Override
    public MidoMatch setDataLayerDestination(MAC dataLayerDestination) {
        return setDataLayerDestination(dataLayerDestination.getAddress());
    }

    @Override
    public MidoMatch setDataLayerDestination(byte[] dataLayerDestination) {
        wildcards &= ~OFPFW_DL_DST;
        super.setDataLayerDestination(dataLayerDestination);
        return this;
    }

    @Override
    public MidoMatch setDataLayerDestination(String mac) {
        wildcards &= ~OFPFW_DL_DST;
        super.setDataLayerDestination(mac);
        return this;
    }

    @Override
    public MidoMatch setDataLayerSource(MAC dataLayerSource) {
        return setDataLayerSource(dataLayerSource.getAddress());
    }

    @Override
    public MidoMatch setDataLayerSource(byte[] dataLayerSource) {
        wildcards &= ~OFPFW_DL_SRC;
        super.setDataLayerSource(dataLayerSource);
        return this;
    }

    @Override
    public MidoMatch setDataLayerSource(String mac) {
        wildcards &= ~OFPFW_DL_SRC;
        super.setDataLayerSource(mac);
        return this;
    }

    @Override
    public MidoMatch setDataLayerType(short dataLayerType) {
        wildcards &= ~OFPFW_DL_TYPE;
        super.setDataLayerType(dataLayerType);
        return this;
    }

    @Override
    public MidoMatch setDataLayerVirtualLan(short dataLayerVirtualLan) {
        wildcards &= ~OFPFW_DL_VLAN;
        // Vlan ID is only 12 bits but OF VLAN ID requires all 16 bits.
        // There is a special value, 0xFFFF, which means no VLAN.
        super.setDataLayerVirtualLan(dataLayerVirtualLan);
        return this;
    }

    @Override
    public MidoMatch setDataLayerVirtualLanPriorityCodePoint(byte pcp) {
        wildcards &= ~OFPFW_DL_VLAN_PCP;
        super.setDataLayerVirtualLanPriorityCodePoint(pcp);
        return this;
    }

    @Override
    public MidoMatch setInputPort(short inputPort) {
        wildcards &= ~OFPFW_IN_PORT;
        super.setInputPort(inputPort);
        return this;
    }

    public MidoMatch setNetworkDestination(int networkDestination,
            int prefixLength) {
        setNetworkDestinationPrefixLength(prefixLength);
        super.setNetworkDestination(networkDestination);
        return this;
    }

    @Override
    public MidoMatch setNetworkDestination(int networkDestination) {
        return setNetworkDestination(networkDestination, 32);
    }

    public MidoMatch setNetworkSource(int networkSource, int prefixLength) {
        setNetworkSourcePrefixLength(prefixLength);
        super.setNetworkSource(networkSource);
        return this;
    }

    @Override
    public MidoMatch setNetworkSource(int networkSource) {
        return setNetworkSource(networkSource, 32);
    }

    @Override
    public MidoMatch setNetworkProtocol(byte networkProtocol) {
        wildcards &= ~OFPFW_NW_PROTO;
        super.setNetworkProtocol(networkProtocol);
        return this;
    }

    @Override
    public MidoMatch setNetworkTypeOfService(byte networkTypeOfService) {
        wildcards &= ~OFPFW_NW_TOS;
        super.setNetworkTypeOfService(networkTypeOfService);
        return this;
    }

    @Override
    public MidoMatch setTransportDestination(short transportDestination) {
        wildcards &= ~OFPFW_TP_DST;
        super.setTransportDestination(transportDestination);
        return this;
    }

    @Override
    public MidoMatch setTransportSource(short transportSource) {
        wildcards &= ~OFPFW_TP_SRC;
        super.setTransportSource(transportSource);
        return this;
    }

    public MidoMatch setNetworkSourcePrefixLength(int prefixLen) {
        wildcards = (wildcards & ~OFPFW_NW_SRC_MASK)
                | ((32 - prefixLen) << OFPFW_NW_SRC_SHIFT);
        return this;
    }

    public MidoMatch setNetworkDestinationPrefixLength(int prefixLen) {
        wildcards = (wildcards & ~OFPFW_NW_DST_MASK)
                | ((32 - prefixLen) << OFPFW_NW_DST_SHIFT);
        return this;
    }

    /**
     * Implement clonable interface
     */
    @Override
    public MidoMatch clone() {
        return (MidoMatch) super.clone();
    }
}
