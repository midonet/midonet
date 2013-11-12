/*
* Copyright 2013 Midokura
*/
package org.midonet.odp.flows;

import java.nio.ByteOrder;

import org.midonet.netlink.NetlinkMessage;
import org.midonet.netlink.messages.BaseBuilder;
import org.midonet.odp.OpenVSwitch;
import org.midonet.packets.IPv4Addr;

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
            ID = attr(OpenVSwitch.FlowKey.TunnelAttr.Id);

        // OVS_TUNNEL_KEY_ATTR_IPV4_SRC
        public static final FlowKeyTunnelAttr<Integer>
            IPV4_SRC = attr(OpenVSwitch.FlowKey.TunnelAttr.IPv4Src);

        // OVS_TUNNEL_KEY_ATTR_IPV4_DST
        public static final FlowKeyTunnelAttr<Integer>
            IPV4_DST = attr(OpenVSwitch.FlowKey.TunnelAttr.IPv4Dst);

        // OVS_TUNNEL_KEY_ATTR_TOS
        public static final FlowKeyTunnelAttr<Byte>
            TOS = attr(OpenVSwitch.FlowKey.TunnelAttr.TOS);

        // OVS_TUNNEL_KEY_ATTR_TTL
        public static final FlowKeyTunnelAttr<Byte>
            TTL = attr(OpenVSwitch.FlowKey.TunnelAttr.TTL);

        // OVS_TUNNEL_KEY_ATTR_DONT_FRAGMENT
        public static final FlowKeyTunnelAttr<Boolean>
            DONT_FRAGMENT = attr(OpenVSwitch.FlowKey.TunnelAttr.DontFrag);

        // OVS_TUNNEL_KEY_ATTR_CSUM
        public static final FlowKeyTunnelAttr<Boolean>
            CSUM = attr(OpenVSwitch.FlowKey.TunnelAttr.CSum);

        public FlowKeyTunnelAttr(int id) {
            super(id);
        }

        static <T> FlowKeyTunnelAttr<T> attr(int id) {
            return new FlowKeyTunnelAttr<T>(id);
        }
    }

    @Override
    public void serialize(BaseBuilder<?,?> builder) {

        if (tun_id != null) {
            builder.addAttr(FlowKeyTunnelAttr.ID, tun_id, ByteOrder.BIG_ENDIAN);
        }

        if (ipv4_src != null) {
            builder.addAttr(
                FlowKeyTunnelAttr.IPV4_SRC, ipv4_src, ByteOrder.BIG_ENDIAN);
        }

        /*
         * For flow-based tunneling, ipv4_dst has to be set, otherwise
         * the NL message will result in EINVAL
         */
        if (ipv4_dst != null) {
            builder.addAttr(
                FlowKeyTunnelAttr.IPV4_DST, ipv4_dst, ByteOrder.BIG_ENDIAN);
        }
        if (ipv4_tos != null) {
            builder.addAttrNoPad(FlowKeyTunnelAttr.TOS, ipv4_tos);
        }

        /*
         * For flow-based tunneling, ipv4_ttl of zero would also result
         * in OVS kmod replying with error EINVAL
         */
        if (ipv4_ttl != null) {
            builder.addAttrNoPad(FlowKeyTunnelAttr.TTL, ipv4_ttl);
        }
        if (tun_flags != null) {
            if ((tun_flags.shortValue() & OVS_TNL_F_DONT_FRAGMENT) ==
                    OVS_TNL_F_DONT_FRAGMENT)
                builder.addAttr(FlowKeyTunnelAttr.DONT_FRAGMENT);
            if ((tun_flags.shortValue() & OVS_TNL_F_CSUM) == OVS_TNL_F_CSUM)
                builder.addAttr(FlowKeyTunnelAttr.CSUM);
        }
    }

    @Override
    public boolean deserialize(NetlinkMessage message) {
        try {

            tun_id = message.getAttrValueLong(
                FlowKeyTunnelAttr.ID, ByteOrder.BIG_ENDIAN);

            ipv4_src = message.getAttrValueInt(
                FlowKeyTunnelAttr.IPV4_SRC, ByteOrder.BIG_ENDIAN);

            ipv4_dst = message.getAttrValueInt(
                FlowKeyTunnelAttr.IPV4_DST, ByteOrder.BIG_ENDIAN);

            ipv4_tos = message.getAttrValueByte(
                FlowKeyTunnelAttr.TOS);

            ipv4_ttl = message.getAttrValueByte(
                FlowKeyTunnelAttr.TTL);

            if (message.getAttrValueNone(
                    FlowKeyTunnelAttr.DONT_FRAGMENT) != null ) {
                tun_flags = (short)(tun_flags | OVS_TNL_F_DONT_FRAGMENT);
            }

            if (message.getAttrValueNone(FlowKeyTunnelAttr.CSUM) != null) {
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
                      + IPv4Addr.intToString(ipv4_src) + ", ";
        if (ipv4_dst != null)
            retString = retString + "ipv4_dst="
                      + IPv4Addr.intToString(ipv4_dst) + ", ";
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
