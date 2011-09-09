package com.midokura.midolman.rules;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import com.midokura.midolman.openflow.MidoMatch;

public class Condition implements Serializable {

    private static final long serialVersionUID = 5676283307439852254L;

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

    private void writeObject(ObjectOutputStream out) throws IOException {
        out.defaultWriteObject();
        if (null == inPortIds)
            out.writeInt(0);
        else {
            out.writeInt(inPortIds.size());
            for (UUID id : inPortIds)
                out.writeObject(id);
        }
        if (null == outPortIds)
            out.writeInt(0);
        else {
            out.writeInt(outPortIds.size());
            for (UUID id : outPortIds)
                out.writeObject(id);
        }
    }
    // Getter and setter for the transient property inPortIds.
    public Set<UUID> getInPortIds() { return inPortIds; }
    public void setInPortIds(Set<UUID> inPortIds) { this.inPortIds = inPortIds; }
	
    // Getter and setter for the transient property outPortIds.
    public Set<UUID> getOutPortIds() { return outPortIds; }
    public void setOutPortIds(Set<UUID> outPortIds) { this.outPortIds = outPortIds; }

    private void readObject(ObjectInputStream in) throws IOException,
            ClassNotFoundException {
        in.defaultReadObject();
        int numInPortIds = in.readInt();
        if (0 == numInPortIds)
            inPortIds = null;
        else {
            inPortIds = new HashSet<UUID>();
            for (int i = 0; i < numInPortIds; i++)
                inPortIds.add((UUID) in.readObject());
        }
        int numOutPortIds = in.readInt();
        if (0 == numOutPortIds)
            outPortIds = null;
        else {
            outPortIds = new HashSet<UUID>();
            for (int i = 0; i < numOutPortIds; i++)
                outPortIds.add((UUID) in.readObject());
        }
    }
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
        if (null != inPortIds && inPortIds.contains(inPortId) == inPortInv)
            return conjunctionInv;
        if (null != outPortIds && outPortIds.contains(outPortId) == outPortInv)
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
        result = prime * result + inPortIds.hashCode();
        bHash = inPortInv ? 1231 : 1237;
        result = prime * result + bHash;
        result = prime * result + outPortIds.hashCode();
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
}
