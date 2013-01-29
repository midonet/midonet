/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.rules;

import com.midokura.packets.IPv4;
import com.midokura.midolman.util.Net;

public class NatTarget {

    public int nwStart;
    public int nwEnd;
    public int tpStart;
    public int tpEnd;

    public NatTarget(int nwStart, int nwEnd, int tpStart, int tpEnd) {
        this.nwStart = nwStart;
        this.nwEnd = nwEnd;
        this.tpStart = tpStart;
        this.tpEnd = tpEnd;
    }

    // Default constructor for the Jackson deserialization.
    public NatTarget() { super(); }

    /* Custom accessors for Jackson serialization with more readable IPs. */

    public String getNwStart() {
        return Net.convertIntAddressToString(this.nwStart);
    }

    public void setNwStart(String addr) {
        this.nwStart = Net.convertStringAddressToInt(addr);
    }

    public String getNwEnd() {
        return Net.convertIntAddressToString(this.nwEnd);
    }

    public void setNwEnd(String addr) {
        this.nwEnd = Net.convertStringAddressToInt(addr);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other)
            return true;
        if (!(other instanceof NatTarget))
            return false;
        NatTarget nt = (NatTarget) other;
        return nwStart == nt.nwStart && nwEnd == nt.nwEnd
                && tpStart == nt.tpStart && tpEnd == nt.tpEnd;
    }

    @Override
    public int hashCode() {
        int hash = nwStart;
        hash = 13 * hash + nwEnd;
        hash = 17 * hash + tpStart;
        return 23 * hash + tpEnd;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("NatTarget [");
        sb.append("nwStart=").append(IPv4.fromIPv4Address(nwStart));
        sb.append(", nwEnd=").append(IPv4.fromIPv4Address(nwEnd));
        sb.append(", tpStart=").append(tpStart & 0xffff);
        sb.append(", tpEnd=").append(tpEnd & 0xffff);
        sb.append("]");
        return sb.toString();
    }
}
