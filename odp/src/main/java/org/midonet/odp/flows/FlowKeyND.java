/*
 * Copyright (c) 2012 Midokura SARL, All Rights Reserved.
 */
package org.midonet.odp.flows;

import java.util.Arrays;
import java.nio.ByteBuffer;

import org.midonet.netlink.BytesUtil;
import org.midonet.odp.OpenVSwitch;
import org.midonet.packets.MAC;
import org.midonet.packets.Net;

/**
* Neighbour Discovery key
*/
public class FlowKeyND implements CachedFlowKey {

    /*__u32*/ private int[] nd_target = new int[4]; // always 4 int long
    /*__u8*/ private byte[] nd_sll = new byte[6];   // always 6 bytes long
    /*__u8*/ private byte[] nd_tll = new byte[6];   // always 6 bytes long

    // This is used for deserialization purposes only.
    FlowKeyND() { }

    FlowKeyND(int[] target) {
        nd_target = target;
    }

    public int serializeInto(ByteBuffer buffer) {
        BytesUtil.instance.writeBEIntsInto(buffer, nd_target);
        buffer.put(nd_sll);
        buffer.put(nd_tll);
        return 28;
    }

    public void deserializeFrom(ByteBuffer buf) {
        BytesUtil.instance.readBEIntsFrom(buf, nd_target);
        buf.get(nd_sll);
        buf.get(nd_tll);
    }

    public short attrId() {
        return OpenVSwitch.FlowKey.Attr.ND;
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

        @SuppressWarnings("unchecked")
        FlowKeyND that = (FlowKeyND) o;

        return Arrays.equals(this.nd_sll, that.nd_sll)
            && Arrays.equals(this.nd_target, that.nd_target)
            && Arrays.equals(this.nd_tll, that.nd_tll);
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
        return "FlowKeyND{" +
                "nd_target=" + Net.convertIPv6BytesToString(nd_target) +
                ", nd_sll=" + MAC.bytesToString(nd_sll) +
                ", nd_tll=" + MAC.bytesToString(nd_tll) +
                '}';
    }
}
