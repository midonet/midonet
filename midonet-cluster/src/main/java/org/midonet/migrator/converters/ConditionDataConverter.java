/*
 * Copyright 2015 Midokura SARL
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
package org.midonet.migrator.converters;

import java.util.UUID;

import org.midonet.cluster.rest_api.models.Condition;
import org.midonet.packets.MAC;

import static org.midonet.midolman.rules.Condition.NO_MASK;
import static org.midonet.packets.Unsigned.unsign;

public class ConditionDataConverter {

    public static Condition fillFromSimulationData(
        Condition dto, org.midonet.midolman.rules.Condition c) {

        dto.condInvert = c.conjunctionInv;
        dto.invInPorts = c.inPortInv;
        dto.invOutPorts = c.outPortInv;
        dto.invPortGroup = c.invPortGroup;
        dto.invIpAddrGroupDst = c.invIpAddrGroupIdDst;
        dto.invIpAddrGroupSrc = c.invIpAddrGroupIdSrc;
        dto.invDlType = c.invDlType;
        dto.invDlSrc = c.invDlSrc;
        dto.invDlDst = c.invDlDst;
        dto.invNwDst = c.nwDstInv;
        dto.invNwProto = c.nwProtoInv;
        dto.invNwSrc = c.nwSrcInv;
        dto.invNwTos = c.nwTosInv;
        dto.invTpDst = c.tpDstInv;
        dto.invTpSrc = c.tpSrcInv;
        dto.traversedDevice = c.traversedDevice;
        dto.invTraversedDevice = c.traversedDeviceInv;

        dto.matchForwardFlow = c.matchForwardFlow;
        dto.matchReturnFlow = c.matchReturnFlow;
        if (c.inPortIds != null) {
            dto.inPorts = c.inPortIds.toArray(new UUID[c.inPortIds.size()]);
        }
        if (c.outPortIds != null) {
            dto.outPorts = c.outPortIds.toArray(new UUID[c.outPortIds.size()]);
        }
        dto.portGroup = c.portGroup;
        dto.ipAddrGroupDst = c.ipAddrGroupIdDst;
        dto.ipAddrGroupSrc = c.ipAddrGroupIdSrc;
        dto.dlType = org.midonet.midolman.rules.Condition.unsignShort(c.etherType);
        if (null != c.ethSrc) {
            dto.dlSrc = c.ethSrc.toString();
        }
        if (NO_MASK != c.ethSrcMask) {
            dto.dlSrcMask = MAC.maskToString(c.ethSrcMask);
        }
        if (null != c.ethDst) {
            dto.dlDst = c.ethDst.toString();
        }
        if (NO_MASK != c.dlDstMask) {
            dto.dlDstMask = MAC.maskToString(c.dlDstMask);
        }
        if (null != c.nwDstIp) {
            dto.nwDstAddress = c.nwDstIp.getAddress().toString();
            dto.nwDstLength = c.nwDstIp.getPrefixLen();
        }
        if (null != c.nwSrcIp) {
            dto.nwSrcAddress = c.nwSrcIp.getAddress().toString();
            dto.nwSrcLength = c.nwSrcIp.getPrefixLen();
        }
        if (null != c.nwProto) {
            dto.nwProto = unsign(c.nwProto);
        }
        if (null != c.nwTos) {
            dto.nwTos = unsign(c.nwTos);
        }
        dto.fragmentPolicy = Condition.FragmentPolicy.valueOf(
            c.fragmentPolicy.toString().toLowerCase());
        if (null != c.tpDst) {
            dto.tpDst = c.tpDst;
        }
        if (null != c.tpSrc) {
            dto.tpSrc = c.tpSrc;
        }
        return dto;
    }

}
