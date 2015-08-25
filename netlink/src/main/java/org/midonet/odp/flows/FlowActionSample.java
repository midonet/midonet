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
import java.util.List;
import java.util.Objects;

import org.midonet.netlink.AttributeHandler;
import org.midonet.netlink.NetlinkMessage;
import org.midonet.odp.OpenVSwitch;
import org.midonet.odp.OpenVSwitch.FlowAction.SampleAttr;

public class FlowActionSample implements FlowAction,
                                         AttributeHandler, Randomize {

    /**
     * u32 port number.
     */
    private int probability;

    private List<FlowAction> actions;

    // This is used for deserialization purposes only.
    FlowActionSample() { }

    FlowActionSample(int probability, List<FlowAction> actions) {
        this.probability = probability;
        this.actions = actions;
    }

    public int serializeInto(ByteBuffer buffer) {
        int nBytes= 0;
        nBytes += NetlinkMessage.writeIntAttr(buffer, SampleAttr.Probability,
                                              probability);
        nBytes += NetlinkMessage.writeAttrSeq(buffer, SampleAttr.Actions,
                                              actions, FlowActions.writer);
        return nBytes;
    }

    public void deserializeFrom(ByteBuffer buf) {
        NetlinkMessage.scanAttributes(buf, this);
    }

    public void use(ByteBuffer buf, short id) {
        switch(NetlinkMessage.unnest(id)) {
            case SampleAttr.Probability:
                probability = buf.getInt();
                break;
            case SampleAttr.Actions:
                actions = FlowActions.reader.deserializeFrom(buf);
                break;
        }
    }

    public short attrId() {
        return NetlinkMessage.nested(OpenVSwitch.FlowAction.Attr.Sample);
    }

    public void randomize() {
        actions = FlowActions.randomActions();
        probability = FlowActions.rand.nextInt();
    }

    public int getProbability() {
        return probability;
    }

    public List<FlowAction> getActions() {
        return actions;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        @SuppressWarnings("unchecked")
        FlowActionSample that = (FlowActionSample) o;

        return (probability == that.probability)
            && Objects.equals(this.actions, that.actions);
    }

    @Override
    public int hashCode() {
        return 31 * probability + Objects.hashCode(actions);
    }

    @Override
    public String toString() {
        return "Sample{" +
            "probability=" + probability +
            ", actions=" + actions +
            '}';
    }
}
