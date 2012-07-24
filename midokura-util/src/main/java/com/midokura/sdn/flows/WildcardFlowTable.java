/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.sdn.flows;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.midokura.sdn.dp.Packet;
import com.midokura.sdn.dp.flows.FlowKey;

// Not thread-safe.
public class WildcardFlowTable {
    private static final int MAX_SIZE = 10000;

    private int size = 0;
    private int maxSize = MAX_SIZE;
    private Map<WildcardSpec, WildcardFlowTable> tables =
            new HashMap<WildcardSpec, WildcardFlowTable>();

    public WildcardFlow matchPacket(Packet pkt) {
        return null;
    }

    // returns evicted flows
    public Set<WildcardFlow> add(WildcardFlow flow) {
        return null;
    }

    public void remove(WildcardFlow flow) {}

    public void markUsed(WildcardFlow flow) {}

    public void markUnused(Set<WildcardFlow> flows) {}

    public static class Table {
        private WildcardSpec spec;
        Map<List<FlowKey>, WildcardFlow> flows;

    }
}
