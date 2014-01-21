/*
 * Copyright (c) 2012 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.odp.flows;

import org.midonet.netlink.NetlinkMessage;
import org.midonet.netlink.NetlinkMessage.AttrKey;
import org.midonet.netlink.messages.Builder;
import org.midonet.odp.OpenVSwitch;

public class FlowActionUserspace implements FlowAction {

    /* u32 Netlink PID to receive upcalls. */
    public static final AttrKey<Integer> OVS_USERSPACE_ATTR_PID =
        AttrKey.attr(OpenVSwitch.FlowAction.UserspaceAttr.PID);

    /* u64 optional user-specified cookie. */
    public static final AttrKey<Long> OVS_USERSPACE_ATTR_USERDATA =
        AttrKey.attr(OpenVSwitch.FlowAction.UserspaceAttr.Userdata);

    private int uplinkPid;
    private Long userData;

    // This is used for deserialization purposes only.
    FlowActionUserspace() { }

    FlowActionUserspace(int uplinkPid) {
        this.uplinkPid = uplinkPid;
    }

    FlowActionUserspace(int uplinkPid, long userData) {
        this.uplinkPid = uplinkPid;
        this.userData = userData;
    }

    @Override
    public void serialize(Builder builder) {
        builder.addAttr(OVS_USERSPACE_ATTR_PID, uplinkPid);
        if (userData != null) {
            builder.addAttr(OVS_USERSPACE_ATTR_USERDATA, userData);
        }
    }

    @Override
    public boolean deserialize(NetlinkMessage message) {
        try {
            uplinkPid = message.getAttrValueInt(OVS_USERSPACE_ATTR_PID);
            userData = message.getAttrValueLong(OVS_USERSPACE_ATTR_USERDATA);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public NetlinkMessage.AttrKey<FlowActionUserspace> getKey() {
        return FlowActionAttr.USERSPACE;
    }

    @Override
    public FlowActionUserspace getValue() {
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        FlowActionUserspace that = (FlowActionUserspace) o;

        if (uplinkPid != that.uplinkPid) return false;
        if (userData != null ? !userData.equals(
            that.userData) : that.userData != null) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = uplinkPid;
        result = 31 * result + (userData != null ? userData.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return "FlowActionUserspace{" +
            "uplinkPid=" + uplinkPid +
            ", userData=" + userData +
            '}';
    }

    public int getUplinkPid() {
        return uplinkPid;
    }

    public Long getUserData() {
        return userData;
    }
}
