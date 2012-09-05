// Copyright 2012 Midokura Inc.

package com.midokura.sdn.flows;


public interface PacketMatch {
    short getDataLayerType();
    byte[] getDataLayerSource();
    byte[] getDataLayerDestination();
    byte getNetworkProtocol();
    byte getNetworkTypeOfService();
    int getNetworkDestination();
    int getNetworkSource();
    short getTransportSource();
    short getTransportDestination();
}
