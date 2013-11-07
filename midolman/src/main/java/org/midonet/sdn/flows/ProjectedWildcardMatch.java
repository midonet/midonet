/*
 * Copyright 2012 Midokura Europe SARL
 */

package org.midonet.sdn.flows;

import java.util.Set;
import javax.annotation.Nonnull;


public class ProjectedWildcardMatch extends WildcardMatch {

    @Nonnull private final WildcardMatch source;

    @SuppressWarnings("ConstantConditions")
    public ProjectedWildcardMatch(@Nonnull Set<Field> fields,
                                  @Nonnull WildcardMatch source) {
        for (Field field : fields) {
            switch (field) {
                case InputPortUUID:
                    setInputPortUUID(source.getInputPortUUID());
                    break;
                case InputPortNumber:
                    setInputPortNumber(source.getInputPortNumber());
                    break;
                case TransportDestination:
                    setTransportDestination(
                        source.getTransportDestinationObject());
                    break;
                case TransportSource:
                    setTransportSource(source.getTransportSourceObject());
                    break;
                case NetworkDestination:
                    setNetworkDestination(source.getNetworkDestinationIP());
                    break;
                case NetworkSource:
                    setNetworkSource(source.getNetworkSourceIP());
                    break;
                case NetworkProtocol:
                    setNetworkProtocol(source.getNetworkProtocolObject());
                    break;
                case NetworkTTL:
                    setNetworkTTL(source.getNetworkTTL());
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
                case FragmentType:
                    setIpFragmentType(source.getIpFragmentType());
                    break;
                case TunnelID:
                    setTunnelID(source.getTunnelID());
                    break;
                case IcmpData:
                    setIcmpData(source.getIcmpData());
                    break;
                case IcmpId:
                    setIcmpIdentifier(source.getIcmpIdentifier());
                    break;
                case VlanId:
                    addVlanIds(source.getVlanIds());
                    break;
            }
        }

        this.source = source;
    }

    @Nonnull
    public WildcardMatch getSource() {
        return source;
    }

    @Override
    @SuppressWarnings("CloneDoesntCallSuperClone")
    public ProjectedWildcardMatch clone() {
        return new ProjectedWildcardMatch(getUsedFields(), getSource().clone());
    }
}
