/*
 * Copyright (c) 2013 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.odp.flows;

import java.nio.ByteOrder;

import org.midonet.netlink.NetlinkMessage;
import org.midonet.netlink.NetlinkMessage.AttrKey;
import org.midonet.netlink.messages.Builder;
import org.midonet.odp.OpenVSwitch;
import org.midonet.packets.IPv4Addr;

public class FlowKeyTunnel implements CachedFlowKey {

    // OVS_TUNNEL_KEY_ATTR_ID
    public static final AttrKey<Long> ID =
        AttrKey.attr(OpenVSwitch.FlowKey.TunnelAttr.Id);

    // OVS_TUNNEL_KEY_ATTR_IPV4_SRC
    public static final AttrKey<Integer> IPV4_SRC =
        AttrKey.attr(OpenVSwitch.FlowKey.TunnelAttr.IPv4Src);

    // OVS_TUNNEL_KEY_ATTR_IPV4_DST
    public static final AttrKey<Integer> IPV4_DST =
        AttrKey.attr(OpenVSwitch.FlowKey.TunnelAttr.IPv4Dst);

    // OVS_TUNNEL_KEY_ATTR_TOS
    public static final AttrKey<Byte> TOS =
        AttrKey.attr(OpenVSwitch.FlowKey.TunnelAttr.TOS);

    // OVS_TUNNEL_KEY_ATTR_TTL
    public static final AttrKey<Byte> TTL =
        AttrKey.attr(OpenVSwitch.FlowKey.TunnelAttr.TTL);

    // OVS_TUNNEL_KEY_ATTR_DONT_FRAGMENT
    public static final AttrKey<Boolean> DONT_FRAGMENT =
        AttrKey.attr(OpenVSwitch.FlowKey.TunnelAttr.DontFrag);

    // OVS_TUNNEL_KEY_ATTR_CSUM
    public static final AttrKey<Boolean> CSUM =
        AttrKey.attr(OpenVSwitch.FlowKey.TunnelAttr.CSum);

    // maintaining the names of field to be the same as ovs_key_ipv4_tunnel
    // see datapath/flow.h from OVS source
    /* be64 */  private long tun_id;
    /* be32 */  private int ipv4_src;
    /* be32 */  private int ipv4_dst;
    /* u16 */   private short tun_flags;
    /* u8 */    private byte ipv4_tos;
    /* u8 */    private byte ipv4_ttl;

    private int hashCode = 0;

    // same size as the tun_flags
    public static final short OVS_TNL_F_DONT_FRAGMENT = 1 << 0;
    public static final short OVS_TNL_F_CSUM = 1 << 1;
    public static final short OVS_TNL_F_KEY = 1 << 2;

    private byte usedFields;

    private static final byte TUN_ID_MASK   = 1 << 0;
    private static final byte IPV4_SRC_MASK = 1 << 1;
    private static final byte IPV4_DST_MASK = 1 << 2;
    private static final byte IPV4_TOS_MASK = 1 << 3;
    private static final byte IPV4_TTL_MASK = 1 << 4;

    // This is used for deserialization purposes only.
    FlowKeyTunnel() { }

    // OVS kernel module requires the TTL field to be set. Here we set it to
    // the maximum possible value. We leave TOS and flags not set. We may
    // set the tunnel key flag, but it's not a required field. DONT FRAGMENT
    // and CHECKSUM are for the outer header. We leave those alone for now
    // as well. CHECKSUM defaults to zero in OVS kernel module. GRE header
    // checksum is not checked in gre_rcv, and it is also not required for
    // GRE packets.

    FlowKeyTunnel(long tunnelId, int ipv4SrcAddr, int ipv4DstAddr) {
        this(tunnelId, ipv4SrcAddr, ipv4DstAddr, (byte)-1);
    }

    FlowKeyTunnel(long tunnelId, int ipv4SrcAddr, int ipv4DstAddr, byte ttl) {
        if (ttl == 0)
            throw new IllegalArgumentException("The TTL of a FlowKeyTunnel must not be zero");

        tun_id = tunnelId;
        ipv4_src = ipv4SrcAddr;
        ipv4_dst = ipv4DstAddr;
        ipv4_ttl = ttl;
        usedFields = TUN_ID_MASK | IPV4_SRC_MASK | IPV4_DST_MASK | IPV4_TTL_MASK;
    }

    @Override
    public NetlinkMessage.AttrKey<FlowKeyTunnel> getKey() {
        return FlowKeyAttr.TUNNEL;
    }

    @Override
    public FlowKeyTunnel getValue() {
        return this;
    }

    @Override
    public void serialize(Builder builder) {
        if ((usedFields & TUN_ID_MASK) != 0)
            builder.addAttr(ID, tun_id, ByteOrder.BIG_ENDIAN);

        if ((usedFields & IPV4_SRC_MASK) != 0)
            builder.addAttr(IPV4_SRC, ipv4_src, ByteOrder.BIG_ENDIAN);

        /*
         * For flow-based tunneling, ipv4_dst has to be set, otherwise
         * the NL message will result in EINVAL
         */
        if ((usedFields & IPV4_DST_MASK) != 0)
            builder.addAttr(IPV4_DST, ipv4_dst, ByteOrder.BIG_ENDIAN);

        if ((usedFields & IPV4_TOS_MASK) != 0)
            builder.addAttrNoPad(TOS, ipv4_tos);

        /*
         * For flow-based tunneling, ipv4_ttl of zero would also result
         * in OVS kmod replying with error EINVAL
         */
        if ((usedFields & IPV4_TTL_MASK) != 0)
            builder.addAttrNoPad(TTL, ipv4_ttl);

        if ((tun_flags & OVS_TNL_F_DONT_FRAGMENT) == OVS_TNL_F_DONT_FRAGMENT)
            builder.addAttr(DONT_FRAGMENT);
        if ((tun_flags & OVS_TNL_F_CSUM) == OVS_TNL_F_CSUM)
            builder.addAttr(CSUM);
    }

    @Override
    public boolean deserialize(NetlinkMessage message) {
        try {
            Long tun_id = message.getAttrValueLong(ID, ByteOrder.BIG_ENDIAN);
            if (tun_id != null) {
                this.tun_id = tun_id;
                usedFields |= TUN_ID_MASK;
            }

            Integer ipv4_src =
                message.getAttrValueInt(IPV4_SRC, ByteOrder.BIG_ENDIAN);
            if (ipv4_src != null) {
                this.ipv4_src = ipv4_src;
                usedFields |= IPV4_SRC_MASK;
            }

            Integer ipv4_dst =
                    message.getAttrValueInt(IPV4_DST, ByteOrder.BIG_ENDIAN);
            if (ipv4_dst != null) {
                this.ipv4_dst = ipv4_dst;
                usedFields |= IPV4_DST_MASK;
            }

            Byte ipv4_tos = message.getAttrValueByte(TOS);
            if (ipv4_tos != null) {
                this.ipv4_tos = ipv4_tos;
                usedFields |= IPV4_TOS_MASK;
            }

            Byte ipv4_ttl = message.getAttrValueByte(TTL);
            if (ipv4_ttl != null) {
                this.ipv4_ttl = ipv4_ttl;
                usedFields |= IPV4_TTL_MASK;
            }

            if (message.getAttrValueNone(DONT_FRAGMENT) != null ) {
                tun_flags = (short)(tun_flags | OVS_TNL_F_DONT_FRAGMENT);
            }

            if (message.getAttrValueNone(CSUM) != null) {
                tun_flags = (short)(tun_flags | OVS_TNL_F_CSUM);
            }

            hashCode = 0;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public long getTunnelID() {
        return tun_id;
    }

    public int getIpv4SrcAddr() {
        return ipv4_src;
    }

    public int getIpv4DstAddr() {
        return ipv4_dst;
    }

    public short getTunnelFlags() {
        return tun_flags;
    }

    public byte getTos() {
        return ipv4_tos;
    }

    public byte getTtl() {
        return ipv4_ttl;
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
        if (hashCode == 0) {
            int result = (int)tun_id;
            result = 31 * result + ipv4_src;
            result = 31 * result + ipv4_dst;
            result = 31 * result + tun_flags;
            result = 31 * result + ipv4_tos;
            result = 31 * result + ipv4_ttl;
            hashCode = result;
        }
        return hashCode;
    }

    @Override
    public String toString() {
        return "FlowKeyTunnel{"
            + "tun_id=" + tun_id + ", "
            + "ipv4_src=" + IPv4Addr.intToString(ipv4_src) + ", "
            + "ipv4_dst=" + IPv4Addr.intToString(ipv4_dst) + ", "
            + "tun_flag=" + tun_flags + ", "
            + "ipv4_tos=" + ipv4_tos + ", "
            + "ipv4_ttl=" + ipv4_ttl
            + '}';
    }
}
