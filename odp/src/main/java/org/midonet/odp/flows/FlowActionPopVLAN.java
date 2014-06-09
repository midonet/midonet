/*
 * Copyright (c) 2012 Midokura SARL, All Rights Reserved.
 */
package org.midonet.odp.flows;

import java.nio.ByteBuffer;

import org.midonet.odp.OpenVSwitch;

public class FlowActionPopVLAN implements FlowAction {

    FlowActionPopVLAN() { }

    /** For the POP_VLAN action, nothing is actually serialised after the
     *  netlink attribute header, since POP_VLAN is a "value-less" action. In
     *  the datapath code, the 0 length (not-counting the header) is checked and
     *  enforced in flow-netlink.c in ovs_nla_copy_actions(). */
    public int serializeInto(ByteBuffer buffer) {
        return 0;
    }

    @Override
    public boolean deserialize(ByteBuffer buf) {
        return true;
    }

    public short attrId() {
        return OpenVSwitch.FlowAction.Attr.PopVLan;
    }

    @Override
    public int hashCode() {
        return 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        return true;
    }

    @Override
    public String toString() {
        return "FlowActionPopVLAN{}";
    }
}
