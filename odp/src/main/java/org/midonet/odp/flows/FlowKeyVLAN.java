/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.odp.flows;

import java.nio.ByteOrder;

import org.midonet.netlink.NetlinkMessage;
import org.midonet.netlink.messages.BaseBuilder;

public class FlowKeyVLAN implements FlowKey<FlowKeyVLAN> {

    /* be16 */
    //short pcp; // Priority Code Point 3 bits
    //short dei; // Drop Elegible Indicator 1 bit
    short vlan; // 12 bit

    @Override
    public void serialize(BaseBuilder<?,?> builder) {
        builder.addValue((short)(vlan | 0x1000), ByteOrder.BIG_ENDIAN);
    }

    @Override
    public boolean deserialize(NetlinkMessage message) {
        try {
            short tci = message.getShort(ByteOrder.BIG_ENDIAN);
            vlan = (short)(tci & 0x0fff);
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
