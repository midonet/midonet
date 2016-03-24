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
package org.midonet.cluster.rest_api.conversion;

import java.util.HashSet;

import org.midonet.cluster.rest_api.models.Condition;
import org.midonet.cluster.rest_api.models.ForwardNatRule;
import org.midonet.midolman.rules.FragmentPolicy;
import org.midonet.packets.IPFragmentType;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.IPv4Subnet;
import org.midonet.packets.MAC;

import static java.util.Arrays.asList;
import static org.midonet.cluster.rest_api.validation.MessageProperty.FRAG_POLICY_INVALID_FOR_L4_RULE;
import static org.midonet.cluster.rest_api.validation.MessageProperty.FRAG_POLICY_INVALID_FOR_NAT_RULE;
import static org.midonet.cluster.rest_api.validation.MessageProperty.getMessage;
import static org.midonet.midolman.rules.FragmentPolicy.ANY;
import static org.midonet.midolman.rules.FragmentPolicy.HEADER;
import static org.midonet.midolman.rules.FragmentPolicy.UNFRAGMENTED;
import static org.midonet.midolman.rules.FragmentPolicy.valueOf;

public class ConditionDataConverter {

    public static org.midonet.midolman.rules.Condition makeCondition(
        Condition dto) {
        org.midonet.midolman.rules.Condition c =
            new org.midonet.midolman.rules.Condition();
        c.conjunctionInv = dto.condInvert;
        c.matchForwardFlow = dto.matchForwardFlow;
        c.matchReturnFlow = dto.matchReturnFlow;
        if (dto.inPorts != null) {
            c.inPortIds = new HashSet<>(asList(dto.inPorts));
        } else {
            c.inPortIds = new HashSet<>();
        }
        c.inPortInv = dto.invInPorts;
        if (dto.dlType != null) {
            c.etherType = dto.dlType;
        }
        c.invDlType = dto.invDlType;
        if (dto.dlSrc != null) {
            c.ethSrc = MAC.fromString(dto.dlSrc);
        }
        if (dto.dlSrcMask != null) {
            c.ethSrcMask = MAC.parseMask(dto.dlSrcMask);
        }
        c.invDlSrc = dto.invDlSrc;
        if (dto.dlDst != null) {
            c.ethDst = MAC.fromString(dto.dlDst);
        }
        if (dto.dlDstMask != null) {
            c.dlDstMask = MAC.parseMask(dto.dlDstMask);
        }
        c.invDlDst = dto.invDlDst;
        c.nwDstInv = dto.invNwDst;
        if (dto.nwDstAddress != null) {
            c.nwDstIp = new IPv4Subnet(IPv4Addr.fromString(dto.nwDstAddress),
                                       dto.nwDstLength);
        }
        if (dto.nwProto != null && dto.nwProto != 0) {
            c.nwProto = dto.nwProto.byteValue();
        }

        c.nwProtoInv = dto.invNwProto;
        c.nwSrcInv = dto.invNwSrc;
        if (dto.nwSrcAddress != null) {
            c.nwSrcIp = new IPv4Subnet(
                IPv4Addr.fromString(dto.nwSrcAddress),
                dto.nwSrcLength);
        }

        if (dto.nwTos != null && dto.nwTos != 0) {
            c.nwTos = dto.nwTos.byteValue();
        }

        c.nwTosInv = dto.invNwTos;

        c.fragmentPolicy = getFragmentPolicy(dto);

        if (dto.outPorts != null) {
            c.outPortIds = new HashSet<>(asList(dto.outPorts));
        } else {
            c.outPortIds = new HashSet<>();
        }
        c.outPortInv = dto.invOutPorts;
        if (dto.tpDst != null) {
            c.tpDst = dto.tpDst;
        }
        c.tpDstInv = dto.invTpDst;
        if (dto.tpSrc != null) {
            c.tpSrc = dto.tpSrc;
        }
        c.tpSrcInv = dto.invTpSrc;
        c.portGroup = dto.portGroup;
        c.invPortGroup = dto.invPortGroup;
        c.ipAddrGroupIdDst = dto.ipAddrGroupDst;
        c.traversedDevice = dto.traversedDevice;
        c.invIpAddrGroupIdDst = dto.invIpAddrGroupDst;
        c.traversedDeviceInv = dto.invTraversedDevice;
        c.ipAddrGroupIdSrc = dto.ipAddrGroupSrc;
        c.invIpAddrGroupIdSrc = dto.invIpAddrGroupSrc;

        if (dto.icmpDataSrcIpAddress != null) {
            c.icmpDataSrcIp = new IPv4Subnet(
                    IPv4Addr.fromString(dto.icmpDataSrcIpAddress),
                    dto.icmpDataSrcIpLength);
        }
        c.icmpDataSrcIpInv = dto.invIcmpDataSrcIp;

        if (dto.icmpDataDstIpAddress != null) {
            c.icmpDataDstIp = new IPv4Subnet(
                    IPv4Addr.fromString(dto.icmpDataDstIpAddress),
                    dto.icmpDataDstIpLength);
        }
        c.icmpDataDstIpInv = dto.invIcmpDataDstIp;

        return c;
    }

    public static FragmentPolicy getFragmentPolicy(Condition dto) {

        if (dto instanceof ForwardNatRule) {
            ForwardNatRule fnr = (ForwardNatRule)dto;
            boolean unfragmentedOnly = !fnr.isFloatingIp() || dto.hasL4Fields();
            if (dto.fragmentPolicy == null) {
                return unfragmentedOnly ? UNFRAGMENTED : ANY;
            }

            FragmentPolicy fp = valueOf(
                dto.fragmentPolicy.toString().toUpperCase());
            if (unfragmentedOnly && fp != UNFRAGMENTED) {
                throw new IllegalArgumentException(getMessage(
                    FRAG_POLICY_INVALID_FOR_NAT_RULE));
            }

            return fp;
        } else {
            if (dto.fragmentPolicy == null) {
                return dto.hasL4Fields() ? HEADER : ANY;
            }
            FragmentPolicy fp = valueOf(dto.fragmentPolicy.toString().toUpperCase());
            if (dto.hasL4Fields() && fp.accepts(IPFragmentType.Later)) {
                throw new IllegalArgumentException(
                    getMessage(FRAG_POLICY_INVALID_FOR_L4_RULE));
            }
            return fp;
        }
    }

}
