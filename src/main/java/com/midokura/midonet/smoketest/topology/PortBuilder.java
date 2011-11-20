/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.smoketest.topology;

public class PortBuilder {

    public enum PortType {
        INTERNAL, TAP, VM
    }

    public PortBuilder setType(PortType tap) {
        return null;
    }

    public PortBuilder setDestination(String string) {
        return null;
    }

    public PortBuilder setNetworkLength(String string) {
        return null;
    }

    public PortBuilder setLocalNetworkLength(String string) {
        return null;
    }

    public TapPort buildTap() {
        return null;
    }

    public InternalPort buildInternal() {
        return null;
    }

    public VMPort buildVM() {
        return null;
    }
}
