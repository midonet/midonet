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
package org.midonet.odp;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.packets.Ethernet;

/**
 * An abstraction over the Ovs kernel datapath Packet entity. Contains an
 * {@link FlowMatch} object and a <code>byte[] data</code> member when triggered
 * via a kernel notification.
 *
 * @see FlowMatch
 */
public class Packet {
    private static final Logger log = LoggerFactory
            .getLogger("org.midonet.netlink.netlink-proto");

    public enum Reason {
        FlowTableMiss,
        FlowActionUserspace,
    }

    private FlowMatch match;
    private Long userData;
    private Reason reason;
    private Ethernet eth;
    public final int packetLen;

    // user field used by midolman packet pipeline to track time statistics,
    // ignored in equals() and hashCode()
    public long startTimeNanos = 0;

    public Packet(Ethernet eth, FlowMatch match, int len) {
        this.eth = eth;
        this.match = match;
        this.packetLen = len;
    }

    public Packet(Ethernet eth, FlowMatch match) {
        this(eth, match, (eth != null) ? eth.length() : 0);
    }

    public Ethernet getEthernet() {
        return eth;
    }

    public void setEthernet(Ethernet eth) {
        this.eth = eth;
    }

    public byte[] getData() {
        return eth.serialize();
    }

    public FlowMatch getMatch() {
        return match;
    }

    public void setUserData(Long userData) {
        this.userData = userData;
    }

    public Long getUserData() {
        return userData;
    }

    public Reason getReason() {
        return reason;
    }

    public Packet setReason(Reason reason) {
        this.reason = reason;
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        @SuppressWarnings("unchecked")
        Packet that = (Packet) o;

        return Objects.equals(this.eth, that.eth)
            && Objects.equals(this.match, that.match)
            && Objects.equals(this.userData, that.userData)
            && (this.reason == that.reason);
    }

    @Override
    public int hashCode() {
        int result = Objects.hashCode(eth);
        result = 31 * result + Objects.hashCode(match);
        result = 31 * result + Objects.hashCode(userData);
        result = 31 * result + Objects.hashCode(reason);
        return result;
    }

    @Override
    public String toString() {
        return "Packet{" +
            "data=" + eth +
            ", match=" + match +
            ", userData=" + userData +
            ", reason=" + reason +
            '}';
    }
}
