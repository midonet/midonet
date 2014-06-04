/*
 * Copyright (c) 2012 Midokura SARL, All Rights Reserved.
 */
package org.midonet.odp.flows;

import java.nio.ByteBuffer;

import org.midonet.netlink.NetlinkMessage;
import org.midonet.odp.OpenVSwitch;

public class FlowActionUserspace implements FlowAction {

    public static final short pidAttrId =
        OpenVSwitch.FlowAction.UserspaceAttr.PID;

    public static final short userdataAttrId =
        OpenVSwitch.FlowAction.UserspaceAttr.Userdata;

    private int uplinkPid;  /* u32 Netlink PID to receive upcalls. */
    private Long userData;  /* u64 optional user-specified cookie. */

    // This is used for deserialization purposes only.
    FlowActionUserspace() { }

    FlowActionUserspace(int uplinkPid) {
        this.uplinkPid = uplinkPid;
    }

    FlowActionUserspace(int uplinkPid, long userData) {
        this.uplinkPid = uplinkPid;
        this.userData = userData;
    }

    public int serializeInto(ByteBuffer buffer) {
        int nBytes = 0;

        nBytes += NetlinkMessage.writeIntAttr(buffer, pidAttrId, uplinkPid);

        if (userData == null)
            return nBytes;

        nBytes += NetlinkMessage.writeLongAttr(buffer, userdataAttrId, userData);

        return nBytes;
    }

    @Override
    public boolean deserialize(ByteBuffer buf) {
        try {
            uplinkPid = NetlinkMessage.getAttrValueInt(buf, pidAttrId);
            userData = NetlinkMessage.getAttrValueLong(buf, userdataAttrId);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public short attrId() {
        return NetlinkMessage.nested(OpenVSwitch.FlowAction.Attr.Userspace);
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
        return "FlowActionUserspace{uplinkPid=" + uplinkPid +
               ", userData=" + userData + '}';
    }

    public int getUplinkPid() {
        return uplinkPid;
    }

    public Long getUserData() {
        return userData;
    }
}
