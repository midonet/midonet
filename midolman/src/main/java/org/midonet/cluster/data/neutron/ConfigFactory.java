/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.neutron;

import org.midonet.midolman.state.zkManagers.BridgeZkManager.BridgeConfig;

/**
 * Contains factory methods for constructing data objects.
 */
public class ConfigFactory {

    private ConfigFactory() {}

    /**
     * Construct a Bridge object from a Network object.
     *
     * @param network Network object
     * @return Bridge object
     */
    public static BridgeConfig createBridge(Network network) {

        BridgeConfig config = new BridgeConfig();
        config.adminStateUp = network.adminStateUp;
        config.name = network.name;
        return config;
    }

}
