/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.sdn.flows;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.midokura.sdn.dp.FlowMatch;
import com.midokura.sdn.dp.flows.FlowAction;
import com.midokura.sdn.dp.flows.FlowKey;

public class WildcardFlow {
    short priority; // used to choose among many matching flows
    Map<FlowKey.FlowKeyAttr<?>, FlowKey<?>> match =
            new HashMap<FlowKey.FlowKeyAttr<?>, FlowKey<?>>();
    List<FlowAction<?>> actions;

    final long creationTimeMillis;
    long lastUsedTimeMillis;

    long hardExpirationMillis;
    long idleExpirationMillis;

    public WildcardFlow(List<FlowAction<?>> actions, long creationTimeMillis,
                        long hardExpirationMillis, long idleExpirationMillis,
                        Map<FlowKey.FlowKeyAttr<?>, FlowKey<?>> match,
                        short priority) {
        this.actions = actions;
        this.creationTimeMillis = creationTimeMillis;
        this.lastUsedTimeMillis = this.creationTimeMillis;
        this.hardExpirationMillis = hardExpirationMillis;
        this.idleExpirationMillis = idleExpirationMillis;
        this.match = match;
        this.priority = priority;
    }

    public WildcardFlow(List<FlowAction<?>> actions,
                       long hardExpirationMillis, long idleExpirationMillis,
                       Map<FlowKey.FlowKeyAttr<?>, FlowKey<?>> match,
                       short priority) {
        this.actions = actions;
        this.creationTimeMillis = System.currentTimeMillis();
        this.lastUsedTimeMillis = this.creationTimeMillis;
        this.hardExpirationMillis = hardExpirationMillis;
        this.idleExpirationMillis = idleExpirationMillis;
        this.match = match;
        this.priority = priority;
    }

    public List<FlowAction<?>> getActions() {
        return actions;
    }

    public long getCreationTimeMillis() {
        return creationTimeMillis;
    }

    public long getHardExpirationMillis() {
        return hardExpirationMillis;
    }

    public long getIdleExpirationMillis() {
        return idleExpirationMillis;
    }

    public long getLastUsedTimeMillis() {
        return lastUsedTimeMillis;
    }

    public Map<FlowKey.FlowKeyAttr<?>, FlowKey<?>> getMatch() {
        return match;
    }

    public short getPriority() {
        return priority;
    }

    public void setLastUsedTimeMillis(long lastUsedTimeMillis) {
        this.lastUsedTimeMillis = lastUsedTimeMillis;
    }
}
