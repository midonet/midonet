/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midonet.cluster.data;

import java.util.HashMap;

import com.midokura.midolman.state.zkManagers.BridgeZkManager;

/**
 * // TODO: mtoader ! Please explain yourself.
 */
public class Bridges {
    public static BridgeZkManager.BridgeConfig toBridgeZkConfig(Bridge bridge) {
        BridgeZkManager.BridgeConfig bridgeConfig = new BridgeZkManager.BridgeConfig();

        bridgeConfig.name = bridge.getData().name;
        bridgeConfig.inboundFilter = bridge.getData().inboundFilter;
        bridgeConfig.outboundFilter = bridge.getData().outboundFilter;
        bridgeConfig.greKey = bridge.getData().greKey;
        bridgeConfig.properties = new HashMap<String, String>(
            bridge.getData().properties);

        return bridgeConfig;
    }

    public static Bridge fromZkBridgeConfig(BridgeZkManager.BridgeConfig bridge) {
        if (bridge == null)
            return null;

        return new Bridge()
            .setName(bridge.name)
            .setGreKey(bridge.greKey)
            .setInboundFilter(bridge.inboundFilter)
            .setOutboundFilter(bridge.outboundFilter)
            .setProperties(bridge.properties);
    }
}
