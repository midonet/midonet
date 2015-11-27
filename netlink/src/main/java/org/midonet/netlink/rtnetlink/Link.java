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
import org.midonet.netlink.NetlinkSerializable;
import org.midonet.netlink.Reader;
import org.midonet.packets.MAC;

/**
 * rtnetlink Link resource representation.
 */
public class Link implements AttributeHandler, Cloneable, NetlinkSerializable {

    public static class IfinfoMsg implements Cloneable {
        public byte family; /* AF_UNSPEC */
        public short type;   /* Device type */
        public int index;  /* Interface index */
        public int flags;  /* Device flags  */
        public int change = 0xffffffff;  /* change mask */

        @Override
        public Object clone() throws CloneNotSupportedException {
            return super.clone();
        }

        @Override
        public String toString() {
            return String.format(
                    "{family=%d, type=%d, index=%d, flags=0x%x, change=0x%x}",
                    family, type, index, flags, change);
        }

        /**
         * Check the equality of this and another object. ifi_change is ignored
         * in this check.
         *
         * @param object the object to be compared with.
         * @return true if object equals to this, otherwise false.
         */
        @Override
        public boolean equals(Object object) {
            if (!(object instanceof IfinfoMsg)) {
                return false;
            }

            IfinfoMsg that = (IfinfoMsg) object;

            return this.family == that.family &&
                    this.type == that.type &&
                    this.index == that.index &&
                    this.flags == that.flags;
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(family, type, index, flags, change);
        }
    }

    /**
     * Hardware types defined in include/uapi/linux/if_arp.h.
     */
    public interface Type {
        /* ARP protocol HARDWARE identifiers. */
        short ARPHRD_NETROM = 0; /* from KA9Q: NET/ROM pseudo */
        short ARPHRD_ETHER  = 1; /* Ethernet 10Mbps  */
        short ARPHRD_EETHER = 2; /* Experimental Ethernet */
        short ARPHRD_AX25 = 3; /* AX.25 Level 2 */
        short ARPHRD_PRONET = 4; /* PROnet token ring  */
        short ARPHRD_CHAOS = 5; /* Chaosnet   */
        short ARPHRD_IEEE802 = 6; /* IEEE 802.2 Ethernet/TR/TB */
        short ARPHRD_ARCNET = 7; /* ARCnet   */
        short ARPHRD_APPLETLK = 8; /* APPLEtalk   */
        short ARPHRD_DLCI = 15; /* Frame Relay DLCI  */
        short ARPHRD_ATM = 19; /* ATM     */
        short ARPHRD_METRICOM = 23; /* Metricom STRIP (new IANA id) */
        short ARPHRD_IEEE1394 = 24; /* IEEE 1394IPv4 - RFC 2734*/
        short ARPHRD_EUI64 = 27; /* EUI-64                       */
        short ARPHRD_INFINIBAND = 32; /* InfiniBand   */
        /* Dummy types for non ARP hardware */
        short ARPHRD_SLIP = 256;
        short ARPHRD_CSLIP = 257;
        short ARPHRD_SLIP6 = 258;
        short ARPHRD_CSLIP6 = 259;
        short ARPHRD_RSRVD = 260; /* Notional KISS type   */
        short ARPHRD_ADAPT = 264;
        short ARPHRD_ROSE = 270;
        short ARPHRD_X25 = 271; /* CCITT X.25   */
        short ARPHRD_HWX25 = 272; /* Boards with X.25 in firmware */
        short ARPHRD_CAN = 280; /* Controller Area Network      */
        short ARPHRD_PPP = 512;
        short ARPHRD_CISCO = 513; /* Cisco HDLC    */
        short ARPHRD_HDLC = ARPHRD_CISCO;
        short ARPHRD_LAPB = 516; /* LAPB    */
        short ARPHRD_DDCMP = 517; /* Digital's DDCMP protocol     */
        short ARPHRD_RAWHDLC = 518; /* Raw HDLC   */
        short ARPHRD_TUNNEL = 768; /* IPIP tunnel   */
        short ARPHRD_TUNNEL6 = 769; /* IP6IP6 tunnel         */
        short ARPHRD_FRAD = 770;            /* Frame Relay Access Device    */
        short ARPHRD_SKIP = 771; /* SKIP vif   */
        short ARPHRD_LOOPBACK = 772; /* Loopback device  */
        short ARPHRD_LOCALTLK = 773; /* Localtalk device  */
        short ARPHRD_FDDI = 774; /* Fiber Distributed Data Interface */
        short ARPHRD_BIF = 775;            /* AP1000 BIF                   */
        short ARPHRD_SIT = 776; /* sit0 device - IPv6-in-IPv4 */
        short ARPHRD_IPDDP = 777; /* IP over DDP tunneller */
        short ARPHRD_IPGRE = 778; /* GRE over IP   */
        short ARPHRD_PIMREG = 779; /* PIMSM register interface */
        short ARPHRD_HIPPI = 780; /* High Performance Parallel Interface */
        short ARPHRD_ASH = 781; /* Nexus 64Mbps Ash  */
        short ARPHRD_ECONET = 782; /* Acorn Econet   */
        short ARPHRD_IRDA  = 783; /* Linux-IrDA   */
        /* ARP works differently on different FC media .. so  */
        short ARPHRD_FCPP = 784; /* Point to point fibrechannel */
        short ARPHRD_FCAL = 785; /* Fibrechannel arbitrated loop */
        short ARPHRD_FCPL = 786; /* Fibrechannel public loop */
        short ARPHRD_FCFABRIC = 787; /* Fibrechannel fabric  */
        /* 787->799 reserved for fibrechannel media types */
        short ARPHRD_IEEE802_TR = 800; /* Magic type ident for TR */
        short ARPHRD_IEEE80211 = 801; /* IEEE 802.11   */
        short ARPHRD_IEEE80211_PRISM = 802;/* IEEE 802.11 + Prism2 header  */
        short ARPHRD_IEEE80211_RADIOTAP = 803; // IEEE 802.11 + radiotap header
        short ARPHRD_IEEE802154 = 804;
        short ARPHRD_IEEE802154_MONITOR = 805; // IEEE 802.15.4 network monitor

