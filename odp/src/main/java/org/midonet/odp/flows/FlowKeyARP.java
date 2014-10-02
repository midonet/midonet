/*
 * Copyright (c) 2012 Midokura SARL, All Rights Reserved.
 */
package org.midonet.odp.flows;

import java.util.Arrays;
import java.nio.ByteBuffer;

import org.midonet.netlink.BytesUtil;
import org.midonet.odp.OpenVSwitch;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.MAC;

public class FlowKeyARP implements CachedFlowKey {

    /*__be32*/ private int arp_sip;
    /*__be32*/ private int arp_tip;
    /*__be16*/ private short arp_op;
    /*__u8*/ private byte[] arp_sha = new byte[6]; // 6 bytes long
    /*__u8*/ private byte[] arp_tha = new byte[6]; // 6 bytes long

    private int hashCode = 0;

    // This is used for deserialization purposes only.
    FlowKeyARP() { }

    FlowKeyARP(byte[] sourceAddress, byte[] targetAddress, short opcode,
               int sourceIp, int targetIp) {
        arp_sha = sourceAddress;
        arp_tha = targetAddress;
        arp_op = opcode;
        arp_sip = sourceIp;
        arp_tip = targetIp;
        computeHashCode();
    }

    public int serializeInto(ByteBuffer buffer) {
        buffer.putInt(BytesUtil.instance.reverseBE(arp_sip));
        buffer.putInt(BytesUtil.instance.reverseBE(arp_tip));
        buffer.putShort(BytesUtil.instance.reverseBE(arp_op));
        buffer.put(arp_sha);
        buffer.put(arp_tha);
        buffer.putShort((short)0); // padding
        return 24;
    }

    public void deserializeFrom(ByteBuffer buf) {
        arp_sip = BytesUtil.instance.reverseBE(buf.getInt());
        arp_tip = BytesUtil.instance.reverseBE(buf.getInt());
        arp_op = BytesUtil.instance.reverseBE(buf.getShort());
        buf.get(arp_sha);
        buf.get(arp_tha);
        computeHashCode();
    }

    public short attrId() {
        return OpenVSwitch.FlowKey.Attr.ARP;
    }

    public byte[] getTha() {
        return arp_tha;
    }

    public byte[] getSha() {
        return arp_sha;
    }

    public short getOp() {
        return arp_op;
    }

    public int getTip() {
        return arp_tip;
    }

    public int getSip() {
        return arp_sip;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        @SuppressWarnings("unchecked")
        FlowKeyARP that = (FlowKeyARP) o;

        return (arp_op == that.arp_op)
            && (arp_sip == that.arp_sip)
            && (arp_tip == that.arp_tip)
            && Arrays.equals(arp_sha, that.arp_sha)
            && Arrays.equals(arp_tha, that.arp_tha);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    @Override
    public int connectionHash() { return 0; }

    private void computeHashCode() {
        hashCode = arp_sip;
        hashCode = 31 * hashCode + arp_tip;
        hashCode = 31 * hashCode + arp_op;
        hashCode = 31 * hashCode + Arrays.hashCode(arp_sha);
        hashCode = 31 * hashCode + Arrays.hashCode(arp_tha);
    }

    @Override
    public String toString() {
        return "KeyARP{" +
              "sip=" + IPv4Addr.intToString(arp_sip) +
            ", tip=" + IPv4Addr.intToString(arp_tip) +
            ", op=" + arp_op +
            ", sha=" + MAC.bytesToString(arp_sha) +
            ", tha=" + MAC.bytesToString(arp_tha) +
            '}';
    }
}
