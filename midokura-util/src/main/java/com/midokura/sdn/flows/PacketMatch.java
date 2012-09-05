// Copyright 2012 Midokura Inc.

package com.midokura.sdn.flows;

import com.midokura.packets.MAC;


public interface PacketMatch extends Cloneable {
    short getDataLayerType();
    byte[] getDataLayerSource();
    byte[] getDataLayerDestination();
    byte getNetworkProtocol();
    byte getNetworkTypeOfService();
    int getNetworkDestination();
    int getNetworkSource();
    short getTransportSource();
    short getTransportDestination();
    PacketMatch setDataLayerSource(MAC hwAddr);
    PacketMatch setDataLayerDestination(MAC hwAddr);
    PacketMatch setNetworkSource(int nwAddr);
    PacketMatch setTransportSource(short tpAddr);
    PacketMatch setNetworkDestination(int nwAddr);
    PacketMatch setTransportDestination(short tpAddr);
    PacketMatch clone() throws CloneNotSupportedException;
}
