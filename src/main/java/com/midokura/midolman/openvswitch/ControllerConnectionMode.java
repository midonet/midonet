/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.openvswitch;

/**
 * Open vSwitch switch controller connection modes, determining how a
 * controller is contacted over the network.
 */
public enum ControllerConnectionMode {

    /**
     * The OpenFlow connection is transmitted in-band by the switch, i.e. via
     * the datapath.
     */
    IN_BAND("in-band"),

    /**
     * The OpenFlow connection is transmitted out-of-band by the switch, i.e.
     * not via the datapath.
     */
    OUT_OF_BAND("out-of-band"),

    /**
     * The connection mode is implementation-specific.
     */
    IMPLEMENTATION_SPECIFIC(null);

    final private String mode;

    ControllerConnectionMode(String mode) {
        this.mode = mode;
    }

    String getMode() {
        return this.mode;
    }

}
