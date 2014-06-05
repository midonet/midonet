/*
 * Copyright (c) 2012 Midokura SARL, All Rights Reserved.
 */
package org.midonet.odp.flows;

import java.nio.ByteBuffer;

import org.midonet.netlink.BytesUtil;
import org.midonet.netlink.NetlinkMessage;
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

    public int serializeInto(ByteBuffer buffer) {
        buffer.putShort(BytesUtil.instance.reverseBE(VLAN.setDEI(vlan)));
        return 2;
    }

    @Override
    public boolean deserialize(NetlinkMessage message) {
        try {
            vlan = VLAN.unsetDEI(BytesUtil.instance.reverseBE(message.getShort()));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public short attrId() {
        return FlowKeyAttr.VLAN.getId();
    }

    public short getVLAN() {
        return vlan;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        @SuppressWarnings("unchecked")
        FlowKeyVLAN that = (FlowKeyVLAN) o;

        return vlan == that.vlan;
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
