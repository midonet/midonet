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
package org.midonet.midolman.rules;

import java.util.ArrayList;
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
        public String toString() { return "Condition[" + matches + "]"; }
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
        return !conjunctionInv;
    }

    private boolean matchPort(Set<UUID> condPorts, UUID port, boolean negate) {
        // Packet is considered to match if the field is null or empty set.
        if (condPorts == null || condPorts.isEmpty())
            return true;
        boolean cond = condPorts.contains(port);
        return negate? !cond :cond;
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

    // This works a bit differently from how one might expect. The packet
    // comes in with a set of port groups to which the port through which
    // it ingressed belongs. The condition has a single port group. This
    // matches if that port group is in the packet's list of port groups,
    // which indicates that the packet ingressed through a port in the
    // condition's port group.
    private boolean matchPortGroup(
            ArrayList<UUID> pktGroups, UUID condGroup, boolean negate) {
        return portGroup == null ||
                negate ^ (pktGroups != null && pktGroups.contains(condGroup));
    }

    private <E extends Comparable<E>> boolean matchRange(
            Range<E> range, E pktField, boolean negate) {
        return range == null ||
                negate ^ (null == pktField || range.isInside(pktField));
    }

    private void formatField(StringBuilder sb, boolean inverted, String name, Object value) {
        if (value != null) {
            sb.append(name);
            sb.append(inverted ? "=!" : "=");
            sb.append(value.toString());
            sb.append(" ");
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Condition [");
        sb.append(conjunctionInv ? "! (" : "(");

        if (matchForwardFlow)
            sb.append("forward-flow ");

        if (matchReturnFlow)
            sb.append("return-flow ");

        if (noVlan)
            sb.append("no-vlan ");

        if (vlan != 0)
            sb.append("vlan=").append(vlan).append(" ");

        if (inPortIds != null && !inPortIds.isEmpty()) {
            sb.append("input-ports=");
            sb.append(inPortInv ? "!{" : "{");
            for (UUID id : inPortIds) {
                sb.append(id.toString()).append(",");
            }
            sb.append("} ");
        }
        if (outPortIds != null && !outPortIds.isEmpty()) {
            sb.append("output-ports=");
            sb.append(outPortInv ? "!{" : "{");
            for (UUID id : outPortIds) {
                sb.append(id.toString()).append(",");
            }
            sb.append("} ");
        }

        formatField(sb, invPortGroup, "port-group", portGroup);
        formatField(sb, invIpAddrGroupIdSrc, "ip-src-group", ipAddrGroupIdSrc);
        formatField(sb, invIpAddrGroupIdDst, "ip-dst-group", ipAddrGroupIdDst);
        formatField(sb, invDlType, "ethertype", unsignShort(etherType));
        formatField(sb, invDlSrc, "mac-src", (ethSrcMask != NO_MASK) ?
                ethSrc.toString() + "/" + MAC.maskToString(ethSrcMask) :
                ethSrc);
        formatField(sb, invDlDst, "mac-dst", (dlDstMask != NO_MASK) ?
                ethDst.toString() + "/" + MAC.maskToString(dlDstMask) :
                ethDst);
        formatField(sb, nwTosInv, "tos", nwTos);
        formatField(sb, nwProtoInv, "proto", nwProto);
        formatField(sb, nwSrcInv, "ip-src", nwSrcIp);
        formatField(sb, nwDstInv, "ip-dst", nwDstIp);
        formatField(sb, tpSrcInv, "port-src", tpSrc);
        formatField(sb, tpDstInv, "port-dst", tpDst);
        formatField(sb, traversedDeviceInv, "traversed-dev", traversedDevice);

        sb.append(")]");
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
                invIpAddrGroupIdDst == c.invIpAddrGroupIdDst &&
                invIpAddrGroupIdSrc == c.invIpAddrGroupIdSrc &&
                traversedDeviceInv == c.traversedDeviceInv &&
                invDlType == c.invDlType &&
                invDlSrc == c.invDlSrc && invDlDst == c.invDlDst &&
                ethSrcMask == c.ethSrcMask && dlDstMask == c.dlDstMask &&
                nwTosInv == c.nwTosInv && nwProtoInv == c.nwProtoInv &&
                nwSrcInv == c.nwSrcInv && nwDstInv == c.nwDstInv &&
                tpSrcInv == c.tpSrcInv && tpDstInv == c.tpDstInv &&
                Objects.equals(inPortIds, c.inPortIds) &&
                Objects.equals(outPortIds, c.outPortIds) &&
                Objects.equals(portGroup, c.portGroup) &&
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
                Objects.equals(tpDst, c.tpDst);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                conjunctionInv, matchForwardFlow, matchReturnFlow,
                inPortInv, outPortInv, invPortGroup,
                invIpAddrGroupIdDst, invIpAddrGroupIdSrc,
                invDlType, invDlSrc, invDlDst, ethSrcMask, dlDstMask,
                nwTosInv, nwProtoInv, nwSrcInv, nwDstInv, tpSrcInv, tpDstInv,
                inPortIds, outPortIds, portGroup,
                ipAddrGroupIdDst, ipAddrGroupIdSrc, unsignShort(etherType),
                ethSrc, ethDst, nwTos, nwProto, nwSrcIp, nwDstIp, tpSrc, tpDst,
                traversedDevice);
    }

    public static Integer unsignShort(Integer value) {
        if (null == value) return null;
        return Unsigned.unsign(value.shortValue());
    }
}
