/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.util.netlink.dp;

import java.util.ArrayList;
import java.util.List;

import com.midokura.util.netlink.dp.flows.FlowKey;

/**
* // TODO: mtoader ! Please explain yourself.
*/
public class FlowMatch {
    List<FlowKey> keys;

    public FlowMatch() {
        keys = new ArrayList<FlowKey>();
    }

    public FlowMatch(List<FlowKey> keys_) {
        keys = keys_;
    }

    public FlowMatch addKey(FlowKey key) {
        keys.add(key);
        return this;
    }

    public List<FlowKey> getKeys() {
        return keys;
    }

    public FlowMatch setKeys(List<FlowKey> keys) {
        this.keys = keys;
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        FlowMatch flowMatch = (FlowMatch) o;

        if (keys == null ? flowMatch.keys != null
                         : !keys.equals(flowMatch.keys))
            return false;

        return true;
    }

    @Override
    public int hashCode() {
        return keys != null ? keys.hashCode() : 0;
    }

    @Override
    public String toString() {
        return "FlowMatch{" +
            "keys=" + keys +
            '}';
    }
}