        short ARPHRD_PHONET = 820; /* PhoNet media type  */
        short ARPHRD_PHONET_PIPE = 821; /* PhoNet pipe header  */
        short ARPHRD_CAIF = 822; /* CAIF media type  */
        short ARPHRD_IP6GRE = 823; /* GRE over IPv6  */
        short ARPHRD_NETLINK = 824; /* Netlink header  */
        short ARPHRD_6LOWPAN = 825; /* IPv6 over LoWPAN */

        short ARPHRD_VOID = 0xFF; /* Void type, nothing is known */
        short ARPHRD_NONE = 0xFE; /* zero header length */
    }

    /**
     * Interface flags defined in include/uapi/linux/if.h.
     */
    public interface Flag {
        int IFF_UP = 1;  // 1 << 0
        int IFF_BROADCAST = 1<<1;
        int IFF_DEBUG = 1<<2;
        int IFF_LOOPBACK = 1<<3;
        int IFF_POINTOPOINT = 1<<4;
        int IFF_NOTRAILERS = 1<<5;
        int IFF_RUNNING = 1<<6;
        int IFF_NOARP = 1<<7;
        int IFF_PROMISC = 1<<8;
        int IFF_ALLMULTI = 1<<9;
        int IFF_MASTER = 1<<10;
        int IFF_SLAVE = 1<<11;
        int IFF_MULTICAST = 1<<12;
        int IFF_PORTSEL = 1<<13;
        int IFF_AUTOMEDIA = 1<<14;
        int IFF_DYNAMIC = 1<<15;
        int IFF_LOWER_UP = 1<<16;
        int IFF_DORMANT = 1<<17;
        int IFF_ECHO = 1<<18;
    }

    public interface OperStatus {
        byte IF_OPER_UNKNOWN = 0;
        byte IF_OPER_NOTPRESENT = 1;
        byte IF_OPER_DOWN = 2;
        byte IF_OPER_LOWERLAYERDOWN = 3;
        byte IF_OPER_TESTING = 4;
        byte IF_OPER_DORMANT = 5;
        byte IF_OPER_UP = 6;
    }

