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
package org.midonet.cluster.rest_api.neutron.models;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import org.midonet.cluster.data.ZoomEnum;
import org.midonet.cluster.data.ZoomEnumValue;
import org.midonet.cluster.models.Commons;

@ZoomEnum(clazz = Commons.EtherType.class)
public enum RuleEthertype {

    @ZoomEnumValue("ARP")
    ARP("arp", org.midonet.packets.ARP.ETHERTYPE),

    @ZoomEnumValue("IPV4")
    IPv4("ipv4", org.midonet.packets.IPv4.ETHERTYPE),

    @ZoomEnumValue("IPV6")
    IPv6("ipv6", org.midonet.packets.IPv6.ETHERTYPE);

    private final String value;
    private final int number;

    RuleEthertype(final String value, final int number) {
        this.value = value;
        this.number = number;
    }

    @JsonValue
    public String value() {
        return value;
    }

    public int number() {
        return number;
    }

    @JsonCreator
    public static RuleEthertype forValue(String v) {
        if (v == null) return null;
        for (RuleEthertype type : RuleEthertype.values()) {
            if (v.equalsIgnoreCase(type.value)) {
                return type;
            }
        }
        return null;
    }
}
