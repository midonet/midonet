/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.openvswitch;

public class MockOpenvSwitchBridgeBuilder implements BridgeBuilder{
    String name;
    MockOpenvSwitchDatabaseConnection databaseConnection;

    public MockOpenvSwitchBridgeBuilder(String name, MockOpenvSwitchDatabaseConnection mockOpenvSwitchDatabaseConnection) {
        this.name = name;
        this.databaseConnection = mockOpenvSwitchDatabaseConnection;
    }

    @Override
    public BridgeBuilder externalId(String key, String value) {
        MockOpenvSwitchBridge bridge = databaseConnection.getBridge(key);
        bridge.addExternalId(key, value);
        return this;
    }

    @Override
    public BridgeBuilder datapathId(long datapathId) {
        return null;
    }

    @Override
    public BridgeBuilder otherConfig(String key, String value) {
        return null;
    }

    @Override
    public BridgeBuilder failMode(BridgeFailMode failMode) {
        return null;
    }

    @Override
    public void build() {
        databaseConnection.setBridge(name);
    }
}
