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
import java.util.LinkedList;
import java.util.List;

import com.google.common.base.Objects;

import org.midonet.netlink.AttributeHandler;
import org.midonet.netlink.NetlinkMessage;
import org.midonet.netlink.Reader;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.IPv6Addr;

/**
 * rtnetlink Address resource representation.
 */
public class Addr implements AttributeHandler {

    public class IfaddrMsg {
        public byte family;
        public byte prefixLen;  /* The prefix length */
        public byte flags;      /* Flags             */
        public byte scope;      /* Address scope     */
        public int index;      /* Link index        */

        @Override
        public String toString() {
            return String.format("{family=%d, prefixLen=%d, " +
                            "flags=0x%x, scope=0x%x, index=%d}",
                    family, prefixLen, flags, scope, index);
        }

        @Override
        public boolean equals(Object object) {
            if (!(object instanceof IfaddrMsg)) {
                return false;
            }

            IfaddrMsg that = (IfaddrMsg) object;

            return this.family == that.family &&
                    this.prefixLen == that.prefixLen &&
                    this.flags == that.flags &&
                    this.scope == that.scope &&
                    this.index == that.index;
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(family, prefixLen, flags,
                    scope, index);
        }
    }

    public interface Attr {
        short IFA_UNSPEC    = 0;
        short IFA_ADDRESS   = 1;
        short IFA_LOCAL     = 2;
        short IFA_LABEL     = 3;
        short IFA_BROADCAST = 4;
        short IFA_ANYCAST   = 5;
        short IFA_CACHEINFO = 6;
        short IFA_MULTICAST = 7;
    }

    public interface Flags {
        byte IFA_F_SECONDARY = 0x01;
        byte IFA_F_TEMPORARY = IFA_F_SECONDARY;
        byte IFA_F_NODAD = 0x02;
        byte IFA_F_OPTIMISTIC= 0x04;
        byte IFA_F_DADFAILED = 0x08;
        byte IFA_F_HOMEADDRESS = 0x10;
        byte IFA_F_DEPRECATED = 0x20;
        byte IFA_F_TENTATIVE = 0x40;
        byte IFA_F_PERMANENT = (byte) 0x80;
        byte IFA_F_MANAGETEMPADDR = (byte) 0x100;
        byte IFA_F_NOPREFIXROUTE = (byte) 0x200;
    }

    public interface Scope {
        byte RT_SCOPE_UNIVERSE = 0;
        /* User defined values  */
        byte RT_SCOPE_SITE     = (byte) 200;
        byte RT_SCOPE_LINK     = (byte) 253;
        byte RT_SCOPE_HOST     = (byte) 254;
        byte RT_SCOPE_NOWHERE  = (byte) 255;
    }

    /**
     * Address family defined in include/linux/socket.h.
     */
    public interface Family {
        byte AF_UNSPEC      = 0;
        byte AF_INET        = 2;
        byte AF_INET6       = 10;
        byte AF_PACKET      = 17;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Addr[ifa=");
        sb.append(this.ifa);
        sb.append(", inet=");
        if (null == ipv4 || ipv4.size() == 0) {
            sb.append("[]");
        } else {
            int i = 0;
            sb.append("[");
            for (IPv4Addr a : ipv4) {
                if (i++ != 0) sb.append(" ,");
                sb.append(a.toString());
            }
            sb.append("]");
        }
        sb.append(", inet6=");
        if (null == ipv6 || ipv6.size() == 0) {
            sb.append("[]");
        } else {
            int i = 0;
            sb.append("[");
            for (IPv6Addr a : ipv6) {
                if (i++ != 0) sb.append(" ,");
                sb.append(a.toString());
            }
            sb.append("]");
        }
        sb.append("]");
        return sb.toString();
    }

    public IfaddrMsg ifa = new IfaddrMsg();
    public List<IPv4Addr> ipv4 = new LinkedList<>();
    public List<IPv6Addr> ipv6 = new LinkedList<>();

    public static final Reader<Addr> deserializer = new Reader<Addr>() {
        @Override
        public Addr deserializeFrom(ByteBuffer buf) {
            return Addr.buildFrom(buf);
        }
    };

    public static Addr buildFrom(ByteBuffer buf) {
        try {
            Addr addr = new Addr();
            addr.ifa.family = buf.get();
            addr.ifa.prefixLen = buf.get();
            addr.ifa.flags = buf.get();
            addr.ifa.scope = buf.get();
            addr.ifa.index = buf.getInt();

            NetlinkMessage.scanAttributes(buf, addr);
            return addr;
        } catch (BufferUnderflowException ex) {
            return null;
        }
    }

    public static Addr buildWithIPv4(IPv4Addr ipv4, int prefixlen,
                                     int ifindex) {
        Addr addr = new Addr();

        addr.ifa.family = Family.AF_INET;
        addr.ifa.prefixLen = (byte) prefixlen;
        addr.ifa.flags = 0;
        addr.ifa.scope = 0;
        addr.ifa.index = ifindex;
        addr.ipv4.add(ipv4);
        return addr;
    }

    @Override
    public void use(ByteBuffer buf, short id) {
        ByteOrder originalOrder = buf.order();
        try {
            if (id == Attr.IFA_ADDRESS) {
                switch (ifa.family) {
                    case Family.AF_INET:
                        if (buf.remaining() == 4) {
                            buf.order(ByteOrder.BIG_ENDIAN);
                            this.ipv4.add(IPv4Addr.fromInt(buf.getInt()));
                        }
                        break;
                    case Family.AF_INET6:
                        if (buf.remaining() == 16) {
                            byte[] ipv6 = new byte[16];
                            buf.get(ipv6);
                            this.ipv6.add(IPv6Addr.fromBytes(ipv6));
                        }
                        break;
                }
            }
        } finally {
            buf.order(originalOrder);
        }
    }

    static public ByteBuffer describeGetRequest(ByteBuffer buf) {
        buf.put(Family.AF_UNSPEC);
        buf.put((byte) 0);
        buf.put((byte) 0);
        buf.put((byte) (Scope.RT_SCOPE_LINK | Scope.RT_SCOPE_HOST));
        buf.putInt(0);
        return buf;
    }

    static public ByteBuffer fillNewOrDelRequest(ByteBuffer buf, Addr addr) {
        buf.put(addr.ifa.family);
        buf.put(addr.ifa.prefixLen);
        buf.put(addr.ifa.flags);
        buf.put(addr.ifa.scope);
        buf.putInt(addr.ifa.index);

        switch (addr.ifa.family) {
            case Family.AF_INET:
                for (IPv4Addr ip4 : addr.ipv4) {
                    NetlinkMessage.writeRawAttribute(
                            buf, Attr.IFA_LOCAL, ip4.toBytes());
                }
                for (IPv6Addr ip6 : addr.ipv6) {
                    NetlinkMessage.writeRawAttribute(
                            buf, Attr.IFA_LOCAL, ip6.toBytes());
                }
                break;
        }

        return buf;
    }

    static public ByteBuffer describeNewRequest(ByteBuffer buf, Addr addr) {
        return fillNewOrDelRequest(buf, addr);
    }

    static public ByteBuffer describeDelRequest(ByteBuffer buf, Addr addr) {
        return fillNewOrDelRequest(buf, addr);
    }

    @Override
    public boolean equals(Object object) {
        if (!(object instanceof Addr)) {
            return false;
        }

        Addr that = (Addr) object;

        return Objects.equal(this.ifa, that.ifa) &&
                Objects.equal(this.ipv4, that.ipv4) &&
                Objects.equal(this.ipv6, that.ipv6);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(ifa, ipv4, ipv6);
    }
}
