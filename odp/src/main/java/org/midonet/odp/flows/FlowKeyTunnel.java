/*
* Copyright 2013 Midokura
*/
package org.midonet.odp.flows;

import java.nio.ByteOrder;

import org.midonet.netlink.NetlinkMessage;
import org.midonet.netlink.messages.BaseBuilder;
import org.midonet.packets.Net;

public class FlowKeyTunnel implements FlowKey<FlowKeyTunnel> {

    // maintaining the names of field to be the same as ovs_key_ipv4_tunnel
    // see datapath/flow.h from OVS source
    /* be64 */  Long tun_id;
    /* be32 */  Integer ipv4_src;
    /* be32 */  Integer ipv4_dst;
    /* u16 */   Short tun_flags;
    /* u8 */    Byte ipv4_tos;
    /* u8 */    Byte ipv4_ttl;
    // same size as the tun_flags
    public static final short OVS_TNL_F_DONT_FRAGMENT = (short) (1 << 0);
    public static final short OVS_TNL_F_CSUM = (short) (1 << 1);
    public static final short OVS_TNL_F_KEY = (short) (1 << 2);

    public static final short ATTR_ID = (short) ((1 << 15) | 16);

    @Override
    public NetlinkMessage.AttrKey<FlowKeyTunnel> getKey() {
        return FlowKeyAttr.TUNNEL;
    }

    @Override
    public FlowKeyTunnel getValue() {
        return this;
    }

    public static class FlowKeyTunnelAttr<T> extends
        NetlinkMessage.AttrKey<T> {

        // OVS_TUNNEL_KEY_ATTR_ID
        public static final FlowKeyTunnelAttr<Long>
            OVS_TUNNEL_KEY_ATTR_ID = attr(0);

        // OVS_TUNNEL_KEY_ATTR_IPV4_SRC
        public static final FlowKeyTunnelAttr<Integer>
            OVS_TUNNEL_KEY_ATTR_IPV4_SRC = attr(1);

        // OVS_TUNNEL_KEY_ATTR_IPV4_DST
        public static final FlowKeyTunnelAttr<Integer>
            OVS_TUNNEL_KEY_ATTR_IPV4_DST = attr(2);

        // OVS_TUNNEL_KEY_ATTR_TOS
        public static final FlowKeyTunnelAttr<Byte>
            OVS_TUNNEL_KEY_ATTR_TOS = attr(3);

        // OVS_TUNNEL_KEY_ATTR_TTL
        public static final FlowKeyTunnelAttr<Byte>
            OVS_TUNNEL_KEY_ATTR_TTL = attr(4);

        // OVS_TUNNEL_KEY_ATTR_DONT_FRAGMENT
        public static final FlowKeyTunnelAttr<Boolean>
            OVS_TUNNEL_KEY_ATTR_DONT_FRAGMENT = attr(5);

        // OVS_TUNNEL_KEY_ATTR_CSUM
        public static final FlowKeyTunnelAttr<Boolean>
            OVS_TUNNEL_KEY_ATTR_CSUM = attr(6);

        public FlowKeyTunnelAttr(int id) {
            super(id);
        }

        static <T> FlowKeyTunnelAttr<T> attr(int id) {
            return new FlowKeyTunnelAttr<T>(id);
        }
    }

    @Override
    public void serialize(BaseBuilder builder) {
        if (tun_id != null) {
            builder.addAttr(FlowKeyTunnelAttr.OVS_TUNNEL_KEY_ATTR_ID,
                            tun_id, ByteOrder.BIG_ENDIAN);
        }
        if (ipv4_src != null) {
            builder.addAttr(FlowKeyTunnelAttr.OVS_TUNNEL_KEY_ATTR_IPV4_SRC,
                            ipv4_src, ByteOrder.BIG_ENDIAN);
        }
        /*
         * For flow-based tunneling, ipv4_dst has to be set, otherwise
         * the NL message will result in EINVAL
         */
        if (ipv4_dst != null) {
            builder.addAttr(FlowKeyTunnelAttr.OVS_TUNNEL_KEY_ATTR_IPV4_DST,
                            ipv4_dst, ByteOrder.BIG_ENDIAN);
        }
        if (ipv4_tos != null) {
            builder.addAttrNoPad(FlowKeyTunnelAttr.OVS_TUNNEL_KEY_ATTR_TOS,
                            ipv4_tos);
        }

        /*
         * For flow-based tunneling, ipv4_ttl of zero would also result
         * in OVS kmod replying with error EINVAL
         */
        if (ipv4_ttl != null) {
            builder.addAttrNoPad(FlowKeyTunnelAttr.OVS_TUNNEL_KEY_ATTR_TTL,
                            ipv4_ttl);
        }
        if (tun_flags != null) {
            if ((tun_flags.shortValue() & OVS_TNL_F_DONT_FRAGMENT) ==
                    OVS_TNL_F_DONT_FRAGMENT)
                builder.addAttr(FlowKeyTunnelAttr.
                        OVS_TUNNEL_KEY_ATTR_DONT_FRAGMENT);
            if ((tun_flags.shortValue() & OVS_TNL_F_CSUM) == OVS_TNL_F_CSUM)
                builder.addAttr(FlowKeyTunnelAttr.OVS_TUNNEL_KEY_ATTR_CSUM);
        }
    }

