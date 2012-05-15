/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.rules;

import java.util.Set;
import java.util.UUID;

import com.midokura.midolman.openflow.MidoMatch;
import com.midokura.midolman.packets.IPv4;
import com.midokura.midolman.util.Net;

public class Condition {
    public boolean conjunctionInv;
    public transient Set<UUID> inPortIds;
    public boolean inPortInv;
    public transient Set<UUID> outPortIds;
    public boolean outPortInv;
    public transient Set<UUID> portGroups;
    public boolean invPortGroups;
    public byte nwTos;
    public boolean nwTosInv;
    public byte nwProto;
    public boolean nwProtoInv;
    public int nwSrcIp;
    public byte nwSrcLength;
    public boolean nwSrcInv;
    public int nwDstIp;
    public byte nwDstLength;
    public boolean nwDstInv;
    public short tpSrcStart;
    public short tpSrcEnd;
    public boolean tpSrcInv;
    public short tpDstStart;
    public short tpDstEnd;
    public boolean tpDstInv;

    // Getter and setter for the transient property inPortIds.
    public Set<UUID> getInPortIds() { return inPortIds; }
    public void setInPortIds(Set<UUID> inPortIds) { this.inPortIds = inPortIds; }

    // Getter and setter for the transient property outPortIds.
    public Set<UUID> getOutPortIds() { return outPortIds; }
    public void setOutPortIds(Set<UUID> outPortIds) { this.outPortIds = outPortIds; }

    // Getter and setter for the transient property port.
    public Set<UUID> getPortGroups() { return portGroups; }
    public void setPortGroups(Set<UUID> groups) { portGroups = groups; }

    /* Custom accessors for Jackson serialization with more readable IP addresses. */

    public String getNwSrcIp() {
    	return Net.convertIntAddressToString(this.nwSrcIp);
    }

    public void setNwSrcIp(String addr) {
    	this.nwSrcIp = Net.convertStringAddressToInt(addr);
    }

    public String getNwDstIp() {
    	return Net.convertIntAddressToString(this.nwDstIp);
    }

    public void setNwDstIp(String addr) {
    	this.nwDstIp = Net.convertStringAddressToInt(addr);
    }

    // Default constructor for the Jackson deserialization.
    public Condition() { super(); }

    public boolean matches(UUID inPortId, UUID outPortId, MidoMatch pktMatch) {
        return matches(inPortId, outPortId, pktMatch, null);
    }

