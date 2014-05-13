/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.neutron;

import org.codehaus.jackson.annotate.JsonCreator;
import org.codehaus.jackson.annotate.JsonValue;

public enum DeviceOwner {

    DHCP("network:dhcp"),
    FLOATINGIP("network:floatingip"),
    ROUTER_GW("network:router_gateway"),
    ROUTER_INTF("network:router_interface");

    private final String value;

    private DeviceOwner(final String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static DeviceOwner forValue(String v) {
        if (v == null) return null;

        for (DeviceOwner deviceOwner : DeviceOwner.values()) {
            if (v.equalsIgnoreCase(deviceOwner.value)) {
                return deviceOwner;
            }
        }

        return null;
    }

    @Override
    public String toString() {
        return value;
    }
}
