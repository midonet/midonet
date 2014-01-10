/*
 * Copyright (c) 2012 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.odp.flows;

import java.nio.ByteOrder;

import org.midonet.netlink.NetlinkMessage;
import org.midonet.netlink.messages.BaseBuilder;

public class FlowKeyTunnelID implements FlowKey<FlowKeyTunnelID> {

    /* be64 */ private long tunnelID;

    // This is used for deserialization purposes only.
    FlowKeyTunnelID() { }

    FlowKeyTunnelID(long tunnelID) {
        this.tunnelID = tunnelID;
    }

    @Override
    public void serialize(BaseBuilder<?,?> builder) {
        builder.addValue(tunnelID, ByteOrder.BIG_ENDIAN);
    }

    @Override
    public boolean deserialize(NetlinkMessage message) {
        try {
            tunnelID = message.getLong(ByteOrder.BIG_ENDIAN);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public NetlinkMessage.AttrKey<FlowKeyTunnelID> getKey() {
        return FlowKeyAttr.TUN_ID;
    }

    @Override
    public FlowKeyTunnelID getValue() {
        return this;
    }

    public long getTunnelID() {
        return tunnelID;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        FlowKeyTunnelID that = (FlowKeyTunnelID) o;

        if (tunnelID != that.tunnelID) return false;

        return true;
    }

    @Override
    public int hashCode() {
        return (int) (tunnelID ^ (tunnelID >>> 32));
    }

    @Override
    public String toString() {
        return "FlowKeyTunnelID{" +
            "tunnelID=" + tunnelID +
            '}';
    }
}
