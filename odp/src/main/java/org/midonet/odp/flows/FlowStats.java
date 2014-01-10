/*
 * Copyright (c) 2012 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.odp.flows;

import org.midonet.netlink.NetlinkMessage;
import org.midonet.netlink.messages.BaseBuilder;
import org.midonet.netlink.messages.BuilderAware;
import org.midonet.odp.family.FlowFamily;

public class FlowStats implements BuilderAware {

    /** Number of matched packets. */
    /*__u64*/ private long n_packets;

    /** Number of matched bytes. */
    /*__u64*/ private long n_bytes;

    // This is used for deserialization purposes only.
    FlowStats() { }

    public FlowStats(long numPackets, long numBytes) {
        n_packets = numPackets;
        n_bytes = numBytes;
    }

    @Override
    public void serialize(BaseBuilder<?,?> builder) {
        builder.addValue(n_packets);
        builder.addValue(n_bytes);
    }

    @Override
    public boolean deserialize(NetlinkMessage message) {
        try {
            n_packets = message.getLong();
            n_bytes = message.getLong();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public long getNoPackets() {
        return n_packets;
    }

    public long getNoBytes() {
        return n_packets;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        FlowStats flowStats = (FlowStats) o;

        if (n_bytes != flowStats.n_bytes) return false;
        if (n_packets != flowStats.n_packets) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = (int) (n_packets ^ (n_packets >>> 32));
        result = 31 * result + (int) (n_bytes ^ (n_bytes >>> 32));
        return result;
    }

    @Override
    public String toString() {
        return "FlowStats{" +
            "n_packets=" + n_packets +
            ", n_bytes=" + n_bytes +
            '}';
    }

    public static FlowStats buildFrom(NetlinkMessage msg) {
        return msg.getAttrValue(FlowFamily.AttrKey.STATS, new FlowStats());
    }
}
