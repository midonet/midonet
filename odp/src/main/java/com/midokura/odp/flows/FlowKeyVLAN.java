/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.odp.flows;

import com.midokura.netlink.NetlinkMessage;
import com.midokura.netlink.messages.BaseBuilder;

public class FlowKeyVLAN implements FlowKey<FlowKeyVLAN> {

    /* be16 */ short vlan;

    @Override
    public void serialize(BaseBuilder builder) {
        builder.addValue(vlan);
    }

    @Override
    public boolean deserialize(NetlinkMessage message) {
        try {
            vlan = message.getShort();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public NetlinkMessage.AttrKey<FlowKeyVLAN> getKey() {
        return FlowKeyAttr.VLAN;
    }

    @Override
    public FlowKeyVLAN getValue() {
        return this;
    }

    public short getVLAN() {
        return vlan;
    }

    public FlowKeyVLAN setVLAN(short vlan) {
        this.vlan = vlan;
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        FlowKeyVLAN that = (FlowKeyVLAN) o;

        if (vlan != that.vlan) return false;

        return true;
    }

    @Override
    public int hashCode() {
        return (int) vlan;
    }

    @Override
    public String toString() {
        return "FlowKeyVLAN{" +
            "vlan=" + vlan +
            '}';
    }
}
