/*
 * Copyright (c) 2012 Midokura SARL, All Rights Reserved.
 */
package org.midonet.odp.flows;

import java.nio.ByteBuffer;

import org.midonet.netlink.BytesUtil;
import org.midonet.odp.OpenVSwitch;
import org.midonet.packets.IPv4Addr;

/**
 * Flow key/mask for IP
 *
 * When using IP masks, the flow must also include an exact match for the
 * ethernet protocol equal to IP. Otherwise, no packets will match this key/mask.
 *
 * Example:
 *
 * <code>
 * FlowMatch flowMatch = new FlowMatch();
 * FlowMask flowMask = new FlowMask();
 *
 * // ... some other matches/masks
 *
 * flowMatch.addKey(FlowKeys.etherType(FlowKeyEtherType.Type.ETH_P_IP));
 * flowMask.addKey(FlowKeys.etherType(0xFFFF.toShort));
 * flowMatch.addKey(FlowKeys.ipv4(srcIp, dstIp, IpProtocol.TCP));
 * flowMask.addKey(FlowKeys.ipv4(0x0001.toShort, 0x0001.toShort, 0x00, 0x00, 0x00, 0x00));
 *
 * // ... some other matches/masks
 *
 * Flow flow = new Flow(flowMatch, flowMask);
 * </code>
 *
 * @see org.midonet.odp.flows.FlowKey
 * @see org.midonet.odp.flows.FlowKeyEthernet
 * @see org.midonet.odp.FlowMask
 */
public class FlowKeyIPv4 implements FlowKey {

    /*__be32*/ private int ipv4_src;
    /*__be32*/ private int ipv4_dst;
    /*__u8*/ private byte ipv4_proto;
    /*__u8*/ private byte ipv4_tos;
    /*__u8*/ private byte ipv4_ttl;
    /*__u8*/ private byte ipv4_frag;    /* One of OVS_FRAG_TYPE_*. */

    private int hashCode = 0;
    private int connectionHash = 0;

    // This is used for deserialization purposes only.
    FlowKeyIPv4() { }

    FlowKeyIPv4(int src, int dst, byte protocol, byte typeOfService,
                byte ttl, byte fragmentType) {
        this.ipv4_src = src;
        this.ipv4_dst = dst;
        this.ipv4_proto = protocol;
        this.ipv4_tos = typeOfService;
        this.ipv4_ttl = ttl;
        this.ipv4_frag = fragmentType;
        computeHashCode();
    }

    public int serializeInto(ByteBuffer buffer) {
        buffer.putInt(BytesUtil.instance.reverseBE(ipv4_src));
        buffer.putInt(BytesUtil.instance.reverseBE(ipv4_dst));
        buffer.put(ipv4_proto);
        buffer.put(ipv4_tos);
        buffer.put(ipv4_ttl);
        buffer.put(ipv4_frag);
        return 12;
    }

    public void deserializeFrom(ByteBuffer buf) {
        ipv4_src = BytesUtil.instance.reverseBE(buf.getInt());
        ipv4_dst = BytesUtil.instance.reverseBE(buf.getInt());
        ipv4_proto = buf.get();
        ipv4_tos = buf.get();
        ipv4_ttl = buf.get();
        ipv4_frag = buf.get();
        computeHashCode();
    }

    public short attrId() {
        return OpenVSwitch.FlowKey.Attr.IPv4;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        @SuppressWarnings("unchecked")
        FlowKeyIPv4 that = (FlowKeyIPv4) o;

        return (ipv4_dst == that.ipv4_dst)
            && (ipv4_frag == that.ipv4_frag)
            && (ipv4_proto == that.ipv4_proto)
            && (ipv4_src == that.ipv4_src)
            && (ipv4_tos == that.ipv4_tos)
            && (ipv4_ttl == that.ipv4_ttl);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    private void computeHashCode() {
        hashCode = ipv4_src;
        hashCode = 31 * hashCode + ipv4_dst;
        hashCode = 31 * hashCode + (int) ipv4_proto;
        hashCode = 31 * hashCode + (int) ipv4_tos;
        hashCode = 31 * hashCode + (int) ipv4_ttl;
        hashCode = 31 * hashCode + (int) ipv4_frag;
        computeConnectionHash();
    }

    @Override
    public int connectionHash() {
        return connectionHash;
    }

    private void computeConnectionHash() {
        connectionHash = ipv4_src;
        connectionHash = 31 * connectionHash + ipv4_dst;
        connectionHash = 31 * connectionHash + (int) ipv4_proto;
    }

    @Override
    public String toString() {
        return "KeyIPv4{" +
              "src=" + IPv4Addr.intToString(ipv4_src) +
            ", dst=" + IPv4Addr.intToString(ipv4_dst) +
            ", proto=" + ipv4_proto +
            ", tos=" + ipv4_tos +
            ", ttl=" + ipv4_ttl +
            ", frag=" + ipv4_frag +
            '}';
    }

    public int getSrc() {
        return ipv4_src;
    }

    public int getDst() {
        return ipv4_dst;
    }

    public byte getProto() {
        return ipv4_proto;
    }

    public byte getTos() {
        return ipv4_tos;
    }

    public byte getTtl() {
        return ipv4_ttl;
    }

    public byte getFrag() {
        return ipv4_frag;
    }
}