    public interface Attr {
        byte IFLA_UNSPEC = 0;
        byte IFLA_ADDRESS = 1;
        byte IFLA_BROADCAST = 2;
        byte IFLA_IFNAME = 3;
        byte IFLA_MTU = 4;
        byte IFLA_LINK = 5;
        byte IFLA_QDISC = 6;
        byte IFLA_STATS = 7;
        byte IFLA_COST = 8;
        byte IFLA_PRIORITY = 9;
        byte IFLA_MASTER = 10;
        byte IFLA_WIRELESS = 11; /* Wireless Extension event - see wireless.h */
        byte IFLA_PROTINFO = 12;  /* Protocol specific information for a link */
        byte IFLA_TXQLEN = 13;
        byte IFLA_MAP = 14;
        byte IFLA_WEIGHT = 15;
        byte IFLA_OPERSTATE = 16;
        byte IFLA_LINKMODE = 17;
        byte IFLA_LINKINFO = 18;
        byte IFLA_NET_NS_PID = 19;
        byte IFLA_IFALIAS = 20;
        byte IFLA_NUM_VF = 21; /* Number of VFs if device is SR-IOV PF */
        byte IFLA_VFINFO_LIST = 22;
        byte IFLA_STATS64 = 23;
        byte IFLA_VF_PORTS = 24;
        byte IFLA_PORT_SELF = 25;
        byte IFLA_AF_SPEC = 26;
        byte IFLA_GROUP = 27;  /* Group the device belongs to */
        byte IFLA_NET_NS_FD = 28;
        byte IFLA_EXT_MASK = 29;  /* Extended info mask, VFs, etc */
        byte IFLA_PROMISCUITY = 30; /* Promiscuity count: > 0 means acts PROMISC */
        byte IFLA_NUM_TX_QUEUES = 31;
        byte IFLA_NUM_RX_QUEUES = 32;
        byte IFLA_CARRIER = 33;
        byte IFLA_PHYS_PORT_ID = 34;
        byte IFLA_CARRIER_CHANGES = 35;
        byte IFLA_PHYS_SWITCH_ID = 36;
    }

    public interface NestedAttr {
         interface LinkInfo {
            byte IFLA_INFO_UNSPEC = 0;
            byte IFLA_INFO_KIND = 1;
            byte IFLA_INFO_DATA = 2;
            byte IFLA_INFO_XSTATS = 3;
        }
    }

    public interface ExtMask {
        int RTEXT_FILTER_VF     = 1 << 0;
        int RTEXT_FILTER_BRVLAN = 1 << 1;
    }

    public interface NestedAttrKey {
        String IFLA_INFO_UNSPEC = "IFLA_INFO_UNSPEC";
        String IFLA_INFO_KIND = "IFLA_INFO_KIND";
        String IFLA_INFO_DATA = "IFLA_INFO_DATA";
        String IFLA_INFO_XSTATS = "IFLA_INFO_XSTATS";
        String IFLA_INFO_SLAVE_KIND = "IFLA_INFO_SLAVE_KIND";
        String IFLA_INFO_SLAVE_DATA = "IFLA_INFO_SLAVE_DATA";
    }

    public interface NestedAttrValue {
         interface LinkInfo {
            String KIND_TUN = "tun";
            String KIND_VETH = "veth";
            String KIND_BRIDGE = "bridge";
            String KIND_GRE = "gre";
            String KIND_VLAN = "vlan";
            String KIND_MACVLAN = "macvlan";
        }
    }

    public static class LinkInfo implements NetlinkSerializable {
        public String kind;
        public ByteBuffer data;

        @Override
        public int serializeInto(ByteBuffer buffer) {
            int nbytes = 0;
            if (kind != null) {
                nbytes += NetlinkMessage.writeStringAttr(
                    buffer, NestedAttr.LinkInfo.IFLA_INFO_KIND, kind);
            }
            if (data != null) {
                nbytes += NetlinkMessage.writeRawAttribute(
                    buffer, NestedAttr.LinkInfo.IFLA_INFO_DATA, data);
            }
            return nbytes;
        }
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

    public final IfinfoMsg ifi;
    public MAC mac;
    private String ifname;
    public int mtu;
    public int link;
    public byte operstate = OperStatus.IF_OPER_UNKNOWN;
    public final LinkInfo info = new LinkInfo();

    public Link() {
        this.ifi = new IfinfoMsg();
    }

    public Link(IfinfoMsg ifi) {
        this.ifi = ifi;
    }

    public String getName() {
        return ifname;
    }

    public void setName(String name) {
        if (name != null && name.length() > 16) {
            throw new IllegalArgumentException(
                "Interface name must be smaller or equal to 16 characters");
        }
        ifname = name;
    }

    @Override
    public Object clone() throws CloneNotSupportedException {
        Link l;
        l = this.ifi != null ?
                new Link((IfinfoMsg) this.ifi.clone()) : new Link();
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
        try {
            Link link = new Link();
            link.ifi.family = buf.get();
            buf.get();  // pad
            link.ifi.type = buf.getShort();
            link.ifi.index = buf.getInt();
            link.ifi.flags = buf.getInt();
            link.ifi.change = buf.getInt();
            NetlinkMessage.scanAttributes(buf, link);
            return link;
        } catch (BufferUnderflowException ex) {
            return null;
        }
    }

