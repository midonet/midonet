/*
 * Copyright (c) 2012 Midokura SARL, All Rights Reserved.
 */
package org.midonet.odp.flows;

import java.util.Objects;
import java.nio.ByteBuffer;

import org.midonet.netlink.AttributeHandler;
import org.midonet.netlink.NetlinkMessage;
import org.midonet.odp.OpenVSwitch;
import org.midonet.odp.OpenVSwitch.FlowAction.UserspaceAttr;

public class FlowActionUserspace implements FlowAction,
                                            AttributeHandler, Randomize {

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

        nBytes += NetlinkMessage.writeIntAttr(buffer, UserspaceAttr.PID,
                                              uplinkPid);

        if (userData == null)
            return nBytes;

        nBytes += NetlinkMessage.writeLongAttr(buffer, UserspaceAttr.Userdata,
                                               userData);

        return nBytes;
    }

    public void deserializeFrom(ByteBuffer buf) {
        NetlinkMessage.scanAttributes(buf, this);
    }

    public void use(ByteBuffer buf, short id) {
        switch(NetlinkMessage.unnest(id)) {
            case UserspaceAttr.PID:
                uplinkPid = buf.getInt();
                break;
            case UserspaceAttr.Userdata:
                userData = buf.getLong();
                break;
        }
    }

    public short attrId() {
        return NetlinkMessage.nested(OpenVSwitch.FlowAction.Attr.Userspace);
    }

    public void randomize() {
        uplinkPid = FlowActions.rand.nextInt();
        userData = FlowActions.rand.nextLong();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        @SuppressWarnings("unchecked")
        FlowActionUserspace that = (FlowActionUserspace) o;

        return (uplinkPid == that.uplinkPid)
            && Objects.equals(this.userData, that.userData);
    }

    @Override
    public int hashCode() {
        return 31 * uplinkPid + Objects.hashCode(userData);
    }

    @Override
    public String toString() {
        return "ToUserspace{socket=" + uplinkPid +
               ", data=" + userData + '}';
    }

    public int getUplinkPid() {
        return uplinkPid;
    }

    public Long getUserData() {
        return userData;
    }
}
