/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.sdn.dp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.annotation.Nonnull;

import com.midokura.netlink.Callback;
import com.midokura.netlink.protos.OvsDatapathConnection;
import com.midokura.sdn.dp.flows.FlowAction;
import com.midokura.sdn.dp.flows.FlowKey;

/**
 * An abstraction over the Ovs kernel datapath Packet entity. Contains an
 * {@link FlowMatch} object and a <code>byte[] data</code> member when triggered
 * via a kernel notification and a set list of {@link FlowAction} actions when
 * sent from userland.
 *
 * @see FlowMatch
 * @see FlowAction
 * @see OvsDatapathConnection#packetsExecute(Datapath, Packet)
 * @see OvsDatapathConnection#datapathsSetNotificationHandler(Datapath, Callback)
 */
public class Packet {

    public enum Reason {
        FlowTableMiss,
        FlowActionUserspace,
    }

    byte[] data;
    @Nonnull FlowMatch match = new FlowMatch();
    List<FlowAction<?>> actions;
    Long userData;
    Reason reason;

    public byte[] getData() {
        return data;
    }

    public Packet setData(byte[] data) {
        this.data = data;
        return this;
    }

    @Nonnull
    public FlowMatch getMatch() {
        return match;
    }

    public Packet setMatch(@Nonnull FlowMatch match) {
        this.match = match;
        return this;
    }

    public Packet addKey(FlowKey key) {
        match.addKey(key);
        return this;
    }

    public List<FlowAction<?>> getActions() {
        return actions;
    }

    public Packet setActions(List<FlowAction<?>> actions) {
        // actions may be null on deserialization
        if (actions == null)
            return this;

        // We received an immutable scala List
        // Our list needs to be modifiable
        if (this.actions == null) {
            this.actions = new ArrayList<FlowAction<?>>();
        } else {
            this.actions.clear();
        }

        for(FlowAction<?> action : actions) {
            this.actions.add(action);
        }
        return this;
    }

    public Packet addAction(FlowAction<?> action) {
        if (this.actions == null)
            this.actions = new ArrayList<FlowAction<?>>();

        this.actions.add(action);
        return this;
    }

    public Packet removeAction(FlowAction<?> action) {
        if (this.actions != null) {
            if (this.actions.contains(action)) {
                this.actions.remove(action);
            }
        }

        return this;
    }

    public Long getUserData() {
        return userData;
    }

    public Packet setUserData(Long userData) {
        this.userData = userData;
        return this;
    }

    public Reason getReason() {
        return reason;
    }

    public Packet setReason(Reason reason) {
        this.reason = reason;
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Packet packet = (Packet) o;

        if (actions != null ? !actions.equals(
            packet.actions) : packet.actions != null) return false;
        if (!Arrays.equals(data, packet.data)) return false;
        if (match != null ? !match.equals(packet.match) : packet.match != null)
            return false;
        if (reason != packet.reason) return false;
        if (userData != null ? !userData.equals(
            packet.userData) : packet.userData != null) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = data != null ? Arrays.hashCode(data) : 0;
        result = 31 * result + (match != null ? match.hashCode() : 0);
        result = 31 * result + (actions != null ? actions.hashCode() : 0);
        result = 31 * result + (userData != null ? userData.hashCode() : 0);
        result = 31 * result + (reason != null ? reason.hashCode() : 0);
        return result;
    }


    @Override
    public String toString() {
        return "Packet{" +
            "data=" + Arrays.toString(data) +
            ", match=" + match +
            ", actions=" + actions +
            ", userData=" + userData +
            ", reason=" + reason +
            '}';
    }
}
