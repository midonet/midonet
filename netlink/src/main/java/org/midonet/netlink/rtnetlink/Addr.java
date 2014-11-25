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
import java.util.LinkedList;
import java.util.List;

import org.midonet.netlink.AttributeHandler;
import org.midonet.netlink.NetlinkMessage;
import org.midonet.netlink.Reader;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.IPv6Addr;

/**
 *
 */
public class Addr implements AttributeHandler {

    public class IfaddrMsg {
        public byte ifa_family;
        public byte ifa_prefixlen;  /* The prefix length */
        public byte ifa_flags;      /* Flags             */
        public byte ifa_scope;      /* Address scope     */
        public int  ifa_index;      /* Link index        */

        @Override
        public String toString() {
            return String.format("{ifa_family=%d, ifa_prefixlen=%d, ifa_flags=0x%x, ifa_scope=0x%x, ifa_index=%d}",
                    ifa_family, ifa_prefixlen, ifa_flags, ifa_scope, ifa_index);
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

    public interface Family {
        byte AF_INET        = 2;
        byte AF_INET6       = 10;
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

        if (buf == null)
            return null;

        if (buf.remaining() < 8)
            return null;

        Addr addr = new Addr();
        ByteOrder originalOrder = buf.order();
        try {
            addr.ifa.ifa_family = buf.get();
            addr.ifa.ifa_prefixlen = buf.get();
            addr.ifa.ifa_flags = buf.get();
            addr.ifa.ifa_scope = buf.get();
            addr.ifa.ifa_index = buf.getInt();
        } finally {
            buf.order(originalOrder);
        }

        NetlinkMessage.scanAttributes(buf, addr);
        return addr;
    }

    public static Addr buildWithIPv4(IPv4Addr ipv4, int prefixlen, int ifindex) {
        Addr addr = new Addr();

        addr.ifa.ifa_family = Family.AF_INET;
        addr.ifa.ifa_prefixlen = (byte)prefixlen;
        addr.ifa.ifa_flags = 0;
        addr.ifa.ifa_scope = 0;
        addr.ifa.ifa_index = ifindex;
        addr.ipv4.add(ipv4);
        return addr;
    }

    @Override
    public void use(ByteBuffer buf, short id) {
        ByteOrder originalOrder = buf.order();
        try {
            switch (id) {
                case Attr.IFA_ADDRESS:
                    switch (ifa.ifa_family) {
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
                    break;
            }
        } finally {
            buf.order(originalOrder);
        }
    }

    static public ByteBuffer describeGetRequest(ByteBuffer buf) {

        ByteOrder originalOrder = buf.order();
        try {
            buf.put((byte)0);
            buf.put((byte)0);
            buf.put((byte)0);
            buf.put((byte)0);
            buf.putInt(0);
        } finally {
            buf.order(originalOrder);
        }
        buf.flip();
        return buf;
    }

    static public ByteBuffer describeNewRequest(ByteBuffer buf, Addr addr) {
        ByteOrder originalOrder = buf.order();
        try {
            buf.put(addr.ifa.ifa_family);
            buf.put(addr.ifa.ifa_prefixlen);
            buf.put(addr.ifa.ifa_flags);
            buf.put(addr.ifa.ifa_scope);
            buf.putInt(addr.ifa.ifa_index);

            switch (addr.ifa.ifa_family) {
                case Family.AF_INET:
                    for (IPv4Addr ip : addr.ipv4) {
                        NetlinkMessage.writeRawAttribute(buf, Attr.IFA_LOCAL, ip.toBytes());
                    }
                    break;
            }
        } finally {
            buf.order(originalOrder);
        }
        buf.flip();
        return buf;
    }
}
