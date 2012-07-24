/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.sdn.flows;

import java.util.Set;

import com.sun.tools.javac.util.Pair;

import com.midokura.sdn.dp.Flow;
import com.midokura.sdn.dp.FlowMatch;
import com.midokura.sdn.dp.Packet;

// not thread-safe
public class NetlinkFlowTable {

    public Pair<Set<WildcardFlow>, Set<FlowMatch>>
    add(WildcardFlow wFlow, Flow nlFlow) {
        return null;
    }

    public Set<Flow> removeByWildcard(WildcardFlow wFlow) {
        return null;
    }

    public Flow get(Packet pkt) {
        return null;
    }
}
