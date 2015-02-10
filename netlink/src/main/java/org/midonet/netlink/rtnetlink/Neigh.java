/*
 * Copyright 2015 Midokura SARL
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

package org.midonet.netlink.rtnetlink;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import com.google.common.base.Objects;

import org.midonet.netlink.AttributeHandler;
import org.midonet.netlink.NetlinkMessage;
import org.midonet.netlink.Reader;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.IPv6Addr;
import org.midonet.packets.MAC;

/**
 *
 */
public class Neigh implements AttributeHandler {

    public class NDMsg {
        public byte  ndm_family;
        public byte  ndm_pad1 = 0;
        public short ndm_pad2 = 0;
        public int   ndm_ifindex;  /* Interface index */
        public short ndm_state;    /* State */
        public byte  ndm_flags;    /* Flags */
        public byte  ndm_type;

        @Override
        public String toString() {
            return String.format("{ndm_family=%d, ndm_ifindex=%d, ndm_state=0x%x, ndm_flags=0x%x, ndm_type=0x%x}",
                    ndm_family, ndm_ifindex, ndm_state, ndm_flags, ndm_type);
        }

        @Override
        public boolean equals(Object object) {
            if (!(object instanceof NDMsg)) {
                return false;
            }

            NDMsg that = (NDMsg) object;

            return this.ndm_family == that.ndm_family &&
                    // this.ndm_pad1 == that.ndm_pad1 &&
                    // this.ndm_pad2 == that.ndm_pad2 &&
                    this.ndm_ifindex == that.ndm_ifindex &&
                    this.ndm_state == that.ndm_state &&
                    this.ndm_flags == that.ndm_flags &&
                    this.ndm_type == that.ndm_type;
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(ndm_family, ndm_ifindex, ndm_state,
                    ndm_flags, ndm_type);
        }
    }

    /**
     * Neighbor cache entry stats defined in include/uapi/linux/neighbour.h.
     */
    public interface State {
        byte NUD_INCOMPLETE = (byte) 0x01;
        byte NUD_REACHABLE  = (byte) 0x02;
        byte NUD_STALE      = (byte) 0x04;
        byte NUD_DELAY      = (byte) 0x08;
        byte NUD_PROBE      = (byte) 0x10;
        byte NUD_FAILED     = (byte) 0x20;
        byte NUD_NOARP      = (byte) 0x40;
        byte NUD_PERMANENT  = (byte) 0x80;
    }

    public interface Attr {
        byte NDA_UNSPEC    = 0;
        byte NDA_DST       = 1;
        byte NDA_LLADDR    = 2;
        byte NDA_CACHEINFO = 3;
    }

    /**
     * Neighbor cache entry flags defined in include/uapi/linux/neighbour.h.
     */
    public interface Flag {
        byte NTF_USE = 0x01;
        byte NTF_SELF = 0x02;
        byte NTF_MASTER = 0x04;
        byte NTF_PROXY = 0x08; /* == ATF_PUBL */
        byte NTF_EXT_LEARNED = 0x10;
        byte NTF_ROUTER = (byte) 0x80;
    }

    /**
     * Neighbor types defined in include/uapi/linux/neighbour.h.
     */
    public interface Type {
        byte NDA_UNSPEC = 0;
        byte NDA_DST = 1;
        byte NDA_LLADDR = 2;
        byte NDA_CACHEINFO = 3;
        byte NDA_PROBES = 4;
        byte NDA_VLAN = 5;
        byte NDA_PORT = 6;
        byte NDA_VNI = 7;
        byte NDA_IFINDEX = 8;
        byte NDA_MASTER = 9;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Neigh[ndm=");
        sb.append(this.ndm);
        sb.append(", dst=");
        sb.append(null == ipv4 ? (null == ipv6 ? "null" : ipv6.toString()) : ipv4.toString());
        sb.append(", lladdr=");
        sb.append(null == mac ? "null" : mac.toString());
        sb.append("]");
        return sb.toString();
    }

    public NDMsg ndm = new NDMsg();
    public IPv4Addr ipv4;
    public IPv6Addr ipv6;
    public MAC mac;

    public static final Reader<Neigh> deserializer = new Reader<Neigh>() {
        @Override
        public Neigh deserializeFrom(ByteBuffer buf) {
            return Neigh.buildFrom(buf);
        }
    };

