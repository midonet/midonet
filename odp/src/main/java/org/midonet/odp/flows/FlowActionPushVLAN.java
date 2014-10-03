/*
 * Copyright (c) 2012 Midokura SARL, All Rights Reserved.
 */
package org.midonet.odp.flows;

import java.nio.ByteBuffer;

import org.midonet.netlink.BytesUtil;
import org.midonet.odp.OpenVSwitch;
import org.midonet.packets.VLAN;

public class FlowActionPushVLAN implements FlowAction {

    /** 802.1Q TPID. */
    /*__be16*/ private short vlan_tpid;

    /** 802.1Q TCI (VLAN ID and priority). */
    /*__be16*/ private short vlan_tci;

    // This is used for deserialization purposes only.
    FlowActionPushVLAN() { }

    FlowActionPushVLAN(short tagControlIdentifier) {
        this(tagControlIdentifier,
             (short) FlowKeyEtherType.Type.ETH_P_8021Q.value);
    }

    FlowActionPushVLAN(short tagControlIdentifier, short tagProtocolId) {
        vlan_tci = VLAN.setDEI(tagControlIdentifier);
        vlan_tpid = tagProtocolId;
    }

    public int serializeInto(ByteBuffer buffer) {
        buffer.putShort(BytesUtil.instance.reverseBE(vlan_tpid));
        buffer.putShort(BytesUtil.instance.reverseBE(vlan_tci));
        return 4;
    }

    public void deserializeFrom(ByteBuffer buf) {
        vlan_tpid = BytesUtil.instance.reverseBE(buf.getShort());
        vlan_tci = BytesUtil.instance.reverseBE(buf.getShort());
    }

    public short attrId() {
        return OpenVSwitch.FlowAction.Attr.PushVLan;
    }

    public short getTagProtocolIdentifier() {
        return vlan_tpid;
    }

    public short getTagControlIdentifier() {
        return vlan_tci;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        @SuppressWarnings("unchecked")
        FlowActionPushVLAN that = (FlowActionPushVLAN) o;

        return (vlan_tci == that.vlan_tci) && (vlan_tpid == that.vlan_tpid);
    }

    @Override
    public int hashCode() {
        return 31 * vlan_tpid + vlan_tci;
    }

    @Override
    public String toString() {
        return "PushVLAN{tpid=" + vlan_tpid +
                       ", tci=" + vlan_tci + '}';
    }
}
