// Copyright 2012 Midokura Inc.

package com.midokura.util.netlink.dp.flows;

import java.util.List;


public final class FlowMatch {
    public List<FlowKey> keys;

    public FlowMatch(List<FlowKey> keys_) { keys = keys_; }

    @Override
    public boolean equals(Object o) {
        if (o == null || o.getClass() != getClass())
            return false;
        return keys.equals(FlowMatch.class.cast(o).keys);
    }

    @Override
    public int hashCode() {
        return keys.hashCode();
    }
}
