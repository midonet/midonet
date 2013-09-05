/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.odp.flows;

import java.nio.ByteOrder;
import java.util.Arrays;

import org.midonet.packets.Net;
import org.midonet.netlink.NetlinkMessage;
import org.midonet.netlink.messages.BaseBuilder;

/**
* Neighbour Discovery key
*/
public class FlowKeyND implements FlowKey<FlowKeyND> {
    /*__u32*/ int[] nd_target = new int[4]; // always 4 int long
    /*__u8*/ byte[] nd_sll = new byte[6];   // always 6 bytes long
    /*__u8*/ byte[] nd_tll = new byte[6];   // always 6 bytes long

    @Override
    public void serialize(BaseBuilder<?,?> builder) {
        builder.addValue(nd_target, ByteOrder.BIG_ENDIAN);
        builder.addValue(nd_sll);
        builder.addValue(nd_tll);
    }

    @Override
    public boolean deserialize(NetlinkMessage message) {
        try {
            message.getInts(nd_target, ByteOrder.BIG_ENDIAN);
            message.getBytes(nd_sll);
            message.getBytes(nd_tll);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public NetlinkMessage.AttrKey<FlowKeyND> getKey() {
        return FlowKeyAttr.ND;
    }

    @Override
    public FlowKeyND getValue() {
        return this;
    }

    public int[] getTarget() {
        return nd_target;
    }

    public FlowKeyND setTarget(int[] target) {
        this.nd_target = target;
        return this;
    }

    public byte[] getSourceLinkLayer() {
        return nd_sll;
    }

    public FlowKeyND setSourceLinkLayer(byte[] sll) {
        this.nd_sll = sll;
        return this;
    }

    public byte[] getTargetLinkLayer() {
        return nd_tll;
    }

    public FlowKeyND setTargetLinkLayer(byte[] tll) {
        this.nd_tll = tll;
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        FlowKeyND flowKeyND = (FlowKeyND) o;

        if (!Arrays.equals(nd_sll, flowKeyND.nd_sll)) return false;
        if (!Arrays.equals(nd_target, flowKeyND.nd_target)) return false;
        if (!Arrays.equals(nd_tll, flowKeyND.nd_tll)) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = nd_target != null ? Arrays.hashCode(nd_target) : 0;
        result = 31 * result + (nd_sll != null ? Arrays.hashCode(nd_sll) : 0);
        result = 31 * result + (nd_tll != null ? Arrays.hashCode(nd_tll) : 0);
        return result;
    }

    @Override
    public String toString() {
        return "FlowKeyND{" +
            "nd_target=" + Net.convertIPv6BytesToString(nd_target) +
            ", nd_sll=" + Net.convertByteMacToString(nd_sll) +
            ", nd_tll=" + Net.convertByteMacToString(nd_tll) +
            '}';
    }
}
