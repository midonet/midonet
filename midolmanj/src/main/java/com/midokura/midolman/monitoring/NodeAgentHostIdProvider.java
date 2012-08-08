/*
 * Copyright (c) 2012 Midokura Pte.Ltd.
 */

package com.midokura.midolman.monitoring;

import java.net.InetAddress;
import java.net.UnknownHostException;

import com.midokura.midolman.host.services.HostAgentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Date: 6/1/12
 */
public class NodeAgentHostIdProvider implements HostIdProvider {

    private final static Logger log =
        LoggerFactory.getLogger(NodeAgentHostIdProvider.class);

    HostAgentService agent;

    public NodeAgentHostIdProvider(HostAgentService agent) {
        this.agent = agent;
    }

    @Override
    public String getHostId() {
        if (agent != null && agent.getHostId() != null) {
            log.info("Returned Id {}", agent.getHostId());
            return agent.getHostId().toString();
        }
        String hostName = "UNKNOWN";
        try {
            hostName = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            log.error("Error when trying to get the host name", e);
        }
        return hostName;
    }
}