    @Override
    public void use(ByteBuffer buf, short id) {
        ByteOrder originalOrder = buf.order();
        try {
            if (!NetlinkMessage.isNested(id)) {
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
                        byte[] s = new byte[buf.remaining() - 1];
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
                    case Attr.IFLA_LINK:
                        if (buf.remaining() != 4) {
                            this.link = this.ifi.index;
                        } else {
                            this.link = buf.getInt();
                        }
                        break;
                    case Attr.IFLA_LINKINFO:
                        if (buf.remaining() != 0) {
                            NetlinkMessage.scanNestedAttribute(buf, this);
                        }
                        break;
                    default:
                        break;
                }
            } else {
                switch (NetlinkMessage.unnest(id)) {
                    case NestedAttr.LinkInfo.IFLA_INFO_KIND:
                        byte[] s = new byte[buf.remaining() - 1];
                        buf.get(s);
                        info.kind = new String(s);
                        break;
                    case NestedAttr.LinkInfo.IFLA_INFO_DATA:
                        byte[] data =  new byte[buf.remaining() - 1];
                        buf.get(data);
                        info.data = ByteBuffer.wrap(data);
                        break;
                    default:
                        break;
                }
            }
        } finally {
            buf.order(originalOrder);
        }
    }

    static public ByteBuffer describeListRequest(ByteBuffer buf) {
        return describeGetRequest(buf, 0);
    }

    static public ByteBuffer describeGetRequest(ByteBuffer buf, int index) {
        buf.put((byte) 0);
        buf.put((byte) 0);
        buf.putShort((short) 0);
        buf.putInt(index);
        buf.putInt(0);
        buf.putInt(0xffffffff);

        return buf;
    }

    @Override
    public int serializeInto(ByteBuffer buf) {
        int start = buf.position();
        buf.put(ifi.family);
        buf.put((byte) 0);
        buf.putShort(ifi.type);
        buf.putInt(ifi.index);
        buf.putInt(ifi.flags);
        buf.putInt(ifi.change);

        if (ifname != null) {
            NetlinkMessage.writeStringAttr(buf, Attr.IFLA_IFNAME, ifname);
        }

        if (mac != null) {
            NetlinkMessage.writeRawAttribute(
                    buf, Attr.IFLA_ADDRESS, mac.getAddress());
        }

        if (mtu > 0) {
            NetlinkMessage.writeIntAttr(buf, Attr.IFLA_MTU, mtu);
        }

        if (operstate != OperStatus.IF_OPER_UNKNOWN) {
            NetlinkMessage.writeByteAttr(buf, Attr.IFLA_OPERSTATE, operstate);
        }

        NetlinkMessage.writeAttrNested(buf, Attr.IFLA_LINKINFO, info);

        return buf.position() - start;
    }

    static public ByteBuffer describeSetRequest(ByteBuffer buf, Link link) {
        link.serializeInto(buf);
        return buf;
    }

    static public ByteBuffer describeSetAddrRequest(ByteBuffer buf, Link link,
                                                    MAC mac) {
        IfinfoMsg ifi = link.ifi;
        buf.put(ifi.family);
        buf.put((byte) 0);
        buf.putShort(ifi.type);
        buf.putInt(ifi.index);
        buf.putInt(ifi.flags);
        buf.putInt(ifi.change);

        NetlinkMessage.writeRawAttribute(
                buf, Attr.IFLA_ADDRESS, mac.getAddress());

        return buf;
    }

    static public ByteBuffer describeDelRequest(ByteBuffer buf, Link link) {
        IfinfoMsg ifi = link.ifi;
        buf.put(ifi.family);
        buf.put((byte) 0);
        buf.putShort(ifi.type);
        buf.putInt(ifi.index);
        buf.putInt(ifi.flags);
        buf.putInt(ifi.change);

        NetlinkMessage.writeAttrNested(buf, Attr.IFLA_LINKINFO, link.info);

        return buf;
    }

    @Override
    public boolean equals(Object object) {
        if (!(object instanceof Link)) {
            return false;
        }

        Link that = (Link) object;

        return Objects.equal(this.ifi, that.ifi) &&
                Objects.equal(this.ifname, that.ifname) &&
                Objects.equal(this.mac, that.mac) &&
                this.mtu == that.mtu;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(ifi, ifname, mac, mtu);
    }
}
