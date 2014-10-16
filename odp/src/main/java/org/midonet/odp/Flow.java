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

import org.midonet.netlink.AttributeHandler;
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
public class Flow implements AttributeHandler {

    private FlowMatch match;
    private FlowMask mask = new FlowMask();
    private List<FlowAction> actions = new ArrayList<>();
    private FlowStats stats;
    private Byte tcpFlags;

    // this field is both used by the ovs kernel and by midolman packet pipeline
    // to track time statistics. Because it may be incoherent for the same flow
    // between ovs and midolman , it is ignored in equals() and hashCode().
    private Long lastUsedTime;

    public Flow() { }

    public Flow(FlowMatch match) {
        this.match = match;
    }

    public Flow(FlowMatch match, FlowMask mask) {
        this(match);
        this.mask = mask;
    }

    public Flow(FlowMatch match, List<FlowAction> actions) {
        this(match);
        this.actions = actions;
    }

    public Flow(FlowMatch match, FlowMask mask, List<FlowAction> actions) {
        this(match, mask);
        this.actions = actions;
    }

    public Flow(FlowMatch match, List<FlowAction> actions, FlowStats stats) {
        this(match, actions);
        this.stats = stats;
    }

    public Flow(FlowMatch match, FlowMask mask, List<FlowAction> actions, FlowStats stats) {
        this(match, mask, actions);
        this.stats = stats;
    }

    @Nullable
    public FlowMatch getMatch() {
        return match;
    }

    public boolean hasEmptyMatch() {
        return (match == null || match.getKeys().isEmpty());
    }

    @Nullable
    public FlowMask getMask() {
        return mask;
    }

    public boolean hasEmptyMask() {
        return (mask == null || mask.getKeys().isEmpty());
    }

    @Nonnull
    public List<FlowAction> getActions() {
        return actions;
    }

    public FlowStats getStats() {
        return stats;
    }

    public Byte getTcpFlags() {
        return tcpFlags;
    }

    public Long getLastUsedTime() {
        return lastUsedTime;
    }

    public Flow setLastUsedTime(Long lastUsedTime) {
        this.lastUsedTime = lastUsedTime;
        return this;
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
        int actualDpIndex = buf.getInt(); // read datapath index;
        Flow flow = new Flow();
        NetlinkMessage.scanAttributes(buf, flow);
        return flow;
    }

