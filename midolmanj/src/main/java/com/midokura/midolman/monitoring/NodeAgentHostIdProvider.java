/*
 * Copyright (c) 2012 Midokura Pte.Ltd.
 */

package com.midokura.midolman.monitoring;

import java.util.UUID;

import com.midokura.midolman.agent.NodeAgent;

/**
 * Date: 6/1/12
 */
public class NodeAgentHostIdProvider implements HostIdProvider {

    NodeAgent agent;

    public NodeAgentHostIdProvider(NodeAgent agent) {
        this.agent = agent;
    }

    @Override
    public UUID getHostId() {
        return agent.getHostId();
    }
}
