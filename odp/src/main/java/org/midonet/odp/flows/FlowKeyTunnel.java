/*
 * Copyright (c) 2013 Midokura SARL, All Rights Reserved.
 */
package org.midonet.odp.flows;

import java.nio.ByteBuffer;

import org.midonet.netlink.BytesUtil;
import org.midonet.netlink.NetlinkMessage;
import org.midonet.odp.OpenVSwitch;
import org.midonet.odp.OpenVSwitch.FlowKey.TunnelAttr;
import org.midonet.packets.IPv4Addr;

public class FlowKeyTunnel implements CachedFlowKey {

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

    public short attrId() {
        return OpenVSwitch.FlowKey.Attr.Tunnel_N;
    }

    public int serializeInto(ByteBuffer buffer) {
        int nBytes = 0;

        if ((usedFields & TUN_ID_MASK) != 0)
            nBytes += NetlinkMessage.writeLongAttr(buffer, TunnelAttr.Id,
                                                   BytesUtil.instance
                                                            .reverseBE(tun_id));

        if ((usedFields & IPV4_SRC_MASK) != 0)
            nBytes += NetlinkMessage.writeIntAttr(buffer, TunnelAttr.IPv4Src,
                                                  BytesUtil.instance
                                                           .reverseBE(ipv4_src));

        /*
         * For flow-based tunneling, ipv4_dst has to be set, otherwise
         * the NL message will result in EINVAL
         */
        if ((usedFields & IPV4_DST_MASK) != 0)
            nBytes += NetlinkMessage.writeIntAttr(buffer, TunnelAttr.IPv4Dst,
                                                  BytesUtil.instance
                                                           .reverseBE(ipv4_dst));

        if ((usedFields & IPV4_TOS_MASK) != 0)
            nBytes += NetlinkMessage.writeByteAttrNoPad(buffer, TunnelAttr.TOS,
                                                        ipv4_tos);

        /*
         * For flow-based tunneling, ipv4_ttl of zero would also result
         * in OVS kmod replying with error EINVAL
         */
        if ((usedFields & IPV4_TTL_MASK) != 0)
            nBytes += NetlinkMessage.writeByteAttrNoPad(buffer, TunnelAttr.TTL,
                                                        ipv4_ttl);

        //empty attribute
        if ((tun_flags & OVS_TNL_F_DONT_FRAGMENT) == OVS_TNL_F_DONT_FRAGMENT) {
            NetlinkMessage.setAttrHeader(buffer, TunnelAttr.DontFrag, 0);
            nBytes += 4;
        }

        //empty attribute
        if ((tun_flags & OVS_TNL_F_CSUM) == OVS_TNL_F_CSUM) {
            NetlinkMessage.setAttrHeader(buffer, TunnelAttr.CSum, 0);
            nBytes += 4;
        }

        return nBytes;
    }

    @Override
    public boolean deserialize(ByteBuffer buf) {
        try {
            Long tun_id = NetlinkMessage.getAttrValueLong(buf, TunnelAttr.Id);
            if (tun_id != null) {
                this.tun_id = BytesUtil.instance.reverseBE(tun_id);
                usedFields |= TUN_ID_MASK;
            }

            Integer ipv4_src =
                NetlinkMessage.getAttrValueInt(buf, TunnelAttr.IPv4Src);
            if (ipv4_src != null) {
                this.ipv4_src = BytesUtil.instance.reverseBE(ipv4_src);
                usedFields |= IPV4_SRC_MASK;
            }

            Integer ipv4_dst =
                NetlinkMessage.getAttrValueInt(buf, TunnelAttr.IPv4Dst);
            if (ipv4_dst != null) {
                this.ipv4_dst = BytesUtil.instance.reverseBE(ipv4_dst);
                usedFields |= IPV4_DST_MASK;
            }

            Byte ipv4_tos = NetlinkMessage.getAttrValueByte(buf, TunnelAttr.TOS);
            if (ipv4_tos != null) {
                this.ipv4_tos = ipv4_tos;
                usedFields |= IPV4_TOS_MASK;
            }

            Byte ipv4_ttl = NetlinkMessage.getAttrValueByte(buf, TunnelAttr.TTL);
            if (ipv4_ttl != null) {
                this.ipv4_ttl = ipv4_ttl;
                usedFields |= IPV4_TTL_MASK;
            }

            Boolean dontFrag =
                NetlinkMessage.getAttrValueNone(buf, TunnelAttr.DontFrag);
            if (dontFrag != null ) {
                tun_flags = (short)(tun_flags | OVS_TNL_F_DONT_FRAGMENT);
            }

            Boolean csum = NetlinkMessage.getAttrValueNone(buf, TunnelAttr.CSum);
            if (csum != null) {
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

        @SuppressWarnings("unchecked")
        FlowKeyTunnel that = (FlowKeyTunnel) o;

        return (tun_id == that.tun_id)
            && (ipv4_src == that.ipv4_src)
            && (ipv4_dst == that.ipv4_dst)
            && (tun_flags == that.tun_flags)
            && (ipv4_tos == that.ipv4_tos)
            && (ipv4_ttl == that.ipv4_ttl);
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
