/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
