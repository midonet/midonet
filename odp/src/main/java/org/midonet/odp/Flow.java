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
package org.midonet.odp;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.midonet.netlink.NetlinkMessage;
import org.midonet.netlink.Reader;
import org.midonet.odp.OpenVSwitch.Flow.Attr;
import org.midonet.odp.flows.FlowAction;
import org.midonet.odp.flows.FlowActions;
import org.midonet.odp.flows.FlowKey;
import org.midonet.odp.flows.FlowKeys;
import org.midonet.odp.flows.FlowStats;
import org.midonet.packets.TCP;

/**
 * An abstraction over the OpenVSwitch kernel datapath flow object.
 */
public class Flow extends FlowMetadata {

    private FlowMatch match;
    private FlowMask mask = new FlowMask();
    private List<FlowAction> actions;

    public Flow() { }

    public Flow(FlowMatch match) {
        this(match, new ArrayList<FlowAction>(0), new FlowStats());
    }

    public Flow(FlowMatch match, List<FlowAction> actions) {
        this(match, actions, new FlowStats());
    }

    public Flow(FlowMatch match, List<FlowAction> actions, FlowStats stats) {
        super(stats);
        this.match = match;
        this.actions = actions;
        mask.calculateFor(match);
    }

    @Nullable
    public FlowMatch getMatch() {
        return match;
    }

    public boolean hasEmptyMatch() {
        return (match == null || match.getKeys().isEmpty());
    }

    public FlowMask getMask() {
        return mask;
    }

    @Nonnull
    public List<FlowAction> getActions() {
        return actions;
    }

    public void deserialize(ByteBuffer buf) {
        buf.getInt(); // read datapath index;
        NetlinkMessage.scanAttributes(buf, this);
    }

    /** Static stateless deserializer which builds one Flow instance and
     *  consumes data from the given ByteBuffer. */
    public static final Reader<Flow> deserializer = new Reader<Flow>() {
        public Flow deserializeFrom(ByteBuffer buf) {
            if (buf == null)
                return null;
            return Flow.buildFrom(buf);
        }
    };

    public static Flow buildFrom(ByteBuffer buf) {
        Flow flow = new Flow();
        flow.deserialize(buf);
        return flow;
    }

    public void use(ByteBuffer buf, short id) {
        switch(NetlinkMessage.unnest(id)) {
            case Attr.Actions:
                actions = FlowActions.reader.deserializeFrom(buf);
                break;

            case Attr.Key:
                ArrayList<FlowKey> keys = new ArrayList<>();
                FlowKeys.buildFrom(buf, keys);
                match = new FlowMatch(keys);
                break;

            case Attr.Mask:
                mask = FlowMask.reader.deserializeFrom(buf);
                break;

            default:
                super.use(buf, id);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean equals(Object o) {
        if (!super.equals(o)) return false;
        Flow that = (Flow) o;
        return Objects.equals(that.match, this.match)
            && Objects.equals(that.actions, this.actions);
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 31 * result + Objects.hashCode(match);
        result = 31 * result + Objects.hashCode(actions);
        return result;
    }

    @Override
    public String toString() {
        return "Flow{" +
            "match=" + match +
            ", actions=" + actions +
            ", mask=" + mask +
            ", metadata=" + super.toString() +
            "}";
    }

    public List<String> toPrettyStrings() {
        List<String> desc = new ArrayList<>();
        List<FlowKey> matchKeys = match.getKeys();
        if (matchKeys.isEmpty())
            desc.add("match keys: empty");
        else {
            desc.add("match keys:");
            for (FlowKey key : matchKeys) {
                desc.add("  " + key.toString());
                if (mask != null)
                    desc.add("    mask: " + mask.getMaskFor(key.attrId()).toString());
            }
        }
        if (actions.isEmpty())
            desc.add("actions: empty");
        else {
            desc.add("actions: ");
            for (FlowAction act : actions) desc.add("  " + act.toString());
        }
        if (getStats() != null)
            desc.add("stats: " + getStats());
        desc.add("tcpFlags: " + TCP.Flag.allOfToString(getTcpFlags()));
        desc.add("lastUsedTime: " + getLastUsedMillis());
        return  desc;
    }
}
