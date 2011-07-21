/*
 * Copyright 2011 Midokura KK 
 */

package com.midokura.midolman.openflow;

import org.openflow.protocol.OFMatch;

/**
 * MidoMatch extends OFMatch and sets the wildcard bits automatically.
 * 
 * @author ddumitriu
 * 
 */
public class MidoMatch extends OFMatch {

    public MidoMatch() {
        // this.wildcards correctly set to OFPFW_ALL in OFMatch's ctor
    }

    @Override
    public OFMatch setDataLayerDestination(byte[] dataLayerDestination) {
        wildcards &= ~OFPFW_DL_DST;
        return super.setDataLayerDestination(dataLayerDestination);
    }

    @Override
    public OFMatch setDataLayerDestination(String mac) {
        wildcards &= ~OFPFW_DL_DST;
        return super.setDataLayerDestination(mac);
    }

    @Override
    public OFMatch setDataLayerSource(byte[] dataLayerSource) {
        wildcards &= ~OFPFW_DL_SRC;
        return super.setDataLayerSource(dataLayerSource);
    }

    @Override
    public OFMatch setDataLayerSource(String mac) {
        wildcards &= ~OFPFW_DL_SRC;
        return super.setDataLayerSource(mac);
    }

    @Override
    public OFMatch setDataLayerType(short dataLayerType) {
        wildcards &= ~OFPFW_DL_TYPE;
        return super.setDataLayerType(dataLayerType);
    }

    @Override
    public OFMatch setDataLayerVirtualLan(short dataLayerVirtualLan) {
        wildcards &= ~OFPFW_DL_VLAN;
        return super.setDataLayerVirtualLan(dataLayerVirtualLan);
    }

    @Override
    public OFMatch setDataLayerVirtualLanPriorityCodePoint(byte pcp) {
        wildcards &= ~OFPFW_DL_VLAN_PCP;
        return super.setDataLayerVirtualLanPriorityCodePoint(pcp);
    }

    @Override
    public OFMatch setInputPort(short inputPort) {
        wildcards &= ~OFPFW_IN_PORT;
        return super.setInputPort(inputPort);
    }

    @Override
    public OFMatch setNetworkDestination(int networkDestination) {
        wildcards |= (networkDestination << OFPFW_NW_DST_SHIFT);
        return super.setNetworkDestination(networkDestination);
    }

    @Override
    public OFMatch setNetworkProtocol(byte networkProtocol) {
        wildcards &= ~OFPFW_NW_PROTO;
        return super.setNetworkProtocol(networkProtocol);
    }

    @Override
    public OFMatch setNetworkSource(int networkSource) {
        wildcards |= (networkSource << OFPFW_NW_SRC_SHIFT);
        return super.setNetworkSource(networkSource);
    }

    @Override
    public OFMatch setNetworkTypeOfService(byte networkTypeOfService) {
        wildcards &= ~OFPFW_NW_TOS;
        return super.setNetworkTypeOfService(networkTypeOfService);
    }

    @Override
    public OFMatch setTransportDestination(short transportDestination) {
        wildcards &= ~OFPFW_TP_DST;
        return super.setTransportDestination(transportDestination);
    }

    @Override
    public OFMatch setTransportSource(short transportSource) {
        wildcards &= ~OFPFW_TP_SRC;
        return super.setTransportSource(transportSource);
    }

    public OFMatch setNetworkSourcePrefixLength(int prefixLen) {
        wildcards = (wildcards & ~OFPFW_NW_SRC_MASK) |
                        ((32-prefixLen) << OFPFW_NW_SRC_SHIFT);
        return this;
    }

    public OFMatch setNetworkDestinationPrefixLength(int prefixLen) {
        wildcards = (wildcards & ~OFPFW_NW_DST_MASK) |
                        ((32-prefixLen) << OFPFW_NW_DST_SHIFT);
        return this;
    }
}
