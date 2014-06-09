/*
 * Copyright (c) 2012 Midokura SARL, All Rights Reserved.
 */
package org.midonet.odp.flows;

import java.util.Arrays;
import java.nio.ByteBuffer;

import org.midonet.netlink.BytesUtil;
import org.midonet.netlink.NetlinkMessage;
import org.midonet.odp.OpenVSwitch;
import org.midonet.packets.MAC;
import org.midonet.packets.Net;

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

    public int serializeInto(ByteBuffer buffer) {
        BytesUtil.instance.writeBEIntsInto(buffer, nd_target);
        buffer.put(nd_sll);
        buffer.put(nd_tll);
        return 28;
    }

    @Override
    public boolean deserialize(NetlinkMessage message) {
        try {
            nd_target = new int[4];
            for (int i = 0; i < 4; i++) {
                nd_target[i] =
                    BytesUtil.instance.reverseBE(message.getInt());
            }
            message.getBytes(nd_sll);
            message.getBytes(nd_tll);
            return true;
        } catch (Exception e) {
            return false;
        }
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
