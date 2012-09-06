/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.sdn.flows;

import java.util.ArrayList;
import java.util.List;

import com.midokura.sdn.dp.flows.FlowAction;

public class WildcardFlow {
    short priority = 0; // used to choose among many matching flows
    WildcardMatch match;
    List<FlowAction<?>> actions;

    long creationTimeMillis;
    long lastUsedTimeMillis;

    long hardExpirationMillis = 0; // default: never expire
    long idleExpirationMillis = 0; // default: never expire

    public short getPriority() {
        return priority;
    }

    public WildcardFlow setPriority(short priority) {
        this.priority = priority;
        return this;
    }

    public WildcardMatch getMatch() {
        return match;
    }

    public WildcardFlow setMatch(WildcardMatch match) {
        this.match = match;
        return this;
    }

    public List<FlowAction<?>> getActions() {
        return actions;
    }

    public WildcardFlow setActions(List<FlowAction<?>> actions) {
        this.actions = actions;
        return this;
    }

    public WildcardFlow addAction(FlowAction<?> action) {
        if (actions == null)
            actions = new ArrayList<FlowAction<?>>();
        actions.add(action);
        return this;
    }

    public long getLastUsedTimeMillis() {
        return lastUsedTimeMillis;
    }

    public WildcardFlow setLastUsedTimeMillis(long lastUsedTimeMillis) {
        this.lastUsedTimeMillis = lastUsedTimeMillis;
        return this;
    }

    public long getHardExpirationMillis() {
        return hardExpirationMillis;
    }

    public WildcardFlow setHardExpirationMillis(long hardExpirationMillis) {
        this.hardExpirationMillis = hardExpirationMillis;
        return this;
    }

    public long getIdleExpirationMillis() {
        return idleExpirationMillis;
    }

    public WildcardFlow setIdleExpirationMillis(long idleExpirationMillis) {
        this.idleExpirationMillis = idleExpirationMillis;
        return this;
    }

    public long getCreationTimeMillis() {
        return creationTimeMillis;
    }

    public WildcardFlow setCreationTimeMillis(long creationTimeMillis) {
        this.creationTimeMillis = creationTimeMillis;
        return this;
    }
}
