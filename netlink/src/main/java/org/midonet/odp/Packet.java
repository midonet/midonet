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

import java.nio.ByteBuffer;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.packets.Ethernet;
import org.midonet.packets.MalformedPacketException;

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
    private ByteBuffer ethBuf;
    private Ethernet ethRef = null;
    public int packetLen;

    // user field used by midolman packet pipeline to track time statistics,
    // ignored in equals() and hashCode()
    public long startTimeNanos = 0;

    public Packet(Ethernet eth, FlowMatch match) {
        this(eth, match, (eth != null) ? eth.length() : 0);
    }

    public Packet(Ethernet eth, FlowMatch match, int packetLen) {
        setEthernet(eth);
        this.match = match;
        this.packetLen = packetLen;
    }

    public Packet(ByteBuffer ethBuf, FlowMatch match, int packetLen) {
        this.ethBuf = ethBuf;
        this.match = match;
        this.packetLen = packetLen;
    }

    public ByteBuffer getEthernetBuffer() {
        return ethBuf;
    }

    public Ethernet getEthernet() throws MalformedPacketException {
        if (ethRef == null) {
            ethRef = new Ethernet();
            ethBuf.position(0);
            ethBuf.limit(packetLen);
            ethRef.deserialize(ethBuf);
            ethBuf.position(0);
            ethBuf.limit(packetLen);
        }
        return ethRef;
    }

    public void setEthernet(Ethernet eth) {
        if (eth == null) {
            ethRef = null;
            packetLen = 0;
        } else {
            ethRef = eth;
            packetLen = eth.length();
            if (ethBuf == null || packetLen > ethBuf.capacity()) {
                ethBuf = ByteBuffer.allocate(packetLen);
            }
            ethBuf.clear();
            ethRef.serialize(ethBuf);
            ethBuf.position(0);
            ethBuf.limit(packetLen);
        }
    }

    public int getPacketLength() {
        return packetLen;
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

        return Objects.equals(this.ethBuf, that.ethBuf)
            && Objects.equals(this.match, that.match)
            && Objects.equals(this.userData, that.userData)
            && (this.reason == that.reason);
    }

    @Override
    public int hashCode() {
        int result = Objects.hashCode(ethBuf);
        result = 31 * result + Objects.hashCode(match);
        result = 31 * result + Objects.hashCode(userData);
        result = 31 * result + Objects.hashCode(reason);
        return result;
    }

    @Override
    public String toString() {
        String eth = "[unparsed]";
        if (ethRef != null) {
            eth = ethRef.toString();
        }
        return "Packet{" +
            "data=" + eth +
            ", match=" + match +
            ", userData=" + userData +
            ", reason=" + reason +
            '}';
    }
}
