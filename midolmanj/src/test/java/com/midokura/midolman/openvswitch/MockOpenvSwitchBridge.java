/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.openvswitch;

import java.util.HashMap;
import java.util.Map;

public class MockOpenvSwitchBridge {
    String name;
    Map<String, MockOpenvSwitchPort> ports = new HashMap<String, MockOpenvSwitchPort>();
    Map<String, String> externalIds = new HashMap<String, String>();

    public MockOpenvSwitchBridge(String name) {
        this.name = name;
    }

    public void addExternalId(String key, String value) {
        externalIds.put(key, value);
    }

    public void setPort(String portName, MockOpenvSwitchPort.Type type) {
        ports.put(portName, new MockOpenvSwitchPort(portName, type));
    }

    public boolean hasPort(String portName) {
        return ports.containsKey(portName);
    }
}
