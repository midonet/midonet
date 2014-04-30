/*
 * Copyright (c) 2012 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.odp.flows;

import java.nio.ByteOrder;

import org.midonet.netlink.NetlinkMessage;
import org.midonet.netlink.messages.Builder;
import org.midonet.packets.VLAN;

public class FlowKeyVLAN implements CachedFlowKey {

    /* be16 */
    //short pcp; // Priority Code Point 3 bits
    //short dei; // Drop Elegible Indicator 1 bit
    private short vlan; // 12 bit

    // This is used for deserialization purposes only.
    FlowKeyVLAN() { }

    FlowKeyVLAN(short vlanTCI) {
        vlan = vlanTCI;
    }

    @Override
    public void serialize(Builder builder) {
        builder.addValue(VLAN.setDEI(vlan), ByteOrder.BIG_ENDIAN);
    }

    @Override
    public boolean deserialize(NetlinkMessage message) {
        try {
            vlan = VLAN.unsetDEI(message.getShort(ByteOrder.BIG_ENDIAN));
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
        return vlan;
    }

    @Override
    public String toString() {
        return "FlowKeyVLAN{vlan=" + vlan + '}';
    }
}
