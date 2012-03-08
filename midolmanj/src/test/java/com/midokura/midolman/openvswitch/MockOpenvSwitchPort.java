/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.openvswitch;

public class MockOpenvSwitchPort {
    String portName;
    Type type;

    public static enum Type { SYSTEM, TAP, UNKNOWN }
    
    public MockOpenvSwitchPort(String portName, Type type) {
        this.portName = portName;
        this.type = type;
    }
}
