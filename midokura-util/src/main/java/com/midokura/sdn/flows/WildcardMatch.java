/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.sdn.flows;

import java.util.EnumSet;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.midokura.packets.IntIPv4;
import com.midokura.packets.MAC;

public interface WildcardMatch<T extends WildcardMatch<T>> {

    enum Field {
        InputPortNumber,
        InputPortID,
        TunnelID,
        EthernetSource,
        EthernetDestination,
        EtherType,
        NetworkSource,
        NetworkDestination,
        NetworkProtocol,
        IsIPv4Fragment,
        TransportSource,
        TransportDestination
    }

    /**
     *
     * @return the set of Fields that have been set in this instance.
     */
    EnumSet<Field> getUsedFields();

    T setInputPortNumber(short inputPortNumber);
    @Nullable Short getInputPortNumber();

    T setInputPortUUID(@Nonnull UUID inputPortID);
    @Nullable UUID getInputPortID();

    T setTunnelID(long tunnelID);
    @Nullable Long getTunnelID();

    T setEthernetSource(@Nonnull MAC addr);
    @Nullable MAC getEthernetSource();

    T setEthernetDestination(@Nonnull MAC addr);
    @Nullable MAC getEthernetDestination();

    T setEtherType(short etherType);
    @Nullable Short getEtherType();

    T setNetworkSource(@Nonnull IntIPv4 addr);
    @Nullable IntIPv4 getNetworkSource();

    T setNetworkDestination(@Nonnull IntIPv4 addr);
    @Nullable IntIPv4 getNetworkDestination();

    T setNetworkProtocol(byte networkProtocol);
    @Nullable Byte getNetworkProtocol();

    T setIsIPv4Fragment(boolean isFragment);
    @Nullable Boolean getIsIPv4Fragment();

    T setTransportSource(short transportSource);
    @Nullable Short getTransportSource();

    T setTransportDestination(short transportDestination);
    @Nullable Short getTransportDestination();
}
