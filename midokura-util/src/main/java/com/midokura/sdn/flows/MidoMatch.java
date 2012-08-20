/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.sdn.flows;

import java.util.UUID;

import com.midokura.packets.IntIPv4;
import com.midokura.packets.MAC;

public interface MidoMatch<T extends MidoMatch<T>> {

    T setInputPortNumber(short inputPortNumber);
    Short getInputPortNumber();

    T setInputPortUUID(UUID inputPortID);
    UUID getInputPortID();

    T setTunnelID(long tunnelID);
    Long getTunnelID();

    T setEthernetSource(MAC addr);
    MAC getEthernetSource();

    T setEthernetDestination(MAC addr);
    MAC getEthernetDestination();

    T setEtherType(short etherType);
    Short getEtherType();

    T setNetworkSource(IntIPv4 addr);
    IntIPv4 getNetworkSource();

    T setNetworkDestination(IntIPv4 addr);
    IntIPv4 getNetworkDestination();

    T setNetworkProtocol(byte networkProtocol);
    Byte getNetworkProtocol();

    T setIsIPv4Fragment(boolean isFragment);
    Boolean getIsIPv4Fragment();

    T setTransportSource(short transportSource);
    Short getTransportSource();

    T setTransportDestination(short transportDestination);
    Short getTransportDestination();
}
