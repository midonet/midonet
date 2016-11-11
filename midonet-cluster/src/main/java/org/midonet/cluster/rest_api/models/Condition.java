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
import java.net.URI;
import java.util.UUID;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.Pattern;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.base.MoreObjects.ToStringHelper;
import com.google.protobuf.Message;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomConvert;
import org.midonet.cluster.data.ZoomEnum;
import org.midonet.cluster.data.ZoomEnumValue;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.models.Commons;
import org.midonet.cluster.rest_api.BadRequestHttpException;
import org.midonet.cluster.rest_api.annotation.JsonError;
import org.midonet.cluster.rest_api.validation.IsValidFragmentType;
import org.midonet.cluster.rest_api.validation.ValidMac;
import org.midonet.cluster.rest_api.validation.ValidMacMask;
import org.midonet.cluster.rest_api.validation.ValidRange;
import org.midonet.cluster.util.IPSubnetUtil;
import org.midonet.cluster.util.RangeUtil;
import org.midonet.packets.IPSubnet;
import org.midonet.packets.IPv4;
import org.midonet.packets.IPv4Subnet;
import org.midonet.packets.MAC;
import org.midonet.util.Range;

import static org.midonet.cluster.rest_api.validation.MessageProperty.FRAG_POLICY_INVALID_FOR_L4_RULE;
import static org.midonet.cluster.rest_api.validation.MessageProperty.FRAG_POLICY_UNDEFINED;
import static org.midonet.cluster.rest_api.validation.MessageProperty.getMessage;

@IsValidFragmentType
@ZoomClass(clazz = Commons.Condition.class)
public class Condition extends UriResource {

    /** This enumeration is equivalent with
     * [[org.midonet.midolman.rules.FragmentPolicy]] except that this class
     * uses the lower-case values for API compatibility. */
    @ZoomEnum(clazz = Commons.Condition.FragmentPolicy.class)
    public enum FragmentPolicy {
        @ZoomEnumValue(value = "ANY") any,
        @ZoomEnumValue(value = "NONHEADER") nonheader,
        @ZoomEnumValue(value = "HEADER") header,
        @ZoomEnumValue(value = "UNFRAGMENTED") unfragmented
    }

    @JsonIgnore
    @ZoomField(name = "nw_dst_ip", converter = IPSubnetUtil.Converter.class)
    public IPSubnet<?> nwDst;
    @Pattern(regexp = IPv4.regex, message = "is an invalid IP format")
    public String nwDstAddress;
    @Min(0)
    @Max(32)
    public int nwDstLength;
    @JsonIgnore
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

    @ZoomField(name = "in_port_ids")
    public UUID[] inPorts;
    @ZoomField(name = "in_port_inv")
    public boolean invInPorts;
    @ZoomField(name = "out_port_ids")
    public UUID[] outPorts;
    @ZoomField(name = "out_port_inv")
    public boolean invOutPorts;
    @ZoomField(name = "port_group_id")
    public UUID portGroup;
    @ZoomField(name = "inv_port_group")
    public boolean invPortGroup;
    @ZoomField(name = "in_port_group_id")
    public UUID inPortGroup;
    @ZoomField(name = "inv_in_port_group")
    public boolean invInPortGroup;
    @ZoomField(name = "out_port_group_id")
    public UUID outPortGroup;
    @ZoomField(name = "inv_out_port_group")
    public boolean invOutPortGroup;
    @ZoomField(name = "ip_addr_group_id_src")
    public UUID ipAddrGroupSrc;
    @ZoomField(name = "inv_ip_addr_group_id_src")
    public boolean invIpAddrGroupSrc;
    @ZoomField(name = "ip_addr_group_id_dst")
    public UUID ipAddrGroupDst;
    @ZoomField(name = "inv_ip_addr_group_id_dst")
    public boolean invIpAddrGroupDst;

    @ZoomField(name = "traversed_device")
    public UUID traversedDevice;
    @ZoomField(name = "traversed_device_inv")
    public boolean invTraversedDevice;

    @ZoomField(name = "no_vlan")
    public boolean noVlan;
    @ZoomField(name = "vlan")
    public Integer vlan;

