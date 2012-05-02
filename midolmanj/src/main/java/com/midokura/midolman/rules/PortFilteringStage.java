/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midolman.rules;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.ForwardingElement.ForwardInfo;
import com.midokura.midolman.state.ChainZkManager;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.PortConfig;
import com.midokura.midolman.state.StateAccessException;

public class PortFilteringStage {

    private static final Logger log =
            LoggerFactory.getLogger(PortFilteringStage.class);

    // TODO(pino): remove this stub when Abel provides his Chain class.
    public static class Chain {
        public Chain(UUID inboundFilter) {}

        public void process(ForwardInfo fwdInfo) {}
    }

    private Map<UUID, Chain> chainCache = new HashMap<UUID, Chain>();
    private ChainZkManager chainMgr;

    public PortFilteringStage(Directory zkDir, String zkBasePath) {
        chainMgr = new ChainZkManager(zkDir, zkBasePath);
    }

    public void processInbound(ForwardInfo fwdInfo,
            PortConfig portConfig) throws StateAccessException {
        // Get the chain object for the inbound filter.
        Chain inChain = getChain(portConfig.inboundFilter);
        // Call the chain's process method.
        inChain.process(fwdInfo);
    }

    public void processOutbound(ForwardInfo fwdInfo,
            PortConfig portConfig) throws StateAccessException {
        // Get the chain object for the outbound filter.
        Chain inChain = getChain(portConfig.outboundFilter);
        // Call the chain's process method.
        inChain.process(fwdInfo);
    }

    private Chain getChain(UUID inboundFilter) {
        // Check to see if we have this chain already cached.
        Chain ch = chainCache.get(inboundFilter);
        if (null != ch)
            return ch;
        // Create a new Chain object.
        ch = new Chain(inboundFilter);
        chainCache.put(inboundFilter, ch);
        return ch;
    }
}
