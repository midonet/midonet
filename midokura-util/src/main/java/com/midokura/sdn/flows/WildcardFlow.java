/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.sdn.flows;

import java.util.List;

import com.midokura.util.netlink.dp.FlowMatch;
import com.midokura.util.netlink.dp.flows.FlowAction;

public class WildcardFlow {
    private WildcardSpec spec;
    private short priority; // used to choose among many matching flows

    // Any field of a flowKey that is wildcarded in the spec should be set to
    // zero.
    public FlowMatch match;
    public List<FlowAction> actions;

    private long creationTime = System.currentTimeMillis();
    private long lastUsedTime = creationTime;

    public long getLastUsedTime() {
        return lastUsedTime;
    }

    public long getCreationTime() {
        return creationTime;
    }

    public void updateLastUsedTime(long timestamp) {
        if (timestamp > lastUsedTime)
            lastUsedTime = timestamp;
    }
}