    @Min(0x0600)
    @Max(0xFFFF)
    @ZoomField(name = "dl_type")
    public Integer dlType;
    @ZoomField(name = "inv_dl_type")
    public boolean invDlType;

    @ValidMac
    @ZoomField(name = "dl_src")
    public String dlSrc;

    @ValidMacMask
    @ZoomField(name = "dl_src_mask", converter = MACMaskConverter.class)
    public String dlSrcMask;

    @ZoomField(name = "inv_dl_src")
    public boolean invDlSrc;

    @ZoomField(name = "dl_dst")
    @ValidMac
    public String dlDst;

    @ZoomField(name = "dl_dst_mask", converter = MACMaskConverter.class)
    @ValidMacMask
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

    @ZoomField(name = "match_nw_dst_rewritten")
    public boolean matchNwDstRewritten;

    @JsonError(message = FRAG_POLICY_UNDEFINED)
    @ZoomField(name = "fragment_policy")
    public FragmentPolicy fragmentPolicy;

    @ZoomField(name = "tp_src", converter = RangeUtil.Converter.class)
    @ValidRange( min = 1, max = 0xFFFF )
    public Range<Integer> tpSrc;

    @ZoomField(name = "tp_dst", converter = RangeUtil.Converter.class)
    @ValidRange( min = 1, max = 0xFFFF )
    public Range<Integer> tpDst;

    @ZoomField(name = "tp_src_inv")
    public boolean invTpSrc;
    @ZoomField(name = "tp_dst_inv")
    public boolean invTpDst;

    @JsonIgnore
    @ZoomField(name = "icmp_data_src_ip", converter = IPSubnetUtil.Converter.class)
    public IPSubnet<?> icmpDataSrcIp;
    @Pattern(regexp = IPv4.regex, message = "is an invalid IP format")
    public String icmpDataSrcIpAddress;
    @Min(0)
    @Max(32)
    public int icmpDataSrcIpLength;
    @ZoomField(name = "icmp_data_src_ip_inv")
    public boolean invIcmpDataSrcIp;

    @JsonIgnore
    @ZoomField(name = "icmp_data_dst_ip", converter = IPSubnetUtil.Converter.class)
    public IPSubnet<?> icmpDataDstIp;
    @Pattern(regexp = IPv4.regex, message = "is an invalid IP format")
    public String icmpDataDstIpAddress;
    @Min(0)
    @Max(32)
    public int icmpDataDstIpLength;
    @ZoomField(name = "icmp_data_dst_ip_inv")
    public boolean invIcmpDataDstIp;


    @JsonIgnore
    @Override
    public void afterFromProto(Message proto) {
        nwDstAddress = nwDst != null ? nwDst.getAddress().toString() : null;
        nwDstLength = nwDst != null ? nwDst.getPrefixLen() : 0;
        nwSrcAddress = nwSrc != null ? nwSrc.getAddress().toString() : null;
        nwSrcLength = nwSrc != null ? nwSrc.getPrefixLen() : 0;

        if (icmpDataSrcIp != null) {
            icmpDataSrcIpAddress = icmpDataSrcIp.getAddress().toString();
            icmpDataSrcIpLength = icmpDataSrcIp.getPrefixLen();
        } else {
            icmpDataSrcIpAddress = null;
            icmpDataSrcIpLength = 0;
        }

        if (icmpDataDstIp != null) {
            icmpDataDstIpAddress = icmpDataDstIp.getAddress().toString();
            icmpDataDstIpLength = icmpDataDstIp.getPrefixLen();
        } else {
            icmpDataDstIpAddress = null;
            icmpDataDstIpLength = 0;
        }
    }

    @JsonIgnore
    @Override
    public void beforeToProto() {
        nwDst = nwDstAddress != null ?
                IPv4Subnet.fromCidr(nwDstAddress + "/" + nwDstLength) : null;
        nwSrc = nwSrcAddress != null ?
                IPv4Subnet.fromCidr(nwSrcAddress + "/" + nwSrcLength) : null;
        icmpDataSrcIp = icmpDataSrcIpAddress != null ?
            IPv4Subnet.fromCidr(icmpDataSrcIpAddress + "/" + icmpDataSrcIpLength) : null;
        icmpDataDstIp = icmpDataDstIpAddress != null ?
            IPv4Subnet.fromCidr(icmpDataDstIpAddress + "/" + icmpDataDstIpLength) : null;
        fragmentPolicy = getAndValidateFragmentPolicy();
    }

