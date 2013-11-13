/*
 * Copyright 2011 Midokura KK
 */

package org.midonet.midolman.rules;

import java.util.Set;
import java.util.UUID;

import org.midonet.packets.IPAddr;
import org.midonet.packets.IPSubnet;
import org.midonet.packets.MAC;
import org.midonet.packets.Unsigned;
import org.midonet.sdn.flows.WildcardMatch;
import org.midonet.util.Range;

public class Condition {
    public boolean conjunctionInv;
    public boolean matchForwardFlow;
    public boolean matchReturnFlow;
    public Set<UUID> inPortIds;
    public boolean inPortInv;
    public Set<UUID> outPortIds;
    public boolean outPortInv;
    public UUID portGroup;
    public boolean invPortGroup;
    public Integer dlType;
    public boolean invDlType;
    public MAC dlSrc;
    public boolean invDlSrc;
    public MAC dlDst;
    public boolean invDlDst;
    public Byte nwTos;
    public boolean nwTosInv;
    public Byte nwProto;
    public boolean nwProtoInv;
    public IPSubnet nwSrcIp;
    public IPSubnet nwDstIp;
    public Range<Integer> tpSrc;
    public Range<Integer> tpDst;
    public boolean nwSrcInv;
    public boolean nwDstInv;
    public boolean tpSrcInv;
    public boolean tpDstInv;

    // Default constructor for the Jackson deserialization.
    public Condition() { super(); }

    public boolean matches(ChainPacketContext fwdInfo, WildcardMatch pktMatch,
                           boolean isPortFilter) {
        UUID inPortId = isPortFilter ? null : fwdInfo.inPortId();
        UUID outPortId = isPortFilter ? null : fwdInfo.outPortId();
        Set<UUID> senderGroups = fwdInfo.portGroups();
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
        boolean cond = true;
        if (matchForwardFlow && !fwdInfo.isForwardFlow())
            cond = false;
        else if (matchReturnFlow && fwdInfo.isForwardFlow())
            cond = false;
        else if (null != portGroup) {
            boolean innerCond = senderGroups != null &&
                                senderGroups.contains(portGroup);
            innerCond = invPortGroup? !innerCond : innerCond;
            if (!innerCond)
                cond = false;
        }
        if (!cond)
            return conjunctionInv;

        IPAddr pmSrcIP = pktMatch.getNetworkSourceIP();
        IPAddr pmDstIP = pktMatch.getNetworkDestinationIP();
        if (!matchPort(this.inPortIds, inPortId, this.inPortInv)
            || !matchPort(this.outPortIds, outPortId, this.outPortInv)
            || !matchField(dlType, pktMatch.getEtherType() != null ?
            Unsigned.unsign(pktMatch.getEtherType()) : null, invDlType)
            || !matchField(dlSrc, pktMatch.getEthernetSource(), invDlSrc)
            || !matchField(dlDst, pktMatch.getEthernetDestination(), invDlDst)
            || !matchField(nwTos, pktMatch.getNetworkTOS(), nwTosInv)
            || !matchField(nwProto, pktMatch.getNetworkProtocolObject(), nwProtoInv)
            || !matchIP(nwSrcIp, pmSrcIP, nwSrcInv)
            || !matchIP(nwDstIp, pmDstIP, nwDstInv)
            || !matchRange(tpSrc,
                           pktMatch.getTransportSourceObject(), tpSrcInv)
            || !matchRange(tpDst,
                           pktMatch.getTransportDestinationObject(), tpDstInv)
            )
            cond = false;
        return conjunctionInv? !cond : cond;
    }

    private boolean matchPort(Set<UUID> condPorts, UUID port, boolean negate) {
        // Packet is considered to match if the field is null or empty set.
        if (condPorts == null || condPorts.size() == 0)
            return true;
        boolean cond = condPorts.contains(port);
        return negate? !cond :cond;
    }

    /**
     *
     * @param condField
     * @param pktField
     * @param negate This is only considered if the condField is NOT null
     * @return
     */
    private <T> boolean matchField(T condField, T pktField, boolean negate) {
        // Packet is considered to match if the condField is not specified.
        if (condField == null)
            return true;
        boolean cond = condField.equals(pktField);
        return negate? !cond : cond;
    }

    private boolean matchIP(IPSubnet condSubnet, IPAddr pktIp, boolean negate) {
        // Packet is considered to match if the condField is not specified.
        if (condSubnet == null)
            return true;
        boolean cond = false;
        if (pktIp != null && condSubnet.containsAddress(pktIp))
            cond = true;
        return negate? !cond : cond;
    }

