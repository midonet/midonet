/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.sdn.flows;

import java.util.Set;
import javax.annotation.Nonnull;


public class ProjectedWildcardMatch extends WildcardMatch {

    @Nonnull private final WildcardMatch source;

    @SuppressWarnings("ConstantConditions")
    public ProjectedWildcardMatch(@Nonnull Set<Field> fields, @Nonnull WildcardMatch source) {
        for (Field field : fields) {
            switch (field) {
                case InputPortUUID:
                    setInputPortUUID(source.getInputPortUUID());
                    break;
                case InputPortNumber:
                    setInputPortNumber(source.getInputPortNumber());
                    break;
                case TransportDestination:
                    setTransportDestination(source.getTransportDestination());
                    break;
                case TransportSource:
                    setTransportSource(source.getTransportSource());
                    break;
                case NetworkDestination:
                    setNetworkDestination(source.getNetworkDestination());
                    break;
                case NetworkSource:
                    setNetworkSource(source.getNetworkSource());
                    break;
                case NetworkProtocol:
                    setNetworkProtocol(source.getNetworkProtocol());
                    break;
                case EthernetDestination:
                    setEthernetDestination(source.getEthernetDestination());
                    break;
                case EthernetSource:
                    setEthernetSource(source.getEthernetSource());
                    break;
                case EtherType:
                    setEtherType(source.getEtherType());
                    break;
                case IsIPv4Fragment:
                    setIsIPv4Fragment(source.getIsIPv4Fragment());
                    break;
                case TunnelID:
                    setTunnelID(source.getTunnelID());
                    break;
            }
        }

        this.source = source;
    }

    @Nonnull
    public WildcardMatch getSource() {
        return source;
    }
}
