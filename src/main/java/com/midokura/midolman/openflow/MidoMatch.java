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
        super.wildcards = 0;
    }

    @Override
    public byte[] getDataLayerDestination() {
        return super.getDataLayerDestination();
    }

    @Override
    public OFMatch setDataLayerDestination(byte[] dataLayerDestination) {
        wildcards |= OFPFW_DL_DST;
        return super.setDataLayerDestination(dataLayerDestination);
    }

    @Override
    public OFMatch setDataLayerDestination(String mac) {
        wildcards |= OFPFW_DL_DST;
        return super.setDataLayerDestination(mac);
    }

    @Override
    public byte[] getDataLayerSource() {
        return super.getDataLayerSource();
    }

    @Override
    public OFMatch setDataLayerSource(byte[] dataLayerSource) {
        wildcards |= OFPFW_DL_SRC;
        return super.setDataLayerSource(dataLayerSource);
    }

    @Override
    public OFMatch setDataLayerSource(String mac) {
        wildcards |= OFPFW_DL_SRC;
        return super.setDataLayerSource(mac);
    }

    @Override
    public short getDataLayerType() {
        return super.getDataLayerType();
    }

    @Override
    public OFMatch setDataLayerType(short dataLayerType) {
        wildcards |= OFPFW_DL_TYPE;
        return super.setDataLayerType(dataLayerType);
    }

    @Override
    public short getDataLayerVirtualLan() {
        return super.getDataLayerVirtualLan();
    }

    @Override
    public OFMatch setDataLayerVirtualLan(short dataLayerVirtualLan) {
        wildcards |= OFPFW_DL_VLAN;
        return super.setDataLayerVirtualLan(dataLayerVirtualLan);
    }

    @Override
    public byte getDataLayerVirtualLanPriorityCodePoint() {
        return super.getDataLayerVirtualLanPriorityCodePoint();
    }

    @Override
    public OFMatch setDataLayerVirtualLanPriorityCodePoint(byte pcp) {
        wildcards |= OFPFW_DL_VLAN_PCP;
        return super.setDataLayerVirtualLanPriorityCodePoint(pcp);
    }

    @Override
    public short getInputPort() {
        return super.getInputPort();
    }

    @Override
    public OFMatch setInputPort(short inputPort) {
        wildcards |= OFPFW_IN_PORT;
        return super.setInputPort(inputPort);
    }

    @Override
    public int getNetworkDestination() {
        return super.getNetworkDestination();
    }

    @Override
    public OFMatch setNetworkDestination(int networkDestination) {
        wildcards |= (networkDestination << OFPFW_NW_DST_SHIFT);
        return super.setNetworkDestination(networkDestination);
    }

    @Override
    public int getNetworkDestinationMaskLen() {
        return super.getNetworkDestinationMaskLen();
    }

    @Override
    public int getNetworkSourceMaskLen() {
        return super.getNetworkSourceMaskLen();
    }

    @Override
    public byte getNetworkProtocol() {
        return super.getNetworkProtocol();
    }

    @Override
    public OFMatch setNetworkProtocol(byte networkProtocol) {
        wildcards |= OFPFW_NW_PROTO;
        return super.setNetworkProtocol(networkProtocol);
    }

    @Override
    public int getNetworkSource() {
        return super.getNetworkSource();
    }

    @Override
    public OFMatch setNetworkSource(int networkSource) {
        wildcards |= (networkSource << OFPFW_NW_SRC_SHIFT);
        return super.setNetworkSource(networkSource);
    }

    @Override
    public byte getNetworkTypeOfService() {
        return super.getNetworkTypeOfService();
    }

    @Override
    public OFMatch setNetworkTypeOfService(byte networkTypeOfService) {
        wildcards |= OFPFW_NW_TOS;
        return super.setNetworkTypeOfService(networkTypeOfService);
    }

    @Override
    public short getTransportDestination() {
        return super.getTransportDestination();
    }

    @Override
    public OFMatch setTransportDestination(short transportDestination) {
        wildcards |= OFPFW_TP_DST;
        return super.setTransportDestination(transportDestination);
    }

    @Override
    public short getTransportSource() {
        return super.getTransportSource();
    }

    @Override
    public OFMatch setTransportSource(short transportSource) {
        wildcards |= OFPFW_TP_SRC;
        return super.setTransportSource(transportSource);
    }

    @Override
    public int getWildcards() {
        return super.getWildcards();
    }

}
