/*
 * Copyright (c) 2012 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.odp.flows;

import java.nio.ByteOrder;
import java.util.Arrays;

import org.midonet.netlink.NetlinkMessage;
import org.midonet.netlink.messages.Builder;
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
    }

    @Override
    public void serialize(Builder builder) {
        builder.addValue(arp_sip, ByteOrder.BIG_ENDIAN);
        builder.addValue(arp_tip, ByteOrder.BIG_ENDIAN);
        builder.addValue(arp_op, ByteOrder.BIG_ENDIAN);
        builder.addValue(arp_sha);
        builder.addValue(arp_tha);
        builder.addValue((short)0); // padding
    }

    @Override
    public boolean deserialize(NetlinkMessage message) {
        try {
            arp_sip = message.getInt(ByteOrder.BIG_ENDIAN);
            arp_tip = message.getInt(ByteOrder.BIG_ENDIAN);
            arp_op = message.getShort(ByteOrder.BIG_ENDIAN);
            message.getBytes(arp_sha);
            message.getBytes(arp_tha);
            this.hashCode = 0;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public NetlinkMessage.AttrKey<FlowKeyARP> getKey() {
        return FlowKeyAttr.ARP;
    }

    @Override
    public FlowKeyARP getValue() {
        return this;
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

        FlowKeyARP that = (FlowKeyARP) o;

        if (arp_op != that.arp_op) return false;
        if (arp_sip != that.arp_sip) return false;
        if (arp_tip != that.arp_tip) return false;
        if (!Arrays.equals(arp_sha, that.arp_sha)) return false;
        if (!Arrays.equals(arp_tha, that.arp_tha)) return false;

        return true;
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            int result = arp_sip;
            result = 31 * result + arp_tip;
            result = 31 * result + (int) arp_op;
            result = 31 * result + (arp_sha != null ? Arrays.hashCode(arp_sha) : 0);
            result = 31 * result + (arp_tha != null ? Arrays.hashCode(arp_tha) : 0);
            hashCode = result;
        }
        return hashCode;
    }

    @Override
    public String toString() {
        return "FlowKeyARP{" +
            "arp_sip=" + IPv4Addr.intToString(arp_sip) +
            ", arp_tip=" + IPv4Addr.intToString(arp_tip) +
            ", arp_op=" + arp_op +
            ", arp_sha=" +
                (arp_sha == null ? "null" : MAC.bytesToString(arp_sha)) +
            ", arp_tha=" +
                (arp_tha == null ? "null" : MAC.bytesToString(arp_tha)) +
            '}';
    }
}