    @Override
    public boolean deserialize(NetlinkMessage message) {
        try {
            tun_id = message.getAttrValueLong(
                    FlowKeyTunnelAttr.OVS_TUNNEL_KEY_ATTR_ID,
                    ByteOrder.BIG_ENDIAN);
            ipv4_src = message.getAttrValueInt(
                    FlowKeyTunnelAttr.OVS_TUNNEL_KEY_ATTR_IPV4_SRC,
                    ByteOrder.BIG_ENDIAN);
            ipv4_dst = message.getAttrValueInt(
                    FlowKeyTunnelAttr.OVS_TUNNEL_KEY_ATTR_IPV4_DST,
                    ByteOrder.BIG_ENDIAN);
            ipv4_tos = message.getAttrValueByte(
                    FlowKeyTunnelAttr.OVS_TUNNEL_KEY_ATTR_TOS);
            ipv4_ttl = message.getAttrValueByte(
                    FlowKeyTunnelAttr.OVS_TUNNEL_KEY_ATTR_TTL);
            if (message.getAttrValueNone(
                    FlowKeyTunnelAttr.OVS_TUNNEL_KEY_ATTR_DONT_FRAGMENT)
                    != null) {
                tun_flags = (short)(tun_flags | OVS_TNL_F_DONT_FRAGMENT);
            }
            if (message.getAttrValueNone(
                FlowKeyTunnelAttr.OVS_TUNNEL_KEY_ATTR_CSUM) != null) {
                tun_flags = (short)(tun_flags | OVS_TNL_F_CSUM);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public long getTunnelID() {
        return tun_id;
    }

    public FlowKeyTunnel setTunnelID(long tunnelID) {
        this.tun_id = new Long(tunnelID);
        return this;
    }

    public int getIpv4SrcAddr() {
        return ipv4_src;
    }

    public FlowKeyTunnel setIpv4SrcAddr(int ipv4SrcAddr) {
        this.ipv4_src = new Integer(ipv4SrcAddr);
        return this;
    }

    public int getIpv4DstAddr() {
        return ipv4_dst;
    }

    public FlowKeyTunnel setIpv4DstAddr(int ipv4DstAddr) {
        this.ipv4_dst = new Integer(ipv4DstAddr);
        return this;
    }

    public short getTunnelFlags() {
        return tun_flags;
    }

    public FlowKeyTunnel setTunnelFlags(short tunnelFlags) {
        this.tun_flags = new Short(tunnelFlags);
        return this;
    }

    public byte getTos() {
        return ipv4_tos;
    }

    public FlowKeyTunnel setTos(byte tos) {
        this.ipv4_tos = new Byte(tos);
        return this;
    }

    public byte getTtl() {
        return ipv4_ttl;
    }

    public FlowKeyTunnel setTtl(byte ttl) {
        this.ipv4_ttl= new Byte(ttl);
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        FlowKeyTunnel that = (FlowKeyTunnel) o;

        if (tun_id != that.tun_id) return false;
        if (ipv4_src != that.ipv4_src) return false;
        if (ipv4_dst != that.ipv4_dst) return false;
        if (tun_flags != that.tun_flags) return false;
        if (ipv4_tos != that.ipv4_tos) return false;
        if (ipv4_ttl != that.ipv4_ttl) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = 0;

        if (tun_id != null)
            result = (int) (tun_id.longValue() ^ (tun_id.longValue() >>> 32));
        if (ipv4_src != null)
            result = 31 * result + ipv4_src.intValue();
        if (ipv4_dst != null)
            result = 31 * result + ipv4_dst.intValue();
        if (tun_flags != null) 
            result = 31 * result + tun_flags.intValue();
        if (ipv4_tos != null)
            result = 31 * result + ipv4_tos.intValue();
        if (ipv4_ttl != null)
            result = 31 * result + ipv4_ttl.intValue();
        return result;
    }

    @Override
    public String toString() {
        String retString = "FlowKeyTunnel{";
        if (tun_id != null)
            retString = retString + "tun_id=" + tun_id.longValue() + ", ";
        if (ipv4_src != null)
            retString = retString + "ipv4_src="
                      + Net.convertIntAddressToString(ipv4_src) + ", ";
        if (ipv4_dst != null)
            retString = retString + "ipv4_dst="
                      + Net.convertIntAddressToString(ipv4_dst) + ", ";
        if (tun_flags != null)
            retString = retString + "tun_flag=" + tun_flags.shortValue() + ", ";
        if (ipv4_tos != null)
            retString = retString + "ipv4_tos=" + ipv4_tos.byteValue() + ", ";
        if (ipv4_ttl != null)
            retString = retString + "ipv4_ttl=" + ipv4_ttl.byteValue();
        retString = retString + '}';
        return retString;
    }
}