    public boolean matches(UUID inPortId, UUID outPortId, MidoMatch pktMatch,
                           Set<UUID> senderGroups) {
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
        if (null != inPortIds && inPortIds.size() > 0
                && inPortIds.contains(inPortId) == inPortInv)
            return conjunctionInv;
        if (null != outPortIds && outPortIds.size() > 0
                && outPortIds.contains(outPortId) == outPortInv)
            return conjunctionInv;
        if (null != portGroups && portGroups.size() > 0) {
            boolean matchedGroup = false;
            if (senderGroups != null) {
                for (UUID group : senderGroups) {
                    if (portGroups.contains(group)) {
                        matchedGroup = true;
                        break;
                    }
                }
            }
            if (matchedGroup == invPortGroups)
                return conjunctionInv;
        }
        if (nwTos != 0
                && (nwTos == pktMatch.getNetworkTypeOfService()) == nwTosInv)
            return conjunctionInv;
        if (nwProto != 0
                && (nwProto == pktMatch.getNetworkProtocol()) == nwProtoInv)
            return conjunctionInv;
        int shift = 32 - nwSrcLength;
        if (nwSrcIp != 0
                && nwSrcLength > 0
                && (nwSrcIp >>> shift == pktMatch.getNetworkSource() >>> shift) == nwSrcInv)
            return conjunctionInv;
        shift = 32 - nwDstLength;
        if (nwDstIp != 0
                && nwDstLength > 0
                && (nwDstIp >>> shift == pktMatch.getNetworkDestination() >>> shift) == nwDstInv)
            return conjunctionInv;
        short tpSrc = pktMatch.getTransportSource();
        if (tpSrcEnd != 0
                && (tpSrcStart <= tpSrc && tpSrc <= tpSrcEnd) == tpSrcInv)
            return conjunctionInv;
        short tpDst = pktMatch.getTransportDestination();
        if (tpDstEnd != 0
                && (tpDstStart <= tpDst && tpDst <= tpDstEnd) == tpDstInv)
            return conjunctionInv;
        return !conjunctionInv;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Condition condition = (Condition) o;

        if (conjunctionInv != condition.conjunctionInv) return false;
        if (inPortInv != condition.inPortInv) return false;
        if (invPortGroups != condition.invPortGroups) return false;
        if (nwDstInv != condition.nwDstInv) return false;
        if (nwDstIp != condition.nwDstIp) return false;
        if (nwDstLength != condition.nwDstLength) return false;
        if (nwProto != condition.nwProto) return false;
        if (nwProtoInv != condition.nwProtoInv) return false;
        if (nwSrcInv != condition.nwSrcInv) return false;
        if (nwSrcIp != condition.nwSrcIp) return false;
        if (nwSrcLength != condition.nwSrcLength) return false;
        if (nwTos != condition.nwTos) return false;
        if (nwTosInv != condition.nwTosInv) return false;
        if (outPortInv != condition.outPortInv) return false;
        if (tpDstEnd != condition.tpDstEnd) return false;
        if (tpDstInv != condition.tpDstInv) return false;
        if (tpDstStart != condition.tpDstStart) return false;
        if (tpSrcEnd != condition.tpSrcEnd) return false;
        if (tpSrcInv != condition.tpSrcInv) return false;
        if (tpSrcStart != condition.tpSrcStart) return false;
        if (inPortIds != null ? !inPortIds.equals(condition.inPortIds) : condition.inPortIds != null)
            return false;
        if (outPortIds != null ? !outPortIds.equals(condition.outPortIds) : condition.outPortIds != null)
            return false;
        if (portGroups != null ? !portGroups.equals(condition.portGroups) : condition.portGroups != null)
            return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = (conjunctionInv ? 1 : 0);
        result = 31 * result + (inPortIds != null ? inPortIds.hashCode() : 0);
        result = 31 * result + (inPortInv ? 1 : 0);
        result = 31 * result + (outPortIds != null ? outPortIds.hashCode() : 0);
        result = 31 * result + (outPortInv ? 1 : 0);
        result = 31 * result + (portGroups != null ? portGroups.hashCode() : 0);
        result = 31 * result + (invPortGroups ? 1 : 0);
        result = 31 * result + (int) nwTos;
        result = 31 * result + (nwTosInv ? 1 : 0);
        result = 31 * result + (int) nwProto;
        result = 31 * result + (nwProtoInv ? 1 : 0);
        result = 31 * result + nwSrcIp;
        result = 31 * result + (int) nwSrcLength;
        result = 31 * result + (nwSrcInv ? 1 : 0);
        result = 31 * result + nwDstIp;
        result = 31 * result + (int) nwDstLength;
        result = 31 * result + (nwDstInv ? 1 : 0);
        result = 31 * result + (int) tpSrcStart;
        result = 31 * result + (int) tpSrcEnd;
        result = 31 * result + (tpSrcInv ? 1 : 0);
        result = 31 * result + (int) tpDstStart;
        result = 31 * result + (int) tpDstEnd;
        result = 31 * result + (tpDstInv ? 1 : 0);
        return result;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Condition [");
        if (conjunctionInv)
            sb.append("conjunctionInv=").append(conjunctionInv).append(",");
        if (inPortIds != null && inPortIds.size() > 0) {
            sb.append("inPortIds={");
            for (UUID id : inPortIds) {
                sb.append(id.toString()).append(",");
            }
            sb.append("},");
            if (inPortInv)
                sb.append("inPortInv=").append(inPortInv).append(",");
        }
        if (outPortIds != null && outPortIds.size() > 0) {
            sb.append("outPortIds={");
            for (UUID id : outPortIds) {
                sb.append(id.toString()).append(",");
            }
            sb.append("},");
            if (outPortInv)
                sb.append("outPortInv=").append(outPortInv).append(",");
        }
        if (portGroups != null && portGroups.size() > 0) {
            sb.append("portGroups={");
            for (UUID id : portGroups) {
                sb.append(id.toString()).append(",");
            }
            sb.append("},");
            if (invPortGroups)
                sb.append("invPortGroups=").append(invPortGroups).append(",");
        }
        if (0 != nwTos) {
            sb.append("nwTos=").append(nwTos).append(",");
            if(nwTosInv)
                sb.append("nwTosInv").append(nwTosInv).append(",");
        }
        if (0 != nwProto) {
            sb.append("nwProto=").append(nwProto).append(",");
            if(nwProtoInv)
                sb.append("nwProtoInv").append(nwProtoInv).append(",");
        }
        if (0 != nwSrcIp) {
            sb.append("nwSrcIp=").append(IPv4.fromIPv4Address(nwSrcIp));
            sb.append(",nwSrcLength=").append(nwSrcLength).append(",");
            if(nwSrcInv)
                sb.append("nwSrcInv").append(nwSrcInv).append(",");
        }
        if (0 != nwDstIp) {
            sb.append("nwDstIp=").append(IPv4.fromIPv4Address(nwDstIp));
            sb.append(",nwDstLength=").append(nwDstLength).append(",");
            if(nwDstInv)
                sb.append("nwDstInv").append(nwDstInv).append(",");
        }
        if (0 != tpSrcEnd) {
            sb.append("tpSrcStart=").append(tpSrcStart).append(",");
            sb.append("tpSrcEnd=").append(tpSrcEnd).append(",");
            if(tpSrcInv)
                sb.append("tpSrcInv").append(tpSrcInv).append(",");
        }
        if (0 != tpDstEnd) {
            sb.append("tpDstStart=").append(tpDstStart).append(",");
            sb.append("tpDstEnd=").append(tpDstEnd).append(",");
            if(tpDstInv)
                sb.append("tpDstInv").append(tpDstInv).append(",");
        }
        sb.append("]");
        return sb.toString();
    }
}
