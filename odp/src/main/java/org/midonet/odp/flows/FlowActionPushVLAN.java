/*
 * Copyright (c) 2012 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.odp.flows;

import java.nio.ByteOrder;

import org.midonet.netlink.NetlinkMessage;
import org.midonet.netlink.messages.BaseBuilder;
import org.midonet.packets.VLAN;

public class FlowActionPushVLAN implements FlowAction<FlowActionPushVLAN> {

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

    @Override
    public void serialize(BaseBuilder<?,?> builder) {
        builder.addValue(vlan_tpid, ByteOrder.BIG_ENDIAN);
        builder.addValue(vlan_tci, ByteOrder.BIG_ENDIAN);
    }

    @Override
    public boolean deserialize(NetlinkMessage message) {
        try {
            vlan_tpid = message.getShort(ByteOrder.BIG_ENDIAN);
            vlan_tci = message.getShort(ByteOrder.BIG_ENDIAN);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public NetlinkMessage.AttrKey<FlowActionPushVLAN> getKey() {
        return FlowActionAttr.PUSH_VLAN;
    }

    @Override
    public FlowActionPushVLAN getValue() {
        return this;
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

        FlowActionPushVLAN that = (FlowActionPushVLAN) o;

        if (vlan_tci != that.vlan_tci) return false;
        if (vlan_tpid != that.vlan_tpid) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = (int) vlan_tpid;
        result = 31 * result + (int) vlan_tci;
        return result;
    }

    @Override
    public String toString() {
        return "FlowActionPushVLAN{" +
            "vlan_tpid=" + vlan_tpid +
            ", vlan_tci=" + vlan_tci +
            '}';
    }
}
