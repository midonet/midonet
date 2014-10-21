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
                case InputPortNumber:
                    setInputPortNumber(source.getInputPortNumber());
                    break;
                case DstPort:
                    setDstPort(
                            source.getDstPort());
                    break;
                case SrcPort:
                    setSrcPort(source.getSrcPort());
                    break;
                case NetworkDst:
                    setNetworkDst(source.getNetworkDestinationIP());
                    break;
                case NetworkSrc:
                    setNetworkSrc(source.getNetworkSourceIP());
                    break;
                case NetworkProto:
                    setNetworkProto(source.getNetworkProto());
                    break;
                case NetworkTTL:
                    setNetworkTTL(source.getNetworkTTL());
                    break;
                case EthDst:
                    setEthernetDestination(source.getEthDst());
                    break;
                case EthSrc:
                    setEthernetSource(source.getEthSrc());
                    break;
                case EtherType:
                    setEtherType(source.getEtherType());
                    break;
                case FragmentType:
                    setIpFragmentType(source.getIpFragmentType());
                    break;
                case TunnelKey:
                    setTunnelKey(source.getTunnelKey());
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
