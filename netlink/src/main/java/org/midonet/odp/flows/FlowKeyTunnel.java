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
    /* be64 */  public long tun_id;
    /* be32 */  public int ipv4_src;
    /* be32 */  public int ipv4_dst;
    /* u16 */   public short tun_flags;
    /* u8 */    public byte ipv4_tos;
    /* u8 */    public byte ipv4_ttl;

    // same size as the tun_flags
    public static final short OVS_TNL_F_DONT_FRAGMENT = 1 << 0;
    public static final short OVS_TNL_F_CSUM = 1 << 1;
    public static final short OVS_TNL_F_KEY = 1 << 2;

    // This is used for deserialization purposes only.
    FlowKeyTunnel() { }

    // OVS kernel module requires the TTL field to be set. Here we set it to
    // the maximum possible value. DONT FRAGMENT and CHECKSUM are for the outer
    // header. We leave those alone for now as well. CHECKSUM defaults to zero
    // in OVS kernel module. GRE header checksum is not checked in gre_rcv, and
    // it is also not required for GRE packets.

    public FlowKeyTunnel(long tunnelId, int ipv4SrcAddr, int ipv4DstAddr, byte tos) {
        this(tunnelId, ipv4SrcAddr, ipv4DstAddr, tos, (byte)-1);
    }

    public FlowKeyTunnel(long tunnelId, int ipv4SrcAddr, int ipv4DstAddr,
                         byte tos, byte ttl) {
        tun_id = tunnelId;
        ipv4_src = ipv4SrcAddr;
        ipv4_dst = ipv4DstAddr;
        ipv4_ttl = ttl;
        ipv4_tos = tos;
    }

    public short attrId() {
        return OpenVSwitch.FlowKey.Attr.Tunnel_N;
    }

    public int serializeInto(ByteBuffer buffer) {
        int nBytes = 0;
        nBytes += NetlinkMessage.writeLongAttr(buffer, TunnelAttr.Id,
                                               BytesUtil.instance.reverseBE(tun_id));
        nBytes += NetlinkMessage.writeIntAttr(buffer, TunnelAttr.IPv4Src,
                                              BytesUtil.instance.reverseBE(ipv4_src));
        nBytes += NetlinkMessage.writeIntAttr(buffer, TunnelAttr.IPv4Dst,
                                              BytesUtil.instance.reverseBE(ipv4_dst));
        nBytes += NetlinkMessage.writeByteAttrNoPad(buffer, TunnelAttr.TOS, ipv4_tos);
        nBytes += NetlinkMessage.writeByteAttrNoPad(buffer, TunnelAttr.TTL, ipv4_ttl);

        if ((tun_flags & OVS_TNL_F_DONT_FRAGMENT) == OVS_TNL_F_DONT_FRAGMENT) {
            NetlinkMessage.setAttrHeader(buffer, TunnelAttr.DontFrag, 4);
            nBytes += 4; // Empty attribute
        }

        if ((tun_flags & OVS_TNL_F_CSUM) == OVS_TNL_F_CSUM) {
            NetlinkMessage.setAttrHeader(buffer, TunnelAttr.CSum, 4);
            nBytes += 4; // Empty attribute
        }

        return nBytes;
    }

    public void deserializeFrom(ByteBuffer buf) {
        NetlinkMessage.scanAttributes(buf, this);
    }

    @Override
    public void wildcard() {
        tun_id = 0;
        ipv4_src = 0;
        ipv4_dst = 0;
        tun_flags = 0;
        ipv4_ttl = 0;
        ipv4_tos = 0;
    }

    public void use(ByteBuffer buf, short id) {
        switch(id) {
            case TunnelAttr.Id:
                this.tun_id = BytesUtil.instance.reverseBE(buf.getLong());
                break;

            case TunnelAttr.IPv4Src:
                this.ipv4_src = BytesUtil.instance.reverseBE(buf.getInt());
                break;

            case TunnelAttr.IPv4Dst:
                this.ipv4_dst = BytesUtil.instance.reverseBE(buf.getInt());
                break;

            case TunnelAttr.TOS:
                this.ipv4_tos = buf.get();
                break;

            case TunnelAttr.TTL:
                this.ipv4_ttl = buf.get();
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
        int hashCode = Longs.hashCode(tun_id);
        hashCode = 31 * hashCode + ipv4_src;
        hashCode = 31 * hashCode + ipv4_dst;
        hashCode = 31 * hashCode + tun_flags;
        hashCode = 31 * hashCode + ipv4_tos;
        hashCode = 31 * hashCode + ipv4_ttl;
        return hashCode;
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
