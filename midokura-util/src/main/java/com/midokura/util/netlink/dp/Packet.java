/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.util.netlink.dp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.midokura.util.netlink.dp.flows.FlowAction;
import com.midokura.util.netlink.dp.flows.FlowKey;

/**
 * // TODO: mtoader ! Please explain yourself.
 */
public class Packet {

    byte[] data;
    List<FlowKey> keys;
    List<FlowAction> actions;
    Long userData;

    public byte[] getData() {
        return data;
    }

    public Packet setData(byte[] data) {
        this.data = data;
        return this;
    }

    public List<FlowKey> getKeys() {
        return keys;
    }

    public Packet setKeys(List<FlowKey> keys) {
        this.keys = keys;
        return this;
    }

    public Packet addKey(FlowKey key) {
        if (this.keys == null)
            this.keys = new ArrayList<FlowKey>();

        this.keys.add(key);
        return this;
    }

    public List<FlowAction> getActions() {
        return actions;
    }

    public Packet setActions(List<FlowAction> actions) {
        this.actions = actions;
        return this;
    }

    public Packet addAction(FlowAction action) {
        if (this.actions == null)
            this.actions = new ArrayList<FlowAction>();

        this.actions.add(action);
        return this;
    }

    public Long getUserData() {
        return userData;
    }

    public Packet setUserData(Long userData) {
        this.userData = userData;
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
        if (keys != null ? !keys.equals(packet.keys) : packet.keys != null)
            return false;
        if (userData != null ? !userData.equals(
            packet.userData) : packet.userData != null) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = data != null ? Arrays.hashCode(data) : 0;
        result = 31 * result + (keys != null ? keys.hashCode() : 0);
        result = 31 * result + (actions != null ? actions.hashCode() : 0);
        result = 31 * result + (userData != null ? userData.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return "Packet{" +
            "data=" + Arrays.toString(data) +
            ", keys=" + keys +
            ", actions=" + actions +
            ", userData=" + userData +
            '}';
    }
}
