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
    }

    public interface Attr {
        short RTA_UNSPEC    = 0;
        short RTA_DST       = 1;
        short RTA_SRC       = 2;
        short RTA_IIF       = 3;
        short RTA_OIF       = 4;
        short RTA_GATEWAY   = 5;
        short RTA_PRIORITY  = 6;
        short RTA_PREFSRC   = 7;
        short RTA_METRICS   = 8;
        short RTA_MULTIPATH = 9;
        short RTA_PROTOINFO = 10; /* no longer used */
        short RTA_FLOW      = 11;
        short RTA_CACHEINFO = 12;
        short RTA_SESSION   = 13; /* no longer used */
        short RTA_MP_ALGO   = 14; /* no longer used */
        short RTA_TABLE     = 15;
        short RTA_MARK      = 16;
    }

    public interface Table {
        byte RT_TABLE_COMPAT  = (byte)252;
        byte RT_TABLE_DEFAULT = (byte)253;
        byte RT_TABLE_MAIN    = (byte)254;
        byte RT_TABLE_LOCAL   = (byte)255;
    }

    public interface Proto {
        byte RTPROT_UNSPEC   = 0;
        byte RTPROT_REDIRECT = 1; /* Route installed by ICMP redirects; not used by current IPv4 */
        byte RTPROT_KERNEL   = 2; /* Route installed by kernel            */
        byte RTPROT_BOOT     = 3; /* Route installed during boot          */
        byte RTPROT_STATIC   = 4; /* Route installed by administrator     */
    }

    public interface Type {
        short RTN_UNSPEC      = 0;
        short RTN_UNICAST     = 1;
        short RTN_LOCAL       = 2;        /* Accept locally               */
        short RTN_BROADCAST   = 3;        /* Accept locally as broadcast, send as broadcast */
        short RTN_ANYCAST     = 4;        /* Accept locally as broadcast, but send as unicast */
        short RTN_MULTICAST   = 5;        /* Multicast route              */
        short RTN_BLACKHOLE   = 6;        /* Drop                         */
        short RTN_UNREACHABLE = 7;        /* Destination is unreachable   */
        short RTN_PROHIBIT    = 8;        /* Administratively prohibited  */
        short RTN_THROW       = 9;        /* Not in this table            */
        short RTN_NAT         = 10;       /* Translate this address       */
        short RTN_XRESOLVE    = 11;       /* Use external resolver        */
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

    static public ByteBuffer describeRequest(ByteBuffer buf) {

        ByteOrder originalOrder = buf.order();
        try {
            buf.put((byte)0);
            buf.put((byte)0);
            buf.put((byte)0);
            buf.put((byte)0);
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

    static public ByteBuffer describeGetRequest(ByteBuffer buf, IPv4Addr dst) {

        ByteOrder originalOrder = buf.order();
        try {
            buf.put((byte)Addr.Family.AF_INET);
            buf.put((byte)32);
            buf.put((byte)0);
            buf.put((byte)0);
            buf.put((byte)0);
            buf.put((byte)0);
            buf.put((byte)0);
            buf.put((byte)0);
            buf.putInt(0);

            NetlinkMessage.writeRawAttribute(buf, Attr.RTA_DST, dst.toBytes());
        } finally {
            buf.order(originalOrder);
        }
        buf.flip();
        return buf;
    }



    static public ByteBuffer describeNewRouteRequest(ByteBuffer buf, IPv4Addr dst, int prefix, IPv4Addr gw, Link link) {
        ByteOrder originalOrder = buf.order();
        try {
            buf.put((byte)Addr.Family.AF_INET);
            buf.put((byte)prefix);
            buf.put((byte)0);
            buf.put((byte)0);
            buf.put((byte)Table.RT_TABLE_MAIN);
            buf.put((byte)Proto.RTPROT_BOOT);
            buf.put((byte)0);
            buf.put((byte)Type.RTN_UNICAST);
            buf.putInt(0);

            if (dst != null)
                NetlinkMessage.writeRawAttribute(buf, Attr.RTA_DST, dst.toBytes());
            NetlinkMessage.writeRawAttribute(buf, Attr.RTA_GATEWAY, gw.toBytes());
            NetlinkMessage.writeIntAttr(buf, Attr.RTA_OIF, link.ifi.ifi_index);
        } finally {
            buf.order(originalOrder);
        }
        buf.flip();
        return buf;
    }

}
