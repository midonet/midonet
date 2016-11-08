/*
 * Copyright 2016 Midokura SARL
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
package org.midonet.midolman.rules;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.models.Commons;
import org.midonet.cluster.util.IPSubnetUtil;
import org.midonet.cluster.util.MACUtil;
import org.midonet.cluster.util.RangeUtil;
import org.midonet.midolman.simulation.IPAddrGroup;
import org.midonet.midolman.simulation.PacketContext;
import org.midonet.nsdb.BaseConfig;
import org.midonet.odp.FlowMatch;
import org.midonet.packets.IPAddr;
import org.midonet.packets.IPSubnet;
import org.midonet.packets.IPv4Subnet;
import org.midonet.packets.MAC;
import org.midonet.packets.Unsigned;
import org.midonet.sdn.flows.FlowTagger;
import org.midonet.util.Range;

@ZoomClass(clazz = Commons.Condition.class, skipSuper = true)
public class Condition extends BaseConfig {
    @ZoomField(name = "conjunction_inv")
    public boolean conjunctionInv;
    @ZoomField(name = "match_forward_flow")
    public boolean matchForwardFlow;
    @ZoomField(name = "match_return_flow")
    public boolean matchReturnFlow;
    @ZoomField(name = "in_port_ids")
    public Set<UUID> inPortIds;
    @ZoomField(name = "in_port_inv")
    public boolean inPortInv;
    @ZoomField(name = "out_port_ids")
    public Set<UUID> outPortIds;
    @ZoomField(name = "out_port_inv")
    public boolean outPortInv;
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
    public UUID ipAddrGroupIdSrc;
    @ZoomField(name = "inv_ip_addr_group_id_src")
    public boolean invIpAddrGroupIdSrc;
    @ZoomField(name = "ip_addr_group_id_dst")
    public UUID ipAddrGroupIdDst;
    @ZoomField(name = "inv_ip_addr_group_id_dst")
    public boolean invIpAddrGroupIdDst;
    @ZoomField(name = "dl_type")
    public Integer etherType; // Ethernet frame type.
    @ZoomField(name = "inv_dl_type")
    public boolean invDlType;
    @ZoomField(name = "dl_src", converter = MACUtil.Converter.class)
    public MAC ethSrc; // Source MAC address.
    @ZoomField(name = "dl_src_mask")
    public long ethSrcMask = NO_MASK; // Top 16 bits ignored.
    @ZoomField(name = "inv_dl_src")
    public boolean invDlSrc;
    @ZoomField(name = "dl_dst", converter = MACUtil.Converter.class)
    public MAC ethDst; // Destination MAC address.
    @ZoomField(name = "dl_dst_mask")
    public long dlDstMask = NO_MASK; // Top 16 bits ignored.
    @ZoomField(name = "inv_dl_dst")
    public boolean invDlDst;
    @ZoomField(name = "nw_tos")
    public Byte nwTos;
    @ZoomField(name = "nw_tos_inv")
    public boolean nwTosInv;
    @ZoomField(name = "nw_proto")
    public Byte nwProto;
    @ZoomField(name = "nw_proto_inv")
    public boolean nwProtoInv;
    @ZoomField(name = "nw_src_ip", converter = IPSubnetUtil.Converter.class)
    public IPSubnet<?> nwSrcIp; // Source IP address.
    @ZoomField(name = "nw_dst_ip", converter = IPSubnetUtil.Converter.class)
    public IPSubnet<?> nwDstIp; // Destination IP address.
    @ZoomField(name = "tp_src", converter = RangeUtil.Converter.class)
    public Range<Integer> tpSrc; // Source TCP port.
    @ZoomField(name = "tp_dst", converter = RangeUtil.Converter.class)
    public Range<Integer> tpDst; // Destination TCP port.
    @ZoomField(name = "nw_src_inv")
    public boolean nwSrcInv;
    @ZoomField(name = "nw_dst_inv")
    public boolean nwDstInv;
    @ZoomField(name = "tp_src_inv")
    public boolean tpSrcInv;
    @ZoomField(name = "tp_dst_inv")
    public boolean tpDstInv;
    @ZoomField(name = "traversed_device")
    public UUID traversedDevice;
    @ZoomField(name = "traversed_device_inv")
    public boolean traversedDeviceInv;
    @ZoomField(name = "no_vlan")
    public boolean noVlan;
    @ZoomField(name = "vlan")
    public short vlan;
    @ZoomField(name = "icmp_data_src_ip", converter = IPSubnetUtil.Converter.class)
    public IPSubnet<?> icmpDataSrcIp;
    @ZoomField(name = "icmp_data_src_ip_inv")
    public boolean icmpDataSrcIpInv;
    @ZoomField(name = "icmp_data_dst_ip", converter = IPSubnetUtil.Converter.class)
    public IPSubnet<?> icmpDataDstIp;
    @ZoomField(name = "icmp_data_dst_ip_inv")
    public boolean icmpDataDstIpInv;
    @ZoomField(name = "match_nw_dst_rewritten")
    public boolean matchNwDstRewritten;


    // In production, this should always be initialized via the API, but there
    // are a bunch of tests that bypass the API and create conditions directly.
    @ZoomField(name = "fragment_policy")
    public FragmentPolicy fragmentPolicy = FragmentPolicy.UNFRAGMENTED;

    // These are needed for simulation, but derived from information
    // stored elsewhere in Zookeeper, hence transient.
    public transient IPAddrGroup ipAddrGroupSrc;
    public transient IPAddrGroup ipAddrGroupDst;

    // Default value used when creator specifies no mask.
    public static final long NO_MASK = -1L;

    /** Matches everything */
    public static final Condition TRUE = new Uncondition(true);

    /** Matches nothing */
    public static final Condition FALSE = new Uncondition(false);

    /**
     * Condition that matches everything or nothing, depending on the
     * value passed in at construction. There are only two instances,
     * Condition.TRUE and Condition.FALSE.
     */
    private static class Uncondition extends Condition {

        private Uncondition(boolean matches) {
            this.matches = matches;
        }

        private final boolean matches;

        @Override
        public boolean matches(PacketContext fwdInfo) {
            return matches;
        }

        @Override
        public boolean equals(Object o) { return this == o; }

        @Override
        public int hashCode() { return matches ? 0 : 1; }

        @Override
        public String toString() { return matches ? "true" : "false"; }
    }

    // Default constructor for the Jackson deserialization.
    // This constructor is also used by ZoomConvert.
    public Condition() { super(); }

    public Condition(MAC macAddress) {
        ethSrc = macAddress;
    }

    public Condition(IPSubnet<?> subnet) {
        nwSrcIp = subnet;
        etherType = Unsigned.unsign(subnet.ethertype());
    }

    public boolean matches(PacketContext pktCtx) {
        FlowMatch pktMatch = pktCtx.wcmatch();
        // Matching on fragmentPolicy is unaffected by conjunctionInv,
        // so that gets tested separately.
        if (!fragmentPolicy.accepts(pktMatch.getIpFragmentType()))
            return false;

        /*
         * Given a packet P and a subCondition x, 'xInv x(P)' is true
         * iff either:
         *    1) xInv is false and x(P) is true,
         * or 2) xInv is true and x(P) is false
         * In other words, 'xInv x(P)' is false iff xInv == x(P).  The entire
         * condition can be expressed as a conjunction:
         *     conjunctionInv (x1Inv x1(P) & ... & x_nInv x_n(P))
         * So we can short-circuit evaluation of the conjunction whenever
         * any x_iInv x_i(P) evaluates to false and we then return the value
         * of 'conjunctionInv'.  If the conjunction evaluates to true, then
         * we return 'NOT conjunctionInv'.
         */
        if (matchForwardFlow && !pktCtx.isForwardFlow())
            return conjunctionInv;
        if (matchReturnFlow && pktCtx.isForwardFlow())
            return conjunctionInv;


        UUID inPortId = pktCtx.inPortId();
        UUID outPortId = pktCtx.outPortId();

        if (noVlan && pktMatch.getVlanIds().size() != 0)
            return conjunctionInv;
        if (vlan != 0) {
            if (pktMatch.getVlanIds().size() == 0)
                return conjunctionInv;
            if (pktMatch.getVlanIds().get(0) != vlan)
                return conjunctionInv;
        }

        IPAddr pmSrcIP = pktMatch.getNetworkSrcIP();
        IPAddr pmDstIP = pktMatch.getNetworkDstIP();
        if (!matchPortGroup(pktCtx.portGroups(), portGroup, invPortGroup))
            return conjunctionInv;
        if (!matchPortGroup(pktCtx.inPortGroups(), inPortGroup, invInPortGroup))
            return conjunctionInv;
        if (!matchPortGroup(pktCtx.outPortGroups(), outPortGroup,
                            invOutPortGroup))
            return conjunctionInv;
        if (!matchPort(this.inPortIds, inPortId, this.inPortInv))
            return conjunctionInv;
        if (!matchPort(this.outPortIds, outPortId, this.outPortInv))
            return conjunctionInv;
        if (!matchField(unsignShort(etherType),
                        Unsigned.unsign(pktMatch.getEtherType()), invDlType))
            return conjunctionInv;
        if (!matchMAC(ethSrc, pktMatch.getEthSrc(), ethSrcMask, invDlSrc))
            return conjunctionInv;
        if (!matchMAC(ethDst, pktMatch.getEthDst(),
                      dlDstMask, invDlDst))
            return conjunctionInv;
        if (!matchField(nwTos, pktMatch.getNetworkTOS(), nwTosInv))
            return conjunctionInv;
        if (!matchField(
                nwProto, pktMatch.getNetworkProto(), nwProtoInv))
            return conjunctionInv;
        if (!matchIP(nwSrcIp, pmSrcIP, nwSrcInv))
            return conjunctionInv;
        if (!matchIP(nwDstIp, pmDstIP, nwDstInv))
            return conjunctionInv;
        if (!matchRange(tpSrc, pktMatch.getSrcPort(), tpSrcInv))
            return conjunctionInv;
        if (!matchRange(
                tpDst, pktMatch.getDstPort(), tpDstInv))
            return conjunctionInv;
        if (!matchIpToGroup(ipAddrGroupSrc, pmSrcIP, invIpAddrGroupIdSrc))
            return conjunctionInv;
        if (!matchIpToGroup(ipAddrGroupDst, pmDstIP, invIpAddrGroupIdDst))
            return conjunctionInv;
        if (!matchTraversedDevice(pktCtx))
            return conjunctionInv;
        if (!matchIcmpDataSrcIp(icmpDataSrcIp, pktMatch,
                                icmpDataSrcIpInv))
            return conjunctionInv;
        if (!matchIcmpDataDstIp(icmpDataDstIp, pktMatch,
                                icmpDataDstIpInv))
            return conjunctionInv;
        if (matchNwDstRewritten && !pktCtx.nwDstRewritten())
            return conjunctionInv;
        return !conjunctionInv;
    }

    private boolean matchPort(Set<UUID> condPorts, UUID port, boolean negate) {
        // Packet is considered to match if the field is null or empty set.
        if (condPorts == null || condPorts.isEmpty())
            return true;
        boolean cond = condPorts.contains(port);
        return negate != cond;
    }

    // In the match methods below, note that if the condition field is
    // null, the packet field is considered to match regardless of its
    // own value or the value of the negate argument.
    //
    // Expressed generally, there is a match if:
    //   condField == null || (negate ^ (pktField matches condField))

    private <T> boolean matchField(T condField, T pktField, boolean negate) {
        // Packet is considered to match if the condField is not specified.
        return condField == null ||
                negate ^ condField.equals(pktField);
    }

    private boolean matchMAC(MAC condMAC, MAC pktMAC,
                             long mask, boolean negate) {
        return condMAC == null ||
                negate ^ condMAC.equalsWithMask(pktMAC, mask);
    }

    private boolean matchIP(IPSubnet<?> condSubnet, IPAddr pktIp, boolean negate) {
        // Packet is considered to match if the condField is not specified.
        return condSubnet == null ||
                negate ^ (pktIp != null && condSubnet.containsAddress(pktIp));
    }

    private boolean matchIpToGroup(
            IPAddrGroup ipAddrGroup, IPAddr ipAddr, boolean negate) {
        return ipAddrGroup == null ||
                negate ^ ipAddrGroup.contains(ipAddr);
    }

    private boolean matchTraversedDevice(PacketContext pktCtx) {
        if (traversedDevice == null) {
            return true;
        }
        boolean contains = false;
        for (FlowTagger.FlowTag tag : pktCtx.flowTags()) {
            if (tag instanceof FlowTagger.DeviceTag
                && ((FlowTagger.DeviceTag)tag).deviceId().equals(traversedDevice)) {
                contains = true;
                break;
            }
        }
        return traversedDeviceInv ^ contains;
    }

    private boolean matchIPv4InBytes(IPSubnet<?> subnet, byte[] icmpData,
                                     int offset) {
        if (icmpData == null
            || icmpData.length < offset + 4
            || !(subnet instanceof IPv4Subnet)) {
            return false;
        }
        IPv4Subnet subnet4 = (IPv4Subnet)subnet;
        int dataIp = (icmpData[offset] & 0xff) << 24
            | (icmpData[offset+1] & 0xff) << 16
            | (icmpData[offset+2] & 0xff) << 8
            | (icmpData[offset+3] & 0xff);

        return IPv4Subnet.addrMatch(subnet4.getIntAddress(), dataIp,
                                    subnet4.getPrefixLen());
    }

    private boolean matchIcmpDataSrcIp(IPSubnet<?> srcIp, FlowMatch match,
                                       boolean negate) {
        return srcIp == null ||
            (negate ^ matchIPv4InBytes(srcIp, match.getIcmpData(), 12));
    }

    private boolean matchIcmpDataDstIp(IPSubnet<?> dstIp, FlowMatch match,
                                       boolean negate) {
        return dstIp == null ||
            (negate ^ matchIPv4InBytes(dstIp, match.getIcmpData(), 16));
    }

    // This works a bit differently from how one might expect. The packet
    // comes in with a set of port groups to which the port through which
    // it ingressed belongs. The condition has a single port group. This
    // matches if that port group is in the packet's list of port groups,
    // which indicates that the packet ingressed through a port in the
    // condition's port group.
    private boolean matchPortGroup(
        List<UUID> pktGroups, UUID condGroup, boolean negate) {
        return condGroup == null ||
                negate ^ (pktGroups != null && pktGroups.contains(condGroup));
    }

    private <E extends Comparable<E>> boolean matchRange(
            Range<E> range, E pktField, boolean negate) {
        return range == null ||
                negate ^ (null == pktField || range.isInside(pktField));
    }

    private boolean isNotNullOrEmpty(Object value) {
        return value != null &&
               !(value instanceof Set && ((Set) value).isEmpty());
    }

    private boolean formatField(StringBuilder sb, boolean inverted, String name,
                                Object value, boolean separator) {
        if (value instanceof Boolean) {
            if ((Boolean)value) {
                if (separator) sb.append(" ");
                sb.append(name);
                return true;
            } else {
                return separator;
            }
        } else if (isNotNullOrEmpty(value)) {
            if (separator) sb.append(" ");
            sb.append(name);
            sb.append(inverted ? "!=" : "=");
            sb.append(value);
            return true;
        } else {
            return separator;
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(conjunctionInv ? "!(" : "(");

        boolean s;
        s = formatField(sb, false, "forward-flow", matchForwardFlow, false);
        s = formatField(sb, false, "return-flow", matchReturnFlow, s);
        s = formatField(sb, false, "no-vlan", noVlan, s);
        if (vlan != 0)
            s = formatField(sb, false, "vlan", vlan, s);
        s = formatField(sb, inPortInv, "input-ports", inPortIds, s);
        s = formatField(sb, outPortInv, "output-ports", outPortIds, s);

        s = formatField(sb, invPortGroup, "port-group", portGroup, s);
        s = formatField(sb, invInPortGroup, "in-port-group", inPortGroup, s);
        s = formatField(sb, invOutPortGroup, "out-port-group", outPortGroup, s);
        s = formatField(sb, invIpAddrGroupIdSrc, "ip-src-group",
                            ipAddrGroupIdSrc, s);
        s = formatField(sb, invIpAddrGroupIdDst, "ip-dst-group",
                            ipAddrGroupIdDst, s);
        s = formatField(sb, invDlType, "ethertype", unsignShort(etherType), s);
        s = formatField(sb, invDlSrc, "mac-src", (ethSrcMask != NO_MASK) ?
                            ethSrc.toString() + "/" + MAC.maskToString(ethSrcMask) :
                            ethSrc, s);
        s = formatField(sb, invDlDst, "mac-dst", (dlDstMask != NO_MASK) ?
                            ethDst.toString() + "/" + MAC.maskToString(dlDstMask) :
                            ethDst, s);
        s = formatField(sb, nwTosInv, "tos", nwTos, s);
        s = formatField(sb, nwProtoInv, "proto", nwProto, s);
        s = formatField(sb, nwSrcInv, "ip-src", nwSrcIp, s);
        s = formatField(sb, nwDstInv, "ip-dst", nwDstIp, s);
        s = formatField(sb, tpSrcInv, "port-src", tpSrc, s);
        s = formatField(sb, tpDstInv, "port-dst", tpDst, s);
        s = formatField(sb, traversedDeviceInv, "traversed-dev",
                            traversedDevice, s);
        s = formatField(sb, icmpDataSrcIpInv, "icmp-data-src-ip",
                            icmpDataSrcIp, s);
        s = formatField(sb, icmpDataDstIpInv, "icmp-data-dst-ip",
                            icmpDataDstIp, s);

        formatField(sb, false, "nw-dst-rewritten", matchNwDstRewritten, s);

        sb.append(")");
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Condition c = (Condition) o;

        return conjunctionInv == c.conjunctionInv &&
                matchForwardFlow == c.matchForwardFlow &&
                matchReturnFlow == c.matchReturnFlow &&
                inPortInv == c.inPortInv && outPortInv == c.outPortInv &&
                invPortGroup == c.invPortGroup &&
                invInPortGroup == c.invInPortGroup &&
                invOutPortGroup == c.invOutPortGroup &&
                invIpAddrGroupIdDst == c.invIpAddrGroupIdDst &&
                invIpAddrGroupIdSrc == c.invIpAddrGroupIdSrc &&
                traversedDeviceInv == c.traversedDeviceInv &&
                invDlType == c.invDlType &&
                invDlSrc == c.invDlSrc && invDlDst == c.invDlDst &&
                ethSrcMask == c.ethSrcMask && dlDstMask == c.dlDstMask &&
                nwTosInv == c.nwTosInv && nwProtoInv == c.nwProtoInv &&
                nwSrcInv == c.nwSrcInv && nwDstInv == c.nwDstInv &&
                tpSrcInv == c.tpSrcInv && tpDstInv == c.tpDstInv &&
                icmpDataSrcIpInv == c.icmpDataSrcIpInv &&
                icmpDataDstIpInv == c.icmpDataDstIpInv &&
                Objects.equals(inPortIds, c.inPortIds) &&
                Objects.equals(outPortIds, c.outPortIds) &&
                Objects.equals(portGroup, c.portGroup) &&
                Objects.equals(inPortGroup, c.inPortGroup) &&
                Objects.equals(outPortGroup, c.outPortGroup) &&
                Objects.equals(ipAddrGroupIdDst, c.ipAddrGroupIdDst) &&
                Objects.equals(ipAddrGroupIdSrc, c.ipAddrGroupIdSrc) &&
                Objects.equals(traversedDevice, c.traversedDevice) &&
                Objects.equals(unsignShort(etherType), unsignShort(c.etherType)) &&
                Objects.equals(ethSrc, c.ethSrc) &&
                Objects.equals(ethDst, c.ethDst) &&
                Objects.equals(nwTos, c.nwTos) &&
                Objects.equals(nwProto, c.nwProto) &&
                Objects.equals(nwSrcIp, c.nwSrcIp) &&
                Objects.equals(nwDstIp, c.nwDstIp) &&
                Objects.equals(tpSrc, c.tpSrc) &&
                Objects.equals(tpDst, c.tpDst) &&
                Objects.equals(icmpDataSrcIp, c.icmpDataSrcIp) &&
                Objects.equals(icmpDataDstIp, c.icmpDataDstIp) &&
                matchNwDstRewritten == c.matchNwDstRewritten;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                conjunctionInv, matchForwardFlow, matchReturnFlow,
                inPortInv, outPortInv,
                invPortGroup, invInPortGroup, invOutPortGroup,
                invIpAddrGroupIdDst, invIpAddrGroupIdSrc,
                invDlType, invDlSrc, invDlDst, ethSrcMask, dlDstMask,
                nwTosInv, nwProtoInv, nwSrcInv, nwDstInv, tpSrcInv, tpDstInv,
                inPortIds, outPortIds, portGroup, inPortGroup, outPortGroup,
                ipAddrGroupIdDst, ipAddrGroupIdSrc, unsignShort(etherType),
                ethSrc, ethDst, nwTos, nwProto, nwSrcIp, nwDstIp, tpSrc, tpDst,
                traversedDevice, icmpDataSrcIp, icmpDataDstIp,
                matchNwDstRewritten);
    }

    public static Integer unsignShort(Integer value) {
        if (null == value) return null;
        return Unsigned.unsign(value.shortValue());
    }
}
