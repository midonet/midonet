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

/**
 *
 */
public class Route implements AttributeHandler {

    public class Rtmsg {
        public byte rtm_family;
        public byte rtm_dst_len;
        public byte rtm_src_len;
        public byte rtm_tos;

        public byte rtm_table;      /* Routing table id */
        public byte rtm_protocol;   /* Routing protocol; see below  */
        public byte rtm_scope;
        public byte rtm_type;

        public int  rtm_flags;

        @Override
        public String toString() {
            return String.format("{rtm_family=%d, rtm_dst_len=%d, rtm_src_len=%d, rtm_tos=%d, rtm_table=0x%x, rtm_protocol=%d, rtm_scope=0x%x, rtm_type=%d, rtm_flags=0x%x}",
                    rtm_family, rtm_dst_len, rtm_src_len, rtm_tos, rtm_table, rtm_protocol, rtm_scope, rtm_type, rtm_flags);
        }

        @Override
        public boolean equals(Object object) {
            if (!(object instanceof Rtmsg)) {
                return false;
            }

            Rtmsg that = (Rtmsg) object;

            return this.rtm_family == that.rtm_family &&
                    this.rtm_dst_len == that.rtm_dst_len &&
                    this.rtm_src_len == that.rtm_src_len  &&
                    this.rtm_tos == that.rtm_tos &&
                    this.rtm_table == that.rtm_table &&
                    this.rtm_protocol == that.rtm_protocol &&
                    this.rtm_scope == that.rtm_scope &&
                    this.rtm_type == that.rtm_type  &&
                    this.rtm_flags == that.rtm_flags;
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(rtm_family, rtm_dst_len, rtm_src_len,
                    rtm_tos, rtm_table, rtm_protocol, rtm_scope, rtm_type,
                    rtm_flags);
        }
    }

    public interface Attr {
        byte RTA_UNSPEC    = 0;
        byte RTA_DST       = 1;
        byte RTA_SRC       = 2;
        byte RTA_IIF       = 3;
        byte RTA_OIF       = 4;
        byte RTA_GATEWAY   = 5;
        byte RTA_PRIORITY  = 6;
        byte RTA_PREFSRC   = 7;
        byte RTA_METRICS   = 8;
        byte RTA_MULTIPATH = 9;
        byte RTA_PROTOINFO = 10; /* no longer used */
        byte RTA_FLOW      = 11;
        byte RTA_CACHEINFO = 12;
        byte RTA_SESSION   = 13; /* no longer used */
        byte RTA_MP_ALGO   = 14; /* no longer used */
        byte RTA_TABLE     = 15;
        byte RTA_MARK      = 16;
    }

    /**
     * Address families defined in include/linux/socket.h.
     */
    public interface Family {
        byte AF_INET  = (byte) 2;
        byte AF_INET6 = (byte) 10;
    }

    public interface Table {
        byte RT_TABLE_COMPAT  = (byte) 252;
        byte RT_TABLE_DEFAULT = (byte) 253;
        byte RT_TABLE_MAIN    = (byte) 254;
        byte RT_TABLE_LOCAL   = (byte) 255;
    }

    public interface Proto {
        byte RTPROT_UNSPEC   = 0;
        byte RTPROT_REDIRECT = 1; /* Route installed by ICMP redirects; not used by current IPv4 */
        byte RTPROT_KERNEL   = 2; /* Route installed by kernel            */
        byte RTPROT_BOOT     = 3; /* Route installed during boot          */
        byte RTPROT_STATIC   = 4; /* Route installed by administrator     */
    }

    public interface Type {
        byte RTN_UNSPEC      = 0;
        byte RTN_UNICAST     = 1;
        byte RTN_LOCAL       = 2;        /* Accept locally               */
        byte RTN_BROADCAST   = 3;        /* Accept locally as broadcast, send as broadcast */
        byte RTN_ANYCAST     = 4;        /* Accept locally as broadcast, but send as unicast */
        byte RTN_MULTICAST   = 5;        /* Multicast route              */
        byte RTN_BLACKHOLE   = 6;        /* Drop                         */
        byte RTN_UNREACHABLE = 7;        /* Destination is unreachable   */
        byte RTN_PROHIBIT    = 8;        /* Administratively prohibited  */
        byte RTN_THROW       = 9;        /* Not in this table            */
        byte RTN_NAT         = 10;       /* Translate this address       */
        byte RTN_XRESOLVE    = 11;       /* Use external resolver        */
    }