    public void use(ByteBuffer buf, short id) {
        switch(NetlinkMessage.unnest(id)) {

          case Attr.Stats:
            stats = FlowStats.buildFrom(buf);
            break;

          case Attr.TCPFlags:
            tcpFlags = buf.get();
            break;

          case Attr.Used:
            lastUsedTime = buf.getLong();
            break;

          case Attr.Actions:
            actions = FlowActions.reader.deserializeFrom(buf);
            break;

          case Attr.Key:
            match = FlowMatch.reader.deserializeFrom(buf);
            break;

          case Attr.Mask:
            mask = FlowMask.reader.deserializeFrom(buf);
            break;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        @SuppressWarnings("unchecked")
        Flow that = (Flow) o;

        return Objects.equals(that.actions, this.actions)
            && Objects.equals(that.match, this.match)
            && Objects.equals(that.stats, this.stats)
            && Objects.equals(that.tcpFlags, this.tcpFlags);
    }

    @Override
    public int hashCode() {
        int result = Objects.hashCode(match);
        result = 31 * result + Objects.hashCode(actions);
        result = 31 * result + Objects.hashCode(stats);
        result = 31 * result + Objects.hashCode(tcpFlags);
        return result;
    }

    @Override
    public String toString() {
        return "Flow{" +
            "match=" + match +
            ", actions=" + actions +
            ", stats=" + stats +
            ", tcpFlags=" + tcpFlags +
            ", lastUsedTime=" + lastUsedTime +
            ", mask=" + mask +
            "}";
    }

    public List<String> toPrettyStrings() {
        List<String> desc = new ArrayList<>();
        List<FlowKey> matchKeys = match.getKeys();
        if (matchKeys.isEmpty())
            desc.add("match keys: empty");
        else {
            desc.add("match keys:");
            for (FlowKey key: matchKeys) desc.add("  " + key.toString());
        }
        if (actions.isEmpty())
            desc.add("actions: empty");
        else {
            desc.add("actions: ");
            for (FlowAction act: actions) desc.add("  " + act.toString());
        }
        if (stats != null)
            desc.add("stats: " + stats);
        if (tcpFlags != null)
            desc.add("tcpFlags: " + TCP.Flag.allOfToString(tcpFlags.intValue()));
        if (lastUsedTime != null)
            desc.add("lastUsedTime: " + lastUsedTime);
        return  desc;
    }

    /** Prepares an ovs request to select all flows in a datapath instance. The
     *  message is empty. Used with flow get (enumerate) and flow del (flush
     *  all) generic netlink commands of the flow family. */
    public static ByteBuffer selectAllRequest(ByteBuffer buf, int datapathId) {
        buf.putInt(datapathId);
        buf.flip();
        return buf;
    }

    /** Prepares an ovs request to select a single flow in a datapath instance
     *  based of the flow match. Used with flow get and flow del generic netlink
     *  commands of the flow family. */
    public static ByteBuffer selectOneRequest(ByteBuffer buf, int datapathId,
                                              Iterable<FlowKey> keys) {
        buf.putInt(datapathId);
        NetlinkMessage.writeAttrSeq(buf, Attr.Key, keys, FlowKeys.writer);
        buf.flip();
        return buf;
    }

    /**
     * Prepares an ovs request to describe a single flow (flow match and flow
     * actions). Used with flow set and flow create generic netlink commands
     * of the flow family.
     */
    public ByteBuffer describeOneRequest(ByteBuffer buf, int datapathId) {
        buf.putInt(datapathId);

        // add the keys
        NetlinkMessage.writeAttrSeq(buf, Attr.Key, match.getKeys(), FlowKeys.writer);

        // add the actions
        // the actions list is allowed to be empty (drop flow). Nevertheless the
        // actions nested attribute header needs to be written otherwise the
        // datapath will answer back with EINVAL
        NetlinkMessage.writeAttrSeq(buf, Attr.Actions, getActions(), FlowActions.writer);

        if (!hasEmptyMask())
            NetlinkMessage.writeAttrSeq(buf, Attr.Mask, mask.getKeys(), FlowKeys.writer);

        buf.flip();
        return buf;
    }

    /**
     * Prepares an ovs request to describe a single flow (flow match and flow
     * actions). Used with flow set and flow create generic netlink commands
     * of the flow family.
     */
    public static ByteBuffer describeOneRequest(ByteBuffer buf, int datapathId,
                                                Iterable<FlowKey> keys,
                                                Iterable<FlowAction> actions) {
        buf.putInt(datapathId);
        NetlinkMessage.writeAttrSeq(buf, Attr.Key, keys, FlowKeys.writer);
        NetlinkMessage.writeAttrSeq(buf, Attr.Actions, actions, FlowActions.writer);
        buf.flip();
        return buf;
    }

    /**
     * Prepares an ovs request to describe a single flow with a mask
     */
    public static ByteBuffer describeOneRequest(ByteBuffer buf, int datapathId,
                                                Iterable<FlowKey> keys,
                                                Iterable<FlowKey> maskKeys,
                                                Iterable<FlowAction> actions) {
        buf.putInt(datapathId);

        // add the keys, the actions and the mask
        NetlinkMessage.writeAttrSeq(buf, Attr.Key, keys, FlowKeys.writer);
        NetlinkMessage.writeAttrSeq(buf, Attr.Actions, actions, FlowActions.writer);
        NetlinkMessage.writeAttrSeq(buf, Attr.Mask, maskKeys, FlowKeys.writer);

        buf.flip();
        return buf;
    }
}