    private <E extends Comparable<E>> boolean matchRange(Range<E> range, E pktField, boolean negate) {
        // Packet is considered to match if the condField is not specified.
        boolean matches = (range == null || null == pktField) ||
                          range.isInside(pktField);
        return negate ? !matches : matches;
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
        if (inPortIds != null && inPortIds.size() > 0) {
            sb.append("inPortIds={");
            for (UUID id : inPortIds) {
                sb.append(id.toString()).append(",");
            }
            sb.append("}, ");
            if (inPortInv)
                sb.append("inPortInv=").append(inPortInv).append(", ");
        }
        if (outPortIds != null && outPortIds.size() > 0) {
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
        if (null != dlType) {
            sb.append("dlType=").append(dlType.intValue()).append(", ");
            if(invDlType)
                sb.append("invDlType").append(invDlType).append(", ");
        }
        if (null != dlSrc) {
            sb.append("dlSrc=").append(dlSrc).append(", ");
            if(invDlSrc)
                sb.append("invDlSrc").append(invDlSrc).append(", ");
        }
        if (null != dlDst) {
            sb.append("dlDst=").append(dlDst).append(", ");
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
        sb.append("]");
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Condition condition = (Condition) o;

        if (conjunctionInv != condition.conjunctionInv) return false;
        if (inPortInv != condition.inPortInv) return false;
        if (invDlDst != condition.invDlDst) return false;
        if (invDlSrc != condition.invDlSrc) return false;
        if (invDlType != condition.invDlType) return false;
        if (invPortGroup != condition.invPortGroup) return false;
        if (matchForwardFlow != condition.matchForwardFlow) return false;
        if (matchReturnFlow != condition.matchReturnFlow) return false;
        if (nwDstInv != condition.nwDstInv) return false;
        if (nwProtoInv != condition.nwProtoInv) return false;
        if (nwSrcInv != condition.nwSrcInv) return false;
        if (nwTosInv != condition.nwTosInv) return false;
        if (outPortInv != condition.outPortInv) return false;
        if (tpDstInv != condition.tpDstInv) return false;
        if (tpSrcInv != condition.tpSrcInv) return false;
        if (dlDst != null ? !dlDst.equals(condition.dlDst) : condition.dlDst != null)
            return false;
        if (dlSrc != null ? !dlSrc.equals(condition.dlSrc) : condition.dlSrc != null)
            return false;
        if (dlType != null ? !dlType.equals(condition.dlType) : condition.dlType != null)
            return false;
        if (inPortIds != null ? !inPortIds.equals(condition.inPortIds) : condition.inPortIds != null)
            return false;
        if (nwDstIp != null ? !nwDstIp.equals(condition.nwDstIp) : condition.nwDstIp != null)
            return false;
        if (nwProto != null ? !nwProto.equals(condition.nwProto) : condition.nwProto != null)
            return false;
        if (nwSrcIp != null ? !nwSrcIp.equals(condition.nwSrcIp) : condition.nwSrcIp != null)
            return false;
        if (nwTos != null ? !nwTos.equals(condition.nwTos) : condition.nwTos != null)
            return false;
        if (outPortIds != null ? !outPortIds.equals(condition.outPortIds) : condition.outPortIds != null)
            return false;
        if (portGroup != null ? !portGroup.equals(condition.portGroup) : condition.portGroup != null)
            return false;
        if (tpDst != null ? !tpDst.equals(condition.tpDst) : condition.tpDst != null)
            return false;
        if (tpSrc != null ? !tpSrc.equals(condition.tpSrc) : condition.tpSrc != null)
            return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = (conjunctionInv ? 1 : 0);
        result = 31 * result + (matchForwardFlow ? 1 : 0);
        result = 31 * result + (matchReturnFlow ? 1 : 0);
        result = 31 * result + (inPortIds != null ? inPortIds.hashCode() : 0);
        result = 31 * result + (inPortInv ? 1 : 0);
        result = 31 * result + (outPortIds != null ? outPortIds.hashCode() : 0);
        result = 31 * result + (outPortInv ? 1 : 0);
        result = 31 * result + (portGroup != null ? portGroup.hashCode() : 0);
        result = 31 * result + (invPortGroup ? 1 : 0);
        result = 31 * result + (dlType != null ? dlType.hashCode() : 0);
        result = 31 * result + (invDlType ? 1 : 0);
        result = 31 * result + (dlSrc != null ? dlSrc.hashCode() : 0);
        result = 31 * result + (invDlSrc ? 1 : 0);
        result = 31 * result + (dlDst != null ? dlDst.hashCode() : 0);
        result = 31 * result + (invDlDst ? 1 : 0);
        result = 31 * result + (nwTos != null ? nwTos.hashCode() : 0);
        result = 31 * result + (nwTosInv ? 1 : 0);
        result = 31 * result + (nwProto != null ? nwProto.hashCode() : 0);
        result = 31 * result + (nwProtoInv ? 1 : 0);
        result = 31 * result + (nwSrcIp != null ? nwSrcIp.hashCode() : 0);
        result = 31 * result + (nwSrcInv ? 1 : 0);
        result = 31 * result + (nwDstIp != null ? nwDstIp.hashCode() : 0);
        result = 31 * result + (nwDstInv ? 1 : 0);
        result = 31 * result + (tpSrc != null ? tpSrc.hashCode() : 0);
        result = 31 * result + (tpSrcInv ? 1 : 0);
        result = 31 * result + (tpDst != null ? tpDst.hashCode() : 0);
        result = 31 * result + (tpDstInv ? 1 : 0);
        return result;
    }
}
