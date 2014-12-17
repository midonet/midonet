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

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.midonet.cluster.data.neutron.SecurityGroupRule;
import org.midonet.midolman.simulation.IPAddrGroup;
import org.midonet.midolman.simulation.PacketContext;
import org.midonet.midolman.state.zkManagers.BaseConfig;
import org.midonet.packets.IPAddr;
import org.midonet.packets.IPSubnet;
import org.midonet.packets.MAC;
import org.midonet.packets.Unsigned;
import org.midonet.sdn.flows.FlowTagger;
import org.midonet.sdn.flows.WildcardMatch;
import org.midonet.util.Range;

public class Condition extends BaseConfig {
    public boolean conjunctionInv;
    public boolean matchForwardFlow;
    public boolean matchReturnFlow;
    public Set<UUID> inPortIds;
    public boolean inPortInv;
    public Set<UUID> outPortIds;
    public boolean outPortInv;
    public UUID portGroup;
    public boolean invPortGroup;
    public UUID ipAddrGroupIdSrc;
    public boolean invIpAddrGroupIdSrc;
    public UUID ipAddrGroupIdDst;
    public boolean invIpAddrGroupIdDst;
    public Integer etherType; // Ethernet frame type.
    public boolean invDlType;
    public MAC ethSrc; // Source MAC address.
    public long ethSrcMask = NO_MASK; // Top 16 bits ignored.
    public boolean invDlSrc;
    public MAC ethDst; // Destination MAC address.
    public long dlDstMask = NO_MASK; // Top 16 bits ignored.
    public boolean invDlDst;
    public Byte nwTos;
    public boolean nwTosInv;
    public Byte nwProto;
    public boolean nwProtoInv;
    public IPSubnet<?> nwSrcIp; // Source IP address.
    public IPSubnet<?> nwDstIp; // Destination IP address.
    public Range<Integer> tpSrc; // Source TCP port.
    public Range<Integer> tpDst; // Destination TCP port.
    public boolean nwSrcInv;
    public boolean nwDstInv;
    public boolean tpSrcInv;
    public boolean tpDstInv;
    public UUID traversedDevice;
    public boolean traversedDeviceInv;

    private FlowTagger.FlowTag _traversedDeviceTag = null;
    private FlowTagger.FlowTag traversedDeviceTag() {
        if (traversedDevice == null)
            return null;
        if (_traversedDeviceTag == null)
            _traversedDeviceTag = FlowTagger.tagForDevice(traversedDevice);
        return _traversedDeviceTag;
    }

