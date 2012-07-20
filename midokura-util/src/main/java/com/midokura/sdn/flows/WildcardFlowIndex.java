/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.sdn.flows;

import java.util.UUID;
import java.util.Set;

// This class is thread-safe.
public class WildcardFlowIndex {

    public synchronized void add(WildcardFlow flow, Set<UUID> ids) { }

    public synchronized void remove(WildcardFlow flow) {}

    public synchronized Set<WildcardFlow> getFlows(UUID id) {
        return null;
    }

    // Older by creation time.
    public synchronized Set<WildcardFlow> getFlowsOlderThan(long timestamp) {
        return null;
    }

    public synchronized Set<WildcardFlow>
    getFlowsOlderThan(long timestamp, UUID id) {
        return null;
    }
}
