/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.util.netlink.dp.flows;

import com.midokura.util.netlink.NetlinkMessage;
import com.midokura.util.netlink.messages.BaseBuilder;

public class FlowActionPushVLAN implements FlowAction<FlowActionPushVLAN> {

    /** 802.1Q TPID. */
    /*__be16*/ short vlan_tpid;

    /** 802.1Q TCI (VLAN ID and priority). */
    /*__be16*/ short vlan_tci;

    @Override
    public void serialize(BaseBuilder builder) {
        builder.addValue(vlan_tpid);
        builder.addValue(vlan_tci);
    }

    @Override
    public boolean deserialize(NetlinkMessage message) {
        try {
            vlan_tpid = message.getShort();
            vlan_tci = message.getShort();
            return true;
        } catch (Exception e) {
            return false;
        }
    }


    @Override
    public NetlinkMessage.AttrKey<FlowActionPushVLAN> getKey() {
        return FlowActionKey.PUSH_VLAN;
    }

    @Override
    public FlowActionPushVLAN getValue() {
        return this;
    }

    public short getTagProtocolIdentifier() {
        return vlan_tpid;
    }

    public FlowActionPushVLAN setTagProtocolIdentifier(short tagProtocolIdentifier) {
        this.vlan_tpid = tagProtocolIdentifier;
        return this;
    }

    public short getTagControlIdentifier() {
        return vlan_tci;
    }

    public FlowActionPushVLAN setTagControlIdentifier(short tagControlIdentifier) {
        this.vlan_tci = tagControlIdentifier;
        return this;
    }
}
