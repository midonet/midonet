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
import org.midonet.packets.MAC;

/**
 *
 */
public class Link implements AttributeHandler, Cloneable {

    public class IfinfoMsg implements Cloneable {
        public byte  ifi_family; /* AF_UNSPEC */
        public byte  ifi_pad;
        public short ifi_type;   /* Device type */
        public int   ifi_index;  /* Interface index */
        public int   ifi_flags;  /* Device flags  */
        public int   ifi_change; /* change mask */

        @Override
        public Object clone() throws CloneNotSupportedException {
            return super.clone();
        }

        @Override
        public String toString() {
            return String.format("{ifi_family=%d, ifi_type=%d, ifi_index=%d, ifi_flags=0x%x, ifi_change=0x%x}",
                    ifi_family, ifi_type, ifi_index, ifi_flags, ifi_change);
        }
    }

    public interface NetDeviceFlags {
        int IFF_UP = 1;
    }

    public interface Attr {
        short IFLA_UNSPEC    = 0;
        short IFLA_ADDRESS   = 1;
        short IFLA_BROADCAST = 2;
        short IFLA_IFNAME    = 3;
        short IFLA_MTU       = 4;
        short IFLA_LINK      = 5;
        short IFLA_QDISC     = 6;
        short IFLA_STATS     = 7;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Link[ifi=");
        sb.append(this.ifi);
        sb.append(", addr=");
        sb.append(null == mac ? "null" : mac.toString());
        sb.append(", name=");
        sb.append(ifname);
        sb.append(", mtu=");
        sb.append(mtu);
        sb.append("]");
        return sb.toString();
    }

    public IfinfoMsg ifi;
    public MAC mac;
    public String ifname;
    public int mtu;

    public Link() {
        this.ifi = new IfinfoMsg();
    }

    public Link(IfinfoMsg ifi) {
        this.ifi = ifi;
    }

    @Override
    public Object clone() throws CloneNotSupportedException {
        Link l;
        l = this.ifi != null ? new Link((IfinfoMsg)this.ifi.clone()) : new Link();
        l.mac = this.mac != null ? MAC.fromAddress(mac.getAddress()) : null;
        l.ifname = this.ifname;
        l.mtu = this.mtu;
        return l;
    }

    public static final Reader<Link> deserializer = new Reader<Link>() {
        @Override
        public Link deserializeFrom(ByteBuffer buf) {
            return Link.buildFrom(buf);
        }
    };

    public static Link buildFrom(ByteBuffer buf) {

        if (buf == null)
            return null;

        if (buf.remaining() < 16)
            return null;

        Link link = new Link();
        ByteOrder originalOrder = buf.order();
        try {
            link.ifi.ifi_family = buf.get();
            link.ifi.ifi_pad = buf.get();
            link.ifi.ifi_type = buf.getShort();
            link.ifi.ifi_index = buf.getInt();
            link.ifi.ifi_flags = buf.getInt();
            link.ifi.ifi_change = buf.getInt();
        } finally {
            buf.order(originalOrder);
        }

        NetlinkMessage.scanAttributes(buf, link);
        return link;
    }

    @Override
    public void use(ByteBuffer buf, short id) {
        ByteOrder originalOrder = buf.order();
        try {
            switch (id) {
                case Attr.IFLA_ADDRESS:
                    if (buf.remaining() != 6) {
                        this.mac = null;
                    } else {
                        byte[] rhs = new byte[6];
                        buf.get(rhs);
                        this.mac = MAC.fromAddress(rhs);
                    }
                    break;
                case Attr.IFLA_IFNAME:
                    byte[] s = new byte[buf.remaining()-1];
                    buf.get(s);
                    this.ifname = new String(s);
                    break;
                case Attr.IFLA_MTU:
                    if (buf.remaining() != 4) {
                        this.mtu = 0;
                    } else {
                        this.mtu = buf.getInt();
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
            buf.putShort((short) 0);
            buf.putInt(0);
            buf.putInt(0);
            buf.putInt(0);
        } finally {
            buf.order(originalOrder);
        }
        buf.flip();
        return buf;
    }

    static public ByteBuffer describeSetRequest(ByteBuffer buf, Link link) {
        IfinfoMsg ifi = link.ifi;
        ByteOrder originalOrder = buf.order();
        try {
            buf.put(ifi.ifi_family);
            buf.put((byte)0);
            buf.putShort(ifi.ifi_type);
            buf.putInt(ifi.ifi_index);
            buf.putInt(ifi.ifi_flags);
            buf.putInt(ifi.ifi_change);
        } finally {
            buf.order(originalOrder);
        }
        buf.flip();
        return buf;
    }

    static public ByteBuffer describeSetAddrRequest(ByteBuffer buf, Link link, MAC mac) {
        IfinfoMsg ifi = link.ifi;
        ByteOrder originalOrder = buf.order();
        try {
            buf.put(ifi.ifi_family);
            buf.put((byte)0);
            buf.putShort(ifi.ifi_type);
            buf.putInt(ifi.ifi_index);
            buf.putInt(ifi.ifi_flags);
            buf.putInt(ifi.ifi_change);
        } finally {
            buf.order(originalOrder);
        }

        NetlinkMessage.writeRawAttribute(buf, Attr.IFLA_ADDRESS, mac.getAddress());

        buf.flip();
        return buf;

    }

}
