/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
                    setNetworkDst(source.getNetworkDstIP());
                    break;
                case NetworkSrc:
                    setNetworkSrc(source.getNetworkSrcIP());
                    break;
                case NetworkProto:
                    setNetworkProto(source.getNetworkProto());
                    break;
                case NetworkTTL:
                    setNetworkTTL(source.getNetworkTTL());
                    break;
                case EthDst:
                    setEthDst(source.getEthDst());
                    break;
                case EthSrc:
                    setEthSrc(source.getEthSrc());
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