    public interface Scope {
        byte RT_SCOPE_UNIVERSE = (byte) 0;
        /* User defined values  */
        byte RT_SCOPE_SITE = (byte) 200;
        byte RT_SCOPE_LINK = (byte) 253;
        byte RT_SCOPE_HOST = (byte) 254;
        byte RT_SCOPE_NOWHERE = (byte) 255;
    }

    public interface Flags {
        int RTM_F_NOTIFY = 0x100;  /* Notify user of route change */
        int RTM_F_CLONED = 0x200;  /* This route is cloned  */
        int RTM_F_EQUALIZE = 0x400; /* Multipath equalizer: NI */
        int RTM_F_PREFIX = 0x800; /* Prefix addresses  */
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Route[rtm=");
        sb.append(this.rtm);
        sb.append(", dst=");
        sb.append(null == dst ? "null" : dst.toString());
        sb.append(", src=");
        sb.append(null == src ? "null" : src.toString());
        sb.append(", gw=");
        sb.append(null == gw ? "null" : gw.toString());
        sb.append("]");
        return sb.toString();
    }

    public Rtmsg rtm = new Rtmsg();
    public IPv4Addr dst;
    public IPv4Addr src;
    public IPv4Addr gw;

    public static final Reader<Route> deserializer = new Reader<Route>() {
        @Override
        public Route deserializeFrom(ByteBuffer buf) {
            return Route.buildFrom(buf);
        }
    };

    public static Route buildFrom(ByteBuffer buf) {

        if (buf == null)
            return null;

        if (buf.remaining() < 12)
            return null;

        Route route = new Route();
        ByteOrder originalOrder = buf.order();
        try {
            route.rtm.rtm_family = buf.get();
            route.rtm.rtm_dst_len = buf.get();
            route.rtm.rtm_src_len = buf.get();
            route.rtm.rtm_tos = buf.get();
            route.rtm.rtm_table = buf.get();
            route.rtm.rtm_protocol = buf.get();
            route.rtm.rtm_scope = buf.get();
            route.rtm.rtm_type = buf.get();
            route.rtm.rtm_flags = buf.getInt();
        } finally {
            buf.order(originalOrder);
        }

        NetlinkMessage.scanAttributes(buf, route);
        return route;
    }

    @Override
    public void use(ByteBuffer buf, short id) {
        ByteOrder originalOrder = buf.order();
        try {
            switch (id) {
                case Attr.RTA_DST:
                    switch (rtm.rtm_family) {
                        case Addr.Family.AF_INET:
                            if (buf.remaining() == 4) {
                                buf.order(ByteOrder.BIG_ENDIAN);
                                this.dst = IPv4Addr.fromInt(buf.getInt());
                            }
                            break;
                    }
                    break;
                case Attr.RTA_SRC:
                    switch (rtm.rtm_family) {
                        case Addr.Family.AF_INET:
                            if (buf.remaining() == 4) {
                                buf.order(ByteOrder.BIG_ENDIAN);
                                this.src = IPv4Addr.fromInt(buf.getInt());
                            }
                            break;
                    }
                    break;
                case Attr.RTA_GATEWAY:
                    switch (rtm.rtm_family) {
                        case Addr.Family.AF_INET:
                            if (buf.remaining() == 4) {
                                buf.order(ByteOrder.BIG_ENDIAN);
                                this.gw = IPv4Addr.fromInt(buf.getInt());
                            }
                            break;
                    }
                    break;
            }
        } finally {
            buf.order(originalOrder);
        }
    }

    static public ByteBuffer describeListRequest(ByteBuffer buf) {
        ByteOrder originalOrder = buf.order();
        try {
            buf.put((byte) 0);
            buf.put((byte) 0);
            buf.put((byte) 0);
            buf.put((byte) 0);
            buf.put((byte) 0);
            buf.put((byte) 0);
            buf.put((byte) 0);
            buf.put((byte) 0);
            buf.putInt(0);
        } finally {
            buf.order(originalOrder);
        }
        // buf.flip();
        return buf;
    }

