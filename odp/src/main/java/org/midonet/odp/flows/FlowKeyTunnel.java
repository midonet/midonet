/*
 * Copyright 2014 Midokura SARL
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
package org.midonet.odp.flows;

import java.nio.ByteBuffer;
import java.util.concurrent.ThreadLocalRandom;

import com.google.common.primitives.Longs;

import org.midonet.netlink.AttributeHandler;
import org.midonet.netlink.BytesUtil;
import org.midonet.netlink.NetlinkMessage;
import org.midonet.odp.OpenVSwitch.FlowKey.TunnelAttr;
import org.midonet.odp.OpenVSwitch;
import org.midonet.packets.IPv4Addr;

public class FlowKeyTunnel implements CachedFlowKey,
                                      Randomize, AttributeHandler {

    // maintaining the names of field to be the same as ovs_key_ipv4_tunnel
    // see datapath/flow.h from OVS source
    /* be64 */  private long tun_id;
    /* be32 */  private int ipv4_src;
    /* be32 */  private int ipv4_dst;
    /* u16 */   private short tun_flags;
    /* u8 */    private byte ipv4_tos;
    /* u8 */    private byte ipv4_ttl;

    private int hashCode = 0;
    private byte usedFields;

    // same size as the tun_flags
    public static final short OVS_TNL_F_DONT_FRAGMENT = 1 << 0;
    public static final short OVS_TNL_F_CSUM = 1 << 1;
    public static final short OVS_TNL_F_KEY = 1 << 2;

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
        computeHashCode();
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

    public void deserializeFrom(ByteBuffer buf) {
        NetlinkMessage.scanAttributes(buf, this);
        computeHashCode();
    }

    public void use(ByteBuffer buf, short id) {
        switch(id) {
            case TunnelAttr.Id:
                this.tun_id = BytesUtil.instance.reverseBE(buf.getLong());
                usedFields |= TUN_ID_MASK;
                break;

            case TunnelAttr.IPv4Src:
                this.ipv4_src = BytesUtil.instance.reverseBE(buf.getInt());
                usedFields |= IPV4_SRC_MASK;
                break;

            case TunnelAttr.IPv4Dst:
                this.ipv4_dst = BytesUtil.instance.reverseBE(buf.getInt());
                usedFields |= IPV4_DST_MASK;
                break;

            case TunnelAttr.TOS:
                this.ipv4_tos = buf.get();
                usedFields |= IPV4_TOS_MASK;
                break;

            case TunnelAttr.TTL:
                this.ipv4_ttl = buf.get();
                usedFields |= IPV4_TTL_MASK;
                break;

            case TunnelAttr.DontFrag:
                tun_flags = (short)(tun_flags | OVS_TNL_F_DONT_FRAGMENT);
                break;

            case TunnelAttr.CSum:
                tun_flags = (short)(tun_flags | OVS_TNL_F_CSUM);
                break;
        }
    }

    public void randomize() {
        tun_id = ThreadLocalRandom.current().nextLong();
        ipv4_src = ThreadLocalRandom.current().nextInt();
        ipv4_dst = ThreadLocalRandom.current().nextInt();
        ipv4_ttl = (byte)-1;
        usedFields = TUN_ID_MASK | IPV4_SRC_MASK | IPV4_DST_MASK | IPV4_TTL_MASK;
        computeHashCode();
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
        return hashCode;
    }

    private void computeHashCode() {
        hashCode = Longs.hashCode(tun_id);
        hashCode = 31 * hashCode + ipv4_src;
        hashCode = 31 * hashCode + ipv4_dst;
        hashCode = 31 * hashCode + tun_flags;
        hashCode = 31 * hashCode + ipv4_tos;
        hashCode = 31 * hashCode + ipv4_ttl;
    }

    @Override
    public String toString() {
        return "Tunnel{"
            + "tun_id=" + tun_id + ", "
            + "ipv4_src=" + IPv4Addr.intToString(ipv4_src) + ", "
            + "ipv4_dst=" + IPv4Addr.intToString(ipv4_dst) + ", "
            + "tun_flag=" + tun_flags + ", "
            + "ipv4_tos=" + ipv4_tos + ", "
            + "ipv4_ttl=" + ipv4_ttl
            + '}';
    }
}
