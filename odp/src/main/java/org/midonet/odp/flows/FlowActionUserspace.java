/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
