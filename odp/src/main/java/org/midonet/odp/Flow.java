/*
 * Copyright (c) 2012 Midokura SARL, All Rights Reserved.
 */
package org.midonet.odp;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.common.base.Function;

import org.midonet.netlink.NetlinkMessage;
import org.midonet.odp.OpenVSwitch.Flow.Attr;
import org.midonet.odp.family.FlowFamily;
import org.midonet.odp.flows.FlowAction;
import org.midonet.odp.flows.FlowActions;
import org.midonet.odp.flows.FlowKey;
import org.midonet.odp.flows.FlowStats;
import org.midonet.packets.TCP;

/**
 * An abstraction over the OpenVSwitch kernel datapath flow object.
 */
public class Flow {

    FlowMatch match;
    List<FlowAction> actions = new ArrayList<>();
    FlowStats stats;
    Byte tcpFlags;
    Long lastUsedTime;

    public Flow() {
    }

    @Nullable
    public FlowMatch getMatch() {
        return match;
    }

    public Flow setMatch(FlowMatch match) {
        this.match = match;
        return this;
    }

    @Nonnull
    public List<FlowAction> getActions() {
        return actions;
    }

    public Flow setActions(List<FlowAction> actions) {
        this.actions = actions;
        return this;
    }

    public FlowStats getStats() {
        return stats;
    }

    public Flow setStats(FlowStats stats) {
        this.stats = stats;
        return this;
    }

    public Byte getTcpFlags() {
        return tcpFlags;
    }

    public Flow setTcpFlags(Byte tcpFlags) {
        this.tcpFlags = tcpFlags;
        return this;
    }

    public Long getLastUsedTime() {
        return lastUsedTime;
    }

    public Flow setLastUsedTime(Long lastUsedTime) {
        this.lastUsedTime = lastUsedTime;
        return this;
    }

    public Flow addAction(FlowAction action) {
        actions.add(action);
        return this;
    }

    public Flow addKey(FlowKey key) {
        if  (match == null) {
            match = new FlowMatch();
        }

        match.addKey(key);
        return this;
    }

    public static Flow buildFrom(ByteBuffer buf) {
        int actualDpIndex = buf.getInt(); // read datapath index;
        Flow flow = new Flow();
        flow.setStats(FlowStats.buildFrom(buf));
        flow.setTcpFlags(NetlinkMessage.getAttrValueByte(buf, Attr.TCPFlags));
        flow.setLastUsedTime(NetlinkMessage.getAttrValueLong(buf, Attr.Used));
        flow.setActions(FlowActions.buildFrom(buf));
        flow.setMatch(FlowMatch.buildFrom(buf));
        return flow;
    }

    /** Static stateless deserializer which builds one Flow instance. Only
     *  consumes the head ByteBuffer in the given input List. */
    public static final Function<List<ByteBuffer>, Flow> deserializer =
        new Function<List<ByteBuffer>, Flow>() {
            @Override
            public Flow apply(List<ByteBuffer> input) {
                if (input == null || input.isEmpty() || input.get(0) == null)
                    return null;
                return Flow.buildFrom(input.get(0));
            }
        };

    /** Static stateless deserializer which builds a set of Flow instance.
     *  Consumes all the ByteBuffers contained in the given input List. */
    public static final Function<List<ByteBuffer>, Set<Flow>> setDeserializer =
        new Function<List<ByteBuffer>, Set<Flow>>() {
            @Override
            public Set<Flow> apply(List<ByteBuffer> input) {
                Set<Flow> flows = new HashSet<>();
                if (input != null) {
                  for (ByteBuffer buffer : input) {
                      flows.add(Flow.buildFrom(buffer));
                  }
                }
                return flows;
            }
        };

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Flow flow = (Flow) o;

        if (actions != null ? !actions.equals(
            flow.actions) : flow.actions != null)
            return false;
        if (match != null ? !match.equals(flow.match) : flow.match != null)
            return false;
        if (stats != null ? !stats.equals(flow.stats) : flow.stats != null)
            return false;
        if (tcpFlags != null ? !tcpFlags.equals(
            flow.tcpFlags) : flow.tcpFlags != null) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = match != null ? match.hashCode() : 0;
        result = 31 * result + (actions != null ? actions.hashCode() : 0);
        result = 31 * result + (stats != null ? stats.hashCode() : 0);
        result = 31 * result + (tcpFlags != null ? tcpFlags.hashCode() : 0);
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
            '}';
    }

    private String formatTCPFlags() {
        StringBuilder buf = new StringBuilder();
        for (TCP.Flag f : TCP.Flag.allOf(tcpFlags.intValue())) {
            buf.append(f).append(" | ");
        }
        return buf.substring(0, buf.length() - 3);
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
            desc.add("tcpFlags: " + formatTCPFlags());
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
        addKeys(buf,keys);
        buf.flip();
        return buf;
    }

    /** Prepares an ovs request to describe a single flow (flow match and flow
     *  actions). Used with flow set and flow create generic netlink commands
     *  of the flow family. */
    public static ByteBuffer describeOneRequest(ByteBuffer buf, int datapathId,
                                                Iterable<FlowKey> keys,
                                                Iterable<FlowAction> actions) {
        buf.putInt(datapathId);
        addKeys(buf,keys);

        // the actions list is allowed to be empty (drop flow). Nevertheless the
        // actions nested attribute header needs to be written otherwise the
        // datapath will answer back with EINVAL
        NetlinkMessage.writeAttrSeq(buf, Attr.Actions, actions,
                                    FlowAction.translator);

        buf.flip();
        return buf;
    }

    public static void addKeys(ByteBuffer buf, Iterable<FlowKey> keys) {
        NetlinkMessage.writeAttrSeq(buf, Attr.Key, keys,
                                    FlowKey.translator);
    }
}
