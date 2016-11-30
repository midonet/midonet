/*
 * Copyright 2015 Midokura SARL
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
package org.midonet.cluster.rest_api.neutron.models;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import org.midonet.cluster.data.ZoomEnum;
import org.midonet.cluster.data.ZoomEnumValue;
import org.midonet.cluster.models.Neutron;

@ZoomEnum(clazz = Neutron.NeutronPort.DeviceOwner.class)
public enum DeviceOwner {

    @ZoomEnumValue("DHCP") DHCP("network:dhcp"),
    @ZoomEnumValue("FLOATINGIP") FLOATINGIP("network:floatingip"),
    @ZoomEnumValue("ROUTER_GATEWAY") ROUTER_GW("network:router_gateway"),
    @ZoomEnumValue("ROUTER_INTERFACE") ROUTER_INTF("network:router_interface"),
    @ZoomEnumValue("COMPUTE") COMPUTE("compute:nova"),
    @ZoomEnumValue("LOADBALANCER") LOADBALANCER("neutron:LOADBALANCER"),
    @ZoomEnumValue("REMOTE_SITE") REMOTE_SITE("network:remote_site"),
    @ZoomEnumValue("LOADBALANCERV2") LOADBALANCERV2("neutron:LOADBALANCERV2");

    static public final String COMPUTE_PREFIX = "compute:";

    private final String value;

    DeviceOwner(final String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    @SuppressWarnings("unused")
    public static DeviceOwner forValue(String v) {
        if (v == null) return null;

        if (v.toLowerCase().startsWith(COMPUTE_PREFIX)) {
            return COMPUTE;
        }

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
