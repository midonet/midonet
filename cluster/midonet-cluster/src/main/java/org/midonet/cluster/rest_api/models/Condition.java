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

package org.midonet.cluster.rest_api.models;

import java.lang.reflect.Type;
import java.util.UUID;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.Pattern;
import javax.xml.bind.annotation.XmlTransient;

import com.google.protobuf.Message;

import org.midonet.cluster.data.ZoomConvert;
import org.midonet.cluster.data.ZoomEnum;
import org.midonet.cluster.data.ZoomEnumValue;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.util.IPSubnetUtil;
import org.midonet.cluster.util.RangeUtil;
import org.midonet.cluster.util.UUIDUtil;
import org.midonet.packets.IPSubnet;
import org.midonet.packets.IPv4;
import org.midonet.packets.IPv4Subnet;
import org.midonet.packets.MAC;
import org.midonet.util.Range;
import org.midonet.util.version.Since;

public class Condition extends UriResource {

    @ZoomEnum(clazz = Topology.Rule.FragmentPolicy.class)
    public enum FragmentPolicy {
        @ZoomEnumValue(value = "ANY") ANY,
        @ZoomEnumValue(value = "NONHEADER") NONHEADER,
        @ZoomEnumValue(value = "HEADER") HEADER,
        @ZoomEnumValue(value = "UNFRAGMENTED") UNFRAGMENTED;

        public static final String pattern = "any|nonheader|header|unfragmented";
    }

    @XmlTransient
    @ZoomField(name = "nw_dst_ip", converter = IPSubnetUtil.Converter.class)
    public IPSubnet<?> nwDst;
    @Pattern(regexp = IPv4.regex, message = "is an invalid IP format")
    public String nwDstAddress;
    @Min(0)
    @Max(32)
    public int nwDstLength;
    @XmlTransient
    @ZoomField(name = "nw_src_ip", converter = IPSubnetUtil.Converter.class)
    public IPSubnet<?> nwSrc;
    @Pattern(regexp = IPv4.regex, message = "is an invalid IP format")
    public String nwSrcAddress;
    @Min(0)
    @Max(32)
    public int nwSrcLength;

    @ZoomField(name = "conjunction_inv")
    public boolean condInvert;
    @ZoomField(name = "match_forward_flow")
    public boolean matchForwardFlow;
    @ZoomField(name = "match_return_flow")
    public boolean matchReturnFlow;

    @ZoomField(name = "in_port_ids", converter = UUIDUtil.Converter.class)
    public UUID[] inPorts;
    @ZoomField(name = "in_port_inv")
    public boolean invInPorts;
    @ZoomField(name = "out_port_ids", converter = UUIDUtil.Converter.class)
    public UUID[] outPorts;
    @ZoomField(name = "out_port_inv")
    public boolean invOutPorts;
    @ZoomField(name = "port_group_id", converter = UUIDUtil.Converter.class)
    public UUID portGroup;
    @ZoomField(name = "inv_port_group")
    public boolean invPortGroup;
    @ZoomField(name = "ip_addr_group_id_src", converter = UUIDUtil.Converter.class)
    public UUID ipAddrGroupSrc;
    @ZoomField(name = "inv_ip_addr_group_id_src")
    public boolean invIpAddrGroupSrc;
    @ZoomField(name = "ip_addr_group_id_dst", converter = UUIDUtil.Converter.class)
    public UUID ipAddrGroupDst;
    @ZoomField(name = "inv_ip_addr_group_id_dst")
    public boolean invIpAddrGroupDst;

    @ZoomField(name = "traversed_device", converter = UUIDUtil.Converter.class)
    public UUID traversedDevice;
    @ZoomField(name = "traversed_device_inv")
    public boolean invTraversedDevice;

    @Min(0x0600)
    @Max(0xFFFF)
    @ZoomField(name = "dl_type")
    public Integer dlType;
    @ZoomField(name = "inv_dl_type")
    public boolean invDlType;
    @ZoomField(name = "dl_src")
    public String dlSrc;
    @Since("2")
    @ZoomField(name = "dl_src_mask", converter = MACMaskConverter.class)
    public String dlSrcMask;
    @ZoomField(name = "inv_dl_src")
    public boolean invDlSrc;
    @ZoomField(name = "dl_dst")
    public String dlDst;
    @Since("2")
    @ZoomField(name = "dl_dst_mask", converter = MACMaskConverter.class)
    public String dlDstMask;
    @ZoomField(name = "inv_dl_dst")
    public boolean invDlDst;
    @ZoomField(name = "nw_tos")
    public Integer nwTos;
    @ZoomField(name = "nw_tos_inv")
    public boolean invNwTos;
    @ZoomField(name = "nw_proto")
    public Integer nwProto;
    @ZoomField(name = "nw_proto_inv")
    public boolean invNwProto;
    @ZoomField(name = "nw_src_inv")
    public boolean invNwSrc;
    @ZoomField(name = "nw_dst_inv")
    public boolean invNwDst;

    @Pattern(regexp = FragmentPolicy.pattern,
             message = "is an invalid fragment policy")
    @ZoomField(name = "fragment_policy")
    public FragmentPolicy fragmentPolicy;

    @ZoomField(name = "tp_src", converter = RangeUtil.Converter.class)
    public Range<Integer> tpSrc;
    @ZoomField(name = "tp_dst", converter = RangeUtil.Converter.class)
    public Range<Integer> tpDst;

    @ZoomField(name = "tp_src_inv")
    public boolean invTpSrc;
    @ZoomField(name = "tp_dst_inv")
    public boolean invTpDst;

    @Override
    public void afterFromProto(Message proto) {
        nwDstAddress = nwDst != null ? nwDst.getAddress().toString() : null;
        nwDstLength = nwDst != null ? nwDst.getPrefixLen() : 0;
        nwSrcAddress = nwSrc != null ? nwSrc.getAddress().toString() : null;
        nwSrcLength = nwSrc != null ? nwSrc.getPrefixLen() : 0;
    }

    @Override
    public void beforeToProto() {
        nwDst = nwDstAddress != null ?
                IPv4Subnet.fromCidr(nwDstAddress + "/" + nwDstLength) : null;
        nwSrc = nwSrcAddress != null ?
                IPv4Subnet.fromCidr(nwSrcAddress + "/" + nwSrcLength) : null;
    }

    public static class MACMaskConverter
        extends ZoomConvert.Converter<String, Long> {
        @Override
        public String fromProto(Long value, Type clazz) {
            return MAC.maskToString(value);
        }
        @Override
        public Long toProto(String value, Type clazz) {
            return MAC.parseMask(value);
        }
    }
}
