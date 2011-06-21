/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.openvswitch;

/**
 * Open vSwitch switch fail modes, determining the behavior of an OpenFlow
 * switch when not connected to an OpenFlow controller.
 */
public enum BridgeFailMode {

    /**
     * The switch acts as an Ethernet learning bridge.
     */
    STANDALONE("standalone"),

    /**
     * The switch blocks all traffic.
     */
    SECURE("secure"),

    /**
     * The switch's behavior is implementation-specific.
     */
    IMPLEMENTATION_SPECIFIC(null);

    final private String mode;

    BridgeFailMode(String mode) {
        this.mode = mode;
    }

    String getMode() {
        return this.mode;
    }
}
