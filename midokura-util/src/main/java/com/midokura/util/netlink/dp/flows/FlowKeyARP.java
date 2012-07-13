/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.util.netlink.dp.flows;

import java.nio.ByteOrder;
import java.util.Arrays;

import com.midokura.util.netlink.NetlinkMessage;
import com.midokura.util.netlink.messages.BaseBuilder;

public class FlowKeyARP implements FlowKey<FlowKeyARP> {

    /*__be32*/ int arp_sip;
    /*__be32*/ int arp_tip;
    /*__be16*/ short arp_op;
    /*__u8*/ byte[] arp_sha = new byte[6]; // 6 bytes long
    /*__u8*/ byte[] arp_tha = new byte[6]; // 6 bytes long

    @Override
    public void serialize(BaseBuilder builder) {
        builder.addValue(arp_sip)
               .addValue(arp_tip)
               .addValue(arp_op)
               .addValue(arp_sha)
               .addValue(arp_tha);
    }

    @Override
    public boolean deserialize(NetlinkMessage message) {
        try {
            arp_sip = message.getInt(ByteOrder.BIG_ENDIAN);
            arp_tip = message.getInt(ByteOrder.BIG_ENDIAN);
            arp_op = message.getShort(ByteOrder.BIG_ENDIAN);
            message.getBytes(arp_sha);
            message.getBytes(arp_tha);
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

    public FlowKeyARP setTargetAddress(byte[] tha) {
        this.arp_tha = tha;
        return this;
    }

    public byte[] getSha() {
        return arp_sha;
    }

    public FlowKeyARP setSourceAddress(byte[] sha) {
        this.arp_sha = sha;
        return this;
    }

    public short getOp() {
        return arp_op;
    }

    public FlowKeyARP setOp(short op) {
        this.arp_op = op;
        return this;
    }

    public int getTip() {
        return arp_tip;
    }

    public FlowKeyARP setTip(int tip) {
        this.arp_tip = tip;
        return this;
    }

    public int getSip() {
        return arp_sip;
    }

    public FlowKeyARP setSip(int sip) {
        this.arp_sip = sip;
        return this;
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
        int result = arp_sip;
        result = 31 * result + arp_tip;
        result = 31 * result + (int) arp_op;
        result = 31 * result + (arp_sha != null ? Arrays.hashCode(arp_sha) : 0);
        result = 31 * result + (arp_tha != null ? Arrays.hashCode(arp_tha) : 0);
        return result;
    }

    @Override
    public String toString() {
        return "FlowKeyARP{" +
            "arp_sip=" + arp_sip +
            ", arp_tip=" + arp_tip +
            ", arp_op=" + arp_op +
            ", arp_sha=" + Arrays.toString(arp_sha) +
            ", arp_tha=" + Arrays.toString(arp_tha) +
            '}';
    }
}