    // In production, this should always be initialized via the API, but there
    // are a bunch of tests that bypass the API and create conditions directly.
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
        public boolean matches(PacketContext fwdInfo, boolean isPortFilter) {
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
    public Condition() { super(); }

    public Condition(SecurityGroupRule sgRule) {
        nwProto = sgRule.protocolNumber();
        etherType = sgRule.ethertype();
        matchForwardFlow = sgRule.isEgress();
        tpDst = sgRule.portRange();

        if (sgRule.isIngress()) {
            nwSrcIp = sgRule.remoteIpv4Subnet();
            ipAddrGroupIdSrc = sgRule.remoteGroupId;
        } else {
            nwDstIp = sgRule.remoteIpv4Subnet();
            ipAddrGroupIdDst = sgRule.remoteGroupId;
        }
    }

    public Condition(MAC macAddress) {
        ethSrc = macAddress;
    }

    public Condition(IPSubnet<?> subnet) {
        nwSrcIp = subnet;
        etherType = (int) subnet.ethertype();
    }

    public boolean containsInPort(UUID portId) {
        return inPortIds != null && inPortIds.contains(portId);
    }

    public boolean containsOutPort(UUID portId) {
        return outPortIds != null && outPortIds.contains(portId);
    }

    public boolean matches(PacketContext pktCtx, boolean isPortFilter) {
        WildcardMatch pktMatch = pktCtx.wcmatch();
        // Matching on fragmentPolicy is unaffected by conjunctionInv,
        // so that gets tested separately.
        if (!fragmentPolicy.accepts(pktMatch.getIpFragmentType()))
            return false;

        /*
         * Given a packet P and a subCondition x, 'xInv x(P)' is true
         * iff either:
         *    1) xInv is false and x(P) is true,
         * or 2) xInv is true and x(P) is false.
         * In other words, 'xInv x(P)' is false iff xInv == x(P).  The entire
         * condition can be expressed as a conjunction:
         *     conjunctionInv (x1Inv x1(P) & ... & x_nInv x_n(P))
         * So we can short-circuit evaluation of the conjunction whenever
         * any x_iInv x_i(P) evaluates to false and we then return the value
         * of 'conjunctionInv'.  If the conjunction evaluates to true, then
         * we return 'NOT conjunctionInv'.
         */
        if (matchForwardFlow && !pktCtx.state().isForwardFlow())
            return conjunctionInv;
        if (matchReturnFlow && pktCtx.state().isForwardFlow())
            return conjunctionInv;

        UUID inPortId = isPortFilter ? null : pktCtx.inPortId();
        UUID outPortId = isPortFilter ? null : pktCtx.outPortId();
        IPAddr pmSrcIP = pktMatch.getNetworkSrcIP();
        IPAddr pmDstIP = pktMatch.getNetworkDstIP();
        if (!matchPortGroup(pktCtx.portGroups(), portGroup, invPortGroup))
            return conjunctionInv;
        if (!matchPort(this.inPortIds, inPortId, this.inPortInv))
            return conjunctionInv;
        if (!matchPort(this.outPortIds, outPortId, this.outPortInv))
            return conjunctionInv;
        if (!matchField(etherType, pktMatch.getEtherType() != null ?
                Unsigned.unsign(pktMatch.getEtherType()) : null, invDlType))
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
        FlowTagger.FlowTag tag = traversedDeviceTag();
        return tag == null || traversedDeviceInv ^ pktCtx.flowTags().contains(tag);
    }

    // This works a bit differently from how one might expect. The packet
    // comes in with a set of port groups to which the port through which
    // it ingressed belongs. The condition has a single port group. This
    // matches if that port group is in the packet's list of port groups,
    // which indicates that the packet ingressed through a port in the
    // condition's port group.
    private boolean matchPortGroup(
            Set<UUID> pktGroups, UUID condGroup, boolean negate) {
        return portGroup == null ||
                negate ^ (pktGroups != null && pktGroups.contains(condGroup));
    }

    private <E extends Comparable<E>> boolean matchRange(
            Range<E> range, E pktField, boolean negate) {
        return range == null ||
                negate ^ (null == pktField || range.isInside(pktField));
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Condition [");
        if (conjunctionInv)
            sb.append("conjunctionInv=true, ");
        if (matchForwardFlow)
            sb.append("matchForwardFlow=true, ");
        if (matchReturnFlow)
            sb.append("matchReturnFlow=true, ");
        if (inPortIds != null && !inPortIds.isEmpty()) {
            sb.append("inPortIds={");
            for (UUID id : inPortIds) {
                sb.append(id.toString()).append(",");
            }
            sb.append("}, ");
            if (inPortInv)
                sb.append("inPortInv=").append(inPortInv).append(", ");
        }
        if (outPortIds != null && !outPortIds.isEmpty()) {
            sb.append("outPortIds={");
            for (UUID id : outPortIds) {
                sb.append(id.toString()).append(",");
            }
            sb.append("}, ");
            if (outPortInv)
                sb.append("outPortInv=").append(outPortInv).append(", ");
        }
        if (portGroup != null) {
            sb.append("portGroup=").append(portGroup).append(", ");
            if (invPortGroup)
                sb.append("invPortGroup=true, ");
        }
        if (ipAddrGroupIdDst != null) {
            sb.append("ipAddrGroupIdDst=").append(ipAddrGroupIdDst).append(
                    ", ");
            if (invIpAddrGroupIdDst)
                sb.append("invIpAddrGroupIdDst=true, ");
        }
        if (ipAddrGroupIdSrc != null) {
            sb.append("ipAddrGroupIdSrc=").append(ipAddrGroupIdSrc).append(
                    ", ");
            if (invIpAddrGroupIdSrc)
                sb.append("invIpAddrGroupIdSrc=true, ");
        }
        if (null != etherType) {
            sb.append("etherType=").append(etherType.intValue()).append(", ");
            if(invDlType)
                sb.append("invDlType").append(invDlType).append(", ");
        }
        if (null != ethSrc) {
            sb.append("ethSrc=").append(ethSrc).append(", ");
            if (ethSrcMask != NO_MASK)
                sb.append("ethSrcMask=").append(MAC.maskToString(ethSrcMask))
                        .append(", ");
            if(invDlSrc)
                sb.append("invDlSrc").append(invDlSrc).append(", ");
        }
        if (null != ethDst) {
            sb.append("ethDst=").append(ethDst).append(", ");
            if(invDlDst)
                sb.append("invDlDst").append(invDlDst).append(", ");
        }
        if (null != nwTos) {
            sb.append("nwTos=").append(nwTos).append(", ");
            if(nwTosInv)
                sb.append("nwTosInv").append(nwTosInv).append(", ");
        }
        if (null != nwProto) {
            sb.append("nwProto=").append(nwProto).append(", ");
            if(nwProtoInv)
                sb.append("nwProtoInv").append(nwProtoInv).append(", ");
        }
        if (null != nwSrcIp) {
            sb.append("nwSrcIp=").append(nwSrcIp.toString()).append(", ");
            if(nwSrcInv)
                sb.append("nwSrcInv").append(nwSrcInv).append(", ");
        }
        if (null != nwDstIp) {
            sb.append("nwDstIp=").append(nwDstIp.toString()).append(", ");
            if(nwDstInv)
                sb.append("nwDstInv").append(nwDstInv).append(", ");
        }
        if (null != tpSrc) {
            sb.append("tpSrc=").append(tpSrc).append(", ");
            if(tpSrcInv)
                sb.append("tpSrcInv").append(tpSrcInv).append(", ");
        }
        if (null != tpDst) {
            sb.append("tpDst=").append(tpDst).append(", ");
            if(tpDstInv)
                sb.append("tpDstInv").append(tpDstInv).append(", ");
        }
        if (null != traversedDevice) {
            sb.append("traversedDev=").append(traversedDevice).append(", ");
            if(traversedDeviceInv)
                sb.append("traversedDevInv").append(traversedDeviceInv).append(", ");
        }
        sb.append("]");
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
                Objects.equals(etherType, c.etherType) &&
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
                ipAddrGroupIdDst, ipAddrGroupIdSrc, etherType, ethSrc, ethDst,
                nwTos, nwProto, nwSrcIp, nwDstIp, tpSrc, tpDst, traversedDevice);
    }
}
