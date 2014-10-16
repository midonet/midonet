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

import org.midonet.packets.IPv4Addr;

public class NatTarget {

    public IPv4Addr nwStart;
    public IPv4Addr nwEnd;
    public int tpStart;
    public int tpEnd;

    public NatTarget(IPv4Addr nwStart, IPv4Addr nwEnd, int tpStart, int tpEnd) {
        this.nwStart = nwStart;
        this.nwEnd = nwEnd;
        this.tpStart = tpStart;
        this.tpEnd = tpEnd;
    }

    public NatTarget(int nwStart, int nwEnd, int tpStart, int tpEnd) {
        this.nwStart = new IPv4Addr(nwStart);
        this.nwEnd = new IPv4Addr(nwEnd);
        this.tpStart = tpStart;
        this.tpEnd = tpEnd;
    }

    public NatTarget(IPv4Addr nwStart, IPv4Addr nwEnd) {
        this(nwStart, nwEnd, 1, 65535);
    }

    public NatTarget(IPv4Addr ipAddr) {
        this(ipAddr, ipAddr);
    }

    public NatTarget(IPv4Addr ipAddr, int tpStart, int tpEnd) {
        this(ipAddr, ipAddr, tpStart, tpEnd);
    }

    // Default constructor for the Jackson deserialization.
    public NatTarget() { super(); }

    /* Custom accessors for Jackson serialization with more readable IPs. */

    public String getNwStart() {
        return this.nwStart.toString();
    }

    public void setNwStart(String addr) {
        this.nwStart = IPv4Addr.fromString(addr);
    }

    public String getNwEnd() {
        return this.nwEnd.toString();
    }

    public void setNwEnd(String addr) {
        this.nwEnd = IPv4Addr.fromString(addr);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other)
            return true;
        if (!(other instanceof NatTarget))
            return false;

        NatTarget nt = (NatTarget) other;
        if (nt.nwStart == null && nwStart != null)
            return false;
        if (nt.nwEnd == null && nwEnd != null)
            return false;
        if (!nt.nwStart.equals(nwStart))
            return false;
        if (!nt.nwEnd.equals(nwEnd))
            return false;
        return tpStart == nt.tpStart && tpEnd == nt.tpEnd;
    }

    @Override
    public int hashCode() {
        int hash = nwStart.hashCode();
        hash = 13 * hash + nwEnd.hashCode();
        hash = 17 * hash + tpStart;
        return 23 * hash + tpEnd;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("NatTarget [");
        sb.append("nwStart=").append(nwStart.toString());
        sb.append(", nwEnd=").append(nwEnd.toString());
        sb.append(", tpStart=").append(tpStart & 0xffff);
        sb.append(", tpEnd=").append(tpEnd & 0xffff);
        sb.append("]");
        return sb.toString();
    }
}