    public boolean hasL4Fields() {
        return null != tpDst || null != tpSrc;
    }

    /**
     * Provide the right FragmentPolicy for this type of Condition, allowing
     * subclasses to override specially for handling the defaults.
     *
     * @throws BadRequestHttpException if the policy is not valid.
     */
    protected FragmentPolicy getAndValidateFragmentPolicy() {

        if (null == fragmentPolicy) {
            return hasL4Fields() ? FragmentPolicy.header : FragmentPolicy.any;
        }

        if (hasL4Fields() &&
            (fragmentPolicy == FragmentPolicy.any ||
             fragmentPolicy == FragmentPolicy.nonheader)) {
            throw new BadRequestHttpException(
                getMessage(FRAG_POLICY_INVALID_FOR_L4_RULE));
        }

        return fragmentPolicy;
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

    public URI getUri() {
        // not in love with this model, but this is how it was done before.
        return null;
    }

    protected void addConditionToStringHelper(ToStringHelper tsh) {
        tsh.add("nwDst", nwDst);
        tsh.add("nwDstAddress", nwDstAddress);
        tsh.add("nwDstLength", nwDstLength);
        tsh.add("nwSrc", nwSrc);
        tsh.add("nwSrcAddress", nwSrcAddress);
        tsh.add("nwSrcLength", nwSrcLength);
        if (condInvert) tsh.add("condInvert", true);
        if (matchForwardFlow) tsh.add("matchForwardFlow", true);
        if (matchReturnFlow) tsh.add("matchReturnFlow", true);
        tsh.add("inPorts", inPorts);
        if (invInPorts) tsh.add("invInPorts", true);
        tsh.add("outPorts", outPorts);
        if (invOutPorts) tsh.add("invOutPorts", true);
        tsh.add("portGroup", portGroup);
        if (invPortGroup) tsh.add("invPortGroup", true);
        tsh.add("ipAddrGroupSrc", ipAddrGroupSrc);
        if (invIpAddrGroupSrc) tsh.add("invIpAddrGroupSrc", true);
        tsh.add("ipAddrGroupDst", ipAddrGroupDst);
        if (invIpAddrGroupDst) tsh.add("invIpAddrGroupDst", true);
        tsh.add("traversedDevice", traversedDevice);
        if (invTraversedDevice) tsh.add("invTraversedDevice", true);
        tsh.add("dlType", dlType);
        if (invDlType) tsh.add("invDlType", true);
        tsh.add("dlSrc", dlSrc);
        tsh.add("dlSrcMask", dlSrcMask);
        if (invDlSrc) tsh.add("invDlSrc", true);
        tsh.add("dlDst", dlDst);
        tsh.add("dlDstMask", dlDstMask);
        if (invDlDst) tsh.add("invDlDst", true);
        tsh.add("nwTos", nwTos);
        if (invNwTos) tsh.add("invNwTos", true);
        tsh.add("nwProto", nwProto);
        if (invNwProto) tsh.add("invNwProto", true);
        if (invNwSrc) tsh.add("invNwSrc", true);
        if (invNwDst) tsh.add("invNwDst", true);
        tsh.add("fragmentPolicy", fragmentPolicy);
        tsh.add("tpSrc", tpSrc);
        tsh.add("tpDst", tpDst);
        if (invTpSrc) tsh.add("invTpSrc", true);
        if (invTpDst) tsh.add("invTpDst", true);
        tsh.add("vlan", vlan);
        tsh.add("noVlan", noVlan);
        tsh.add("icmpDataSrcIpAddress", icmpDataSrcIpAddress);
        tsh.add("icmpDataSrcIpLength", icmpDataSrcIpLength);
        if (invIcmpDataSrcIp) tsh.add("invIcmpDataSrcIp", invIcmpDataSrcIp);
        tsh.add("icmpDataDstIpAddress", icmpDataDstIpAddress);
        tsh.add("icmpDataDstIpLength", icmpDataDstIpLength);
        if (invIcmpDataDstIp) tsh.add("invIcmpDataDstIp", invIcmpDataDstIp);
    }
}
