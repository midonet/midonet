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

import java.nio.ByteBuffer;
import java.util.Objects;

import org.midonet.netlink.NetlinkMessage;
import org.midonet.odp.OpenVSwitch;

public class FlowActionSetKey implements FlowAction, Randomize {

    FlowKey flowKey;

    // This is used for deserialization purposes only.
    FlowActionSetKey() { }

    FlowActionSetKey(FlowKey flowKey) {
        this.flowKey = flowKey;
    }

    public int serializeInto(ByteBuffer buffer) {
        return NetlinkMessage.writeAttr(buffer, flowKey, FlowKeys.writer);
    }

    public void deserializeFrom(ByteBuffer buf) {
        short attrLen = buf.getShort();
        short attrId = buf.getShort();
        flowKey = FlowKeys.newBlankInstance(attrId);
        if (flowKey == null)
            return;
        flowKey.deserializeFrom(buf);
    }

    public short attrId() {
        return NetlinkMessage.nested(OpenVSwitch.FlowAction.Attr.Set);
    }

    public void randomize() {
        flowKey = FlowKeys.randomKey();
    }

    public FlowKey getFlowKey() {
        return flowKey;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        @SuppressWarnings("unchecked")
        FlowActionSetKey that = (FlowActionSetKey) o;

        return Objects.equals(this.flowKey, that.flowKey);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(flowKey);
    }

    @Override
    public String toString() {
        return "SetKey{" + flowKey + '}';
    }
}
