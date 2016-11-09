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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.data.ZoomObject;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.util.IPAddressUtil;
import org.midonet.packets.IPv4Addr;

@ZoomClass(clazz = Topology.Rule.NatTarget.class)
public class NatTarget extends ZoomObject {

    @ZoomField(name = "nw_start", converter = IPAddressUtil.Converter.class)
    public IPv4Addr nwStart;
    @ZoomField(name = "nw_end", converter = IPAddressUtil.Converter.class)
    public IPv4Addr nwEnd;
    @ZoomField(name = "tp_start")
    public int tpStart;
    @ZoomField(name = "tp_end")
    public int tpEnd;

    public NatTarget() { }

    @JsonCreator
    public NatTarget(@JsonProperty("nwStart") IPv4Addr nwStart,
                     @JsonProperty("nwEnd") IPv4Addr nwEnd,
                     @JsonProperty("tpStart") int tpStart,
                     @JsonProperty("tpEnd") int tpEnd) {
        this.nwStart = nwStart;
        this.nwEnd = nwEnd;
        this.tpStart = tpStart & 0xffff;
        this.tpEnd = tpEnd & 0xffff;
    }

    public NatTarget(int nwStart, int nwEnd, int tpStart, int tpEnd) {
        this(new IPv4Addr(nwStart), new IPv4Addr(nwEnd), tpStart, tpEnd);
    }

    public NatTarget(IPv4Addr nwStart, IPv4Addr nwEnd) {
        this(nwStart, nwEnd, 1, 0xffff);
    }

    public NatTarget(IPv4Addr ipAddr) {
        this(ipAddr, ipAddr);
    }

    public NatTarget(IPv4Addr ipAddr, int tpStart, int tpEnd) {
        this(ipAddr, ipAddr, tpStart, tpEnd);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other)
            return true;
        if (!(other instanceof NatTarget))
            return false;

        NatTarget nt = (NatTarget) other;
        return Objects.equals(nwStart, nt.nwStart) &&
               Objects.equals(nwEnd, nt.nwEnd) &&
               tpStart == nt.tpStart && tpEnd == nt.tpEnd;
    }

    @Override
    public int hashCode() {
        int hash = nwStart != null ? nwStart.hashCode() : 0;
        hash = 13 * hash + (nwEnd != null ? nwEnd.hashCode() : 0);
        hash = 17 * hash + tpStart;
        return 23 * hash + tpEnd;
    }

    @Override
    public String toString() {
        return "NatTarget [nwStart=" + nwStart + " nwEnd=" + nwEnd +
               " tpStart=" + tpStart + " tpEnd=" + tpEnd + "]";
    }
}