    static public ByteBuffer describeGetRequest(ByteBuffer buf, IPv4Addr dst) {
        ByteOrder originalOrder = buf.order();
        try {
            buf.put((byte) Addr.Family.AF_INET);
            buf.put((byte) 32);
            buf.put((byte) 0);
            buf.put((byte) 0);
            buf.put((byte) 0);
            buf.put((byte) 0);
            buf.put((byte) 0);
            buf.put((byte) 0);
            buf.putInt(0);

            NetlinkMessage.writeRawAttribute(buf, Attr.RTA_DST, dst.toBytes());
        } finally {
            buf.order(originalOrder);
        }
        // buf.flip();
        return buf;
    }



    static public ByteBuffer describeNewRequest(ByteBuffer buf, IPv4Addr dst,
                                                int prefix, IPv4Addr gw,
                                                Link link) {
        return describeNewRequest(buf, dst, null, prefix, gw, link);
    }

    static public ByteBuffer describeNewRequest(ByteBuffer buf, IPv4Addr dst,
                                                IPv4Addr src, int prefix,
                                                IPv4Addr gw, Link link) {
        ByteOrder originalOrder = buf.order();
        try {
            buf.put((byte) Addr.Family.AF_INET);
            buf.put((byte) prefix);
            buf.put((byte) 0);
            buf.put((byte) 0);
            buf.put((byte) Table.RT_TABLE_MAIN);
            buf.put((byte) Proto.RTPROT_BOOT);
            buf.put((byte) 0);
            buf.put((byte) Type.RTN_UNICAST);
            buf.putInt(0);

            if (dst != null) {
                NetlinkMessage.writeRawAttribute(buf, Attr.RTA_DST, dst.toBytes());
            }
            if (src != null) {
                NetlinkMessage.writeRawAttribute(buf, Attr.RTA_SRC, src.toBytes());
            }
            NetlinkMessage.writeRawAttribute(buf, Attr.RTA_GATEWAY, gw.toBytes());
            NetlinkMessage.writeIntAttr(buf, Attr.RTA_OIF, link.ifi.ifi_index);
        } finally {
            buf.order(originalOrder);
        }
        // buf.flip();
        return buf;
    }

    static public ByteBuffer describeSetRequest(ByteBuffer buf, Route route,
                                                Link link) {
        ByteOrder originalOrder = buf.order();
        try {
            buf.put((byte) Addr.Family.AF_INET);
            buf.put((byte) ((route.rtm.rtm_dst_len != 0) ?
                    route.rtm.rtm_dst_len : 0));
            buf.put((byte) 0);
            buf.put((byte) 0);
            buf.put((byte) Table.RT_TABLE_MAIN);
            buf.put((byte) Proto.RTPROT_BOOT);
            buf.put((byte) 0);
            buf.put((byte) Type.RTN_UNICAST);
            buf.putInt(0);

            if (route.dst != null) {
                NetlinkMessage.writeRawAttribute(
                        buf, Attr.RTA_DST, route.dst.toBytes());
            }
            if (route.src != null) {
                NetlinkMessage.writeRawAttribute(
                        buf, Attr.RTA_SRC, route.src.toBytes());
            }
            NetlinkMessage.writeRawAttribute(
                    buf, Attr.RTA_GATEWAY, route.gw.toBytes());
            if (link != null) {
                NetlinkMessage.writeIntAttr(
                        buf, Attr.RTA_OIF, link.ifi.ifi_index);
            }
        } finally {
            buf.order(originalOrder);
        }
        // buf.flip();
        return buf;
    }


    @Override
    public boolean equals(Object object) {
        if (!(object instanceof Route)) {
            return false;
        }

        Route that = (Route) object;

        return Objects.equal(this.rtm, that.rtm) &&
                Objects.equal(this.src, that.src) &&
                Objects.equal(this.dst, that.dst) &&
                Objects.equal(this.gw, that.gw);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(rtm, src, dst, gw);
    }
}
