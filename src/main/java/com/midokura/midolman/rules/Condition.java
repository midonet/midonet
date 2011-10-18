package com.midokura.midolman.rules;

import java.util.Set;
import java.util.UUID;

import com.midokura.midolman.openflow.MidoMatch;
import com.midokura.midolman.packets.IPv4;

public class Condition {
    public boolean conjunctionInv;
    public transient Set<UUID> inPortIds;
    public boolean inPortInv;
    public transient Set<UUID> outPortIds;
    public boolean outPortInv;
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

    // Default constructor for the Jackson deserialization.
    public Condition() { super(); }

    public boolean matches(UUID inPortId, UUID outPortId, MidoMatch pktMatch) {
        /*
         * Given a packet P and a subCondition x, 'xInv x(P)' is true iff: 1)
         * xInv is false and x(P) is true 2) xInv is true and x(P) is false In
         * other words, 'xInv x(P)' is false if xInv == x(P). The entire
         * condition can be expressed as a conjunction: conjunctionInv (x1Inv
         * x1(P) ^ ... ^ x_nInv x_n(P)) So we can short-circuit evaluation of
         * the conjunction whenever any x_iInv x_i(P) evaluates to false and we
         * then return the value of 'conjunctionInv'. If the conjunction
         * evaluates to true, then we return 'NOT conjunctionInv'.
         */
        if (null != inPortIds && inPortIds.size() > 0
                && inPortIds.contains(inPortId) == inPortInv)
            return conjunctionInv;
        if (null != outPortIds && outPortIds.size() > 0
                && outPortIds.contains(outPortId) == outPortInv)
            return conjunctionInv;
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
    public int hashCode() {
        final int prime = 41;
        int result = 1;
        int bHash = conjunctionInv ? 1231 : 1237;
        result = prime * result + bHash;
        result = prime * result + (null == inPortIds ? 0 : inPortIds.hashCode());
        bHash = inPortInv ? 1231 : 1237;
        result = prime * result + bHash;
        result = prime * result + (null == outPortIds? 0 : outPortIds.hashCode());
        bHash = outPortInv ? 1231 : 1237;
        result = prime * result + bHash;
        result = prime * result + nwTos;
        bHash = nwTosInv ? 1231 : 1237;
        result = prime * result + bHash;
        result = prime * result + nwProto;
        bHash = nwProtoInv ? 1231 : 1237;
        result = prime * result + bHash;
        result = prime * result + nwSrcIp;
        result = prime * result + nwSrcLength;
        bHash = nwSrcInv ? 1231 : 1237;
        result = prime * result + bHash;
        result = prime * result + nwDstIp;
        result = prime * result + nwDstLength;
        bHash = nwDstInv ? 1231 : 1237;
        result = prime * result + bHash;
        result = prime * result + tpSrcStart;
        result = prime * result + tpSrcEnd;
        bHash = tpSrcInv ? 1231 : 1237;
        result = prime * result + bHash;
        result = prime * result + tpDstStart;
        result = prime * result + tpDstEnd;
        bHash = tpDstInv ? 1231 : 1237;
        result = prime * result + bHash;
        return result;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other)
            return true;
        if (!(other instanceof Condition))
            return false;
        Condition c = (Condition) other;
        return conjunctionInv == c.conjunctionInv
                && ((null == inPortIds && null == c.inPortIds) || inPortIds
                        .equals(c.inPortIds))
                && inPortInv == c.inPortInv
                && ((null == outPortIds && null == c.outPortIds) || outPortIds
                        .equals(c.outPortIds)) && outPortInv == c.outPortInv
                && nwTos == c.nwTos && nwTosInv == c.nwTosInv
                && nwProto == c.nwProto && nwProtoInv == c.nwProtoInv
                && nwSrcIp == c.nwSrcIp && nwSrcLength == c.nwSrcLength
                && nwSrcInv == c.nwSrcInv && nwDstIp == c.nwDstIp
                && nwDstLength == c.nwDstLength && nwDstInv == c.nwDstInv
                && tpSrcStart == c.tpSrcStart && tpSrcEnd == c.tpSrcEnd
                && tpSrcInv == c.tpSrcInv && tpDstStart == c.tpDstStart
                && tpDstEnd == c.tpDstEnd && tpDstInv == c.tpDstInv;
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