    public static Neigh buildFrom(ByteBuffer buf) {

        if (buf == null)
            return null;

        if (buf.remaining() < 12)
            return null;

        Neigh neigh = new Neigh();
        ByteOrder originalOrder = buf.order();
        try {
            neigh.ndm.ndm_family = buf.get();
            neigh.ndm.ndm_pad1 = buf.get();
            neigh.ndm.ndm_pad2 = buf.getShort();
            neigh.ndm.ndm_ifindex = buf.getInt();
            neigh.ndm.ndm_state = buf.getShort();
            neigh.ndm.ndm_flags = buf.get();
            neigh.ndm.ndm_type = buf.get();
        } finally {
            buf.order(originalOrder);
        }

        NetlinkMessage.scanAttributes(buf, neigh);
        return neigh;
    }

    @Override
    public void use(ByteBuffer buf, short id) {
        ByteOrder originalOrder = buf.order();
        try {
            switch (id) {
                case Attr.NDA_DST:
                    switch (this.ndm.ndm_family) {
                        case Addr.Family.AF_INET:
                            if (buf.remaining() != 4) {
                                this.ipv4 = null;
                                this.ipv6 = null;
                            } else {
                                buf.order(ByteOrder.BIG_ENDIAN);
                                this.ipv4 = IPv4Addr.fromInt(buf.getInt());
                                this.ipv6 = null;
                            }
                            break;
                        case Addr.Family.AF_INET6:
                            if (buf.remaining() != 16) {
                                this.ipv4 = null;
                                this.ipv6 = null;
                            } else {
                                this.ipv4 = null;
                                byte[] ipv6 = new byte[16];
                                buf.get(ipv6);
                                this.ipv6 = IPv6Addr.fromBytes(ipv6);
                            }
                            break;
                        default:
                            this.ipv4 = null;
                            this.ipv6 = null;
                    }
                    break;

                case Attr.NDA_LLADDR:
                    if (buf.remaining() != 6) {
                        this.mac = null;
                    } else {
                        byte[] rhs = new byte[6];
                        buf.get(rhs);
                        this.mac = MAC.fromAddress(rhs);
                    }
                    break;
            }
        } finally {
            buf.order(originalOrder);
        }
    }

    static public ByteBuffer describeListRequest(ByteBuffer buf) {
        return describeGetRequest(buf, 0, (byte) Addr.Family.AF_INET);
    }

    static public ByteBuffer describeGetRequest(ByteBuffer buf, int ifIndex) {
        return describeGetRequest(buf, ifIndex, (byte) Addr.Family.AF_INET);
    }

    static public ByteBuffer describeGetRequest(ByteBuffer buf,
                                                int ifIndex,
                                                byte family) {
        ByteOrder originalOrder = buf.order();
        try {
            buf.put(family);
            buf.put((byte) 0);
            buf.putShort((short)0);
            buf.putInt(ifIndex);
            buf.putShort((short)0);
            buf.put((byte)0);
            buf.put((byte)0);
        } finally {
            buf.order(originalOrder);
        }
        // buf.flip();
        return buf;
    }

    static public ByteBuffer describeNewRequest(ByteBuffer buf, Neigh neigh) {
        ByteOrder originalOrder = buf.order();
        try {
            buf.put(neigh.ndm.ndm_family);
            buf.put(neigh.ndm.ndm_pad1);
            buf.putShort(neigh.ndm.ndm_pad2);
            buf.putInt(neigh.ndm.ndm_ifindex);
            buf.putShort(neigh.ndm.ndm_state);
            buf.put(neigh.ndm.ndm_flags);
            buf.put(neigh.ndm.ndm_type);
        } finally {
            buf.order(originalOrder);
        }
        // buf.flip();
        return buf;
    }

    @Override
    public boolean equals(Object object) {
        if (!(object instanceof Neigh)) {
            return false;
        }

        Neigh that = (Neigh) object;

        return Objects.equal(this.ndm, that.ndm) &&
                Objects.equal(this.ipv4, that.ipv4) &&
                Objects.equal(this.ipv6, that.ipv6) &&
                Objects.equal(this.mac, that.mac);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(ndm, ipv4, ipv6, mac);
    }
}
