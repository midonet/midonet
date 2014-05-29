/*
 * Copyright (c) 2012 Midokura SARL, All Rights Reserved.
 */
package org.midonet.odp.flows;

import java.nio.ByteOrder;
import java.util.Arrays;

import org.midonet.packets.MAC;
import org.midonet.packets.Net;
import org.midonet.netlink.NetlinkMessage;
import org.midonet.netlink.messages.Builder;

/**
* Neighbour Discovery key
*/
public class FlowKeyND implements CachedFlowKey {
    /*__u32*/ private int[] nd_target; // always 4 int long
    /*__u8*/ private byte[] nd_sll = new byte[6];   // always 6 bytes long
    /*__u8*/ private byte[] nd_tll = new byte[6];   // always 6 bytes long

    // This is used for deserialization purposes only.
    FlowKeyND() { }

    FlowKeyND(int[] target) {
        nd_target = target;
    }

    @Override
    public void serialize(Builder builder) {
        builder.addValue(nd_target, ByteOrder.BIG_ENDIAN);
        builder.addValue(nd_sll);
        builder.addValue(nd_tll);
    }

    @Override
    public boolean deserialize(NetlinkMessage message) {
        try {
            nd_target = new int[4];
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
    public byte[] getSourceLinkLayer() {
        return nd_sll;
    }

    public byte[] getTargetLinkLayer() {
        return nd_tll;
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
        int result = Arrays.hashCode(nd_target);
        result = 31 * result + Arrays.hashCode(nd_sll);
        result = 31 * result + Arrays.hashCode(nd_tll);
        return result;
    }

    @Override
    public String toString() {
        return "FlowKeyND{nd_target=" +
                (nd_target == null ?
                    "null" : Net.convertIPv6BytesToString(nd_target)) +
                ", nd_sll=" +
                    (nd_sll == null ? "null" : MAC.bytesToString(nd_sll)) +
                ", nd_tll=" +
                    (nd_tll == null ? "null" : MAC.bytesToString(nd_tll)) +
                '}';
    }
}
