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

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import com.google.common.base.Objects;

import org.midonet.netlink.AttributeHandler;
import org.midonet.netlink.NetlinkMessage;
import org.midonet.netlink.Reader;
import org.midonet.packets.IPAddr;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.IPv6Addr;
import org.midonet.packets.MAC;

/**
 * rtnetlink Neighbour resource representation.
 */
public class Neigh implements AttributeHandler {

    public class NDMsg {
        public byte family;
        public int ifindex;  /* Interface index */
        public short state;  /* State */
        public byte flags;   /* Flags */
        public byte type;

        @Override
        public String toString() {
            return String.format("{family=%d, ifindex=%d, state=0x%x, flags=0x%x, type=0x%x}",
                    family, ifindex, state, flags, type);
        }

        @Override
        public boolean equals(Object object) {
            if (!(object instanceof NDMsg)) {
                return false;
            }

            NDMsg that = (NDMsg) object;

            return this.family == that.family &&
                    this.ifindex == that.ifindex &&
                    this.state == that.state &&
                    this.flags == that.flags &&
                    this.type == that.type;
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(family, ifindex, state,
                    flags, type);
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
        sb.append(null == ip ? "null" : ip.toString());
        sb.append(", lladdr=");
        sb.append(null == mac ? "null" : mac.toString());
        sb.append("]");
        return sb.toString();
    }

    public NDMsg ndm = new NDMsg();
    public IPAddr ip;
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
        try {
            neigh.ndm.family = buf.get();
            buf.get();  // pad1
            buf.getShort();  // pad2
            neigh.ndm.ifindex = buf.getInt();
            neigh.ndm.state = buf.getShort();
            neigh.ndm.flags = buf.get();
            neigh.ndm.type = buf.get();
        } catch (BufferUnderflowException ex) {
            return null;
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
                    switch (this.ndm.family) {
                        case Addr.Family.AF_INET:
                            if (buf.remaining() != 4) {
                                this.ip = null;
                            } else {
                                buf.order(ByteOrder.BIG_ENDIAN);
                                this.ip = IPv4Addr.fromInt(buf.getInt());
                            }
                            break;
                        case Addr.Family.AF_INET6:
                            if (buf.remaining() != 16) {
                                this.ip = null;
                            } else {
                                byte[] ipv6 = new byte[16];
                                buf.get(ipv6);
                                this.ip = IPv6Addr.fromBytes(ipv6);
                            }
                            break;
                        default:
                            this.ip = null;
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
        buf.put(family);
        buf.put((byte) 0);
        buf.putShort((short) 0);
        buf.putInt(ifIndex);
        buf.putShort((short) 0);
        buf.put((byte) 0);
        buf.put((byte) 0);

        return buf;
    }

    public ByteBuffer describeNewRequest(ByteBuffer buf) {
        buf.put(ndm.family);
        buf.put((byte) 0); /* pad0 */
        buf.putShort((short) 0); /* pad1 */
        buf.putInt(ndm.ifindex);
        buf.putShort(ndm.state);
        buf.put(ndm.flags);
        buf.put(ndm.type);

        if (mac != null) {
            NetlinkMessage.writeRawAttribute(
                buf, Attr.NDA_LLADDR, mac.getAddress());
        }

        if (ip != null) {
            NetlinkMessage.writeRawAttribute(buf, Attr.NDA_DST, ip.toBytes());
        }

        return buf;
    }

    @Override
    public boolean equals(Object object) {
        if (!(object instanceof Neigh)) {
            return false;
        }

        Neigh that = (Neigh) object;

        return Objects.equal(this.ndm, that.ndm) &&
                Objects.equal(this.ip, that.ip) &&
                Objects.equal(this.mac, that.mac);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(ndm, ip, mac);
    }
}
