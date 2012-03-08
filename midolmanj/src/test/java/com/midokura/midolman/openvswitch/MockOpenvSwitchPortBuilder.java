/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.openvswitch;

public class MockOpenvSwitchPortBuilder implements PortBuilder {
    long bridgeId;
    String portName;
    String bridgeName;
    MockOpenvSwitchPort.Type type;
    MockOpenvSwitchDatabaseConnection mockOpenvSwitchDatabaseConnection;

    public MockOpenvSwitchPortBuilder(long bridgeId, String portName, MockOpenvSwitchPort.Type type, MockOpenvSwitchDatabaseConnection mockOpenvSwitchDatabaseConnection) {
        this.bridgeId = bridgeId;
        this.portName = portName;
        this.type = type;
        this.mockOpenvSwitchDatabaseConnection = mockOpenvSwitchDatabaseConnection;
    }

    public MockOpenvSwitchPortBuilder(String bridgeName, String portName, MockOpenvSwitchPort.Type type, MockOpenvSwitchDatabaseConnection mockOpenvSwitchDatabaseConnection) {
        this.bridgeName = bridgeName;
        this.portName = portName;
        this.type = type;
        this.mockOpenvSwitchDatabaseConnection = mockOpenvSwitchDatabaseConnection;
    }

    @Override
    public PortBuilder externalId(String key, String value) {
        return null;
    }

    @Override
    public PortBuilder ifMac(String ifMac) {
        return null;
    }

    @Override
    public PortBuilder qos(String qosUUID) {
        return null;
    }

    @Override
    public void build() {
        if (bridgeName == null) {
            mockOpenvSwitchDatabaseConnection.setPort(bridgeId, portName, type);
        } else {
            mockOpenvSwitchDatabaseConnection.setPort(bridgeName, portName, type);
        }
    }
}
