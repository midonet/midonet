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
import com.google.common.primitives.Ints;

import org.midonet.cluster.data.ZoomEnum;
import org.midonet.cluster.data.ZoomEnumValue;
import org.midonet.cluster.models.Commons;

@ZoomEnum(clazz = Commons.Protocol.class)
public enum RuleProtocol {

    @ZoomEnumValue("TCP")
    TCP("tcp", org.midonet.packets.TCP.PROTOCOL_NUMBER),

    @ZoomEnumValue("UDP")
    UDP("udp", org.midonet.packets.UDP.PROTOCOL_NUMBER),

    @ZoomEnumValue("ICMP")
    ICMP("icmp", org.midonet.packets.ICMP.PROTOCOL_NUMBER),

    @ZoomEnumValue("ICMPV6")
    ICMPv6("icmpv6", org.midonet.packets.ICMPv6.PROTOCOL_NUMBER);

    private final String value;
    private final byte number;

    RuleProtocol(final String value, final byte number) {
        this.value = value;
        this.number = number;
    }

    @JsonValue
    public String value() {
        return value;
    }

    public byte number() {
        return number;
    }

    private static RuleProtocol forNumValue(byte num) {
        for (RuleProtocol protocol : RuleProtocol.values()) {
            if (num == protocol.number) {
                return protocol;
            }
        }
        return null;
    }

    private static RuleProtocol forStrValue(String s) {
        for (RuleProtocol protocol : RuleProtocol.values()) {
            if (s.equalsIgnoreCase(protocol.value)) {
                return protocol;
            }
        }
        return null;
    }

    @JsonCreator
    public static RuleProtocol forValue(String v) {
        if (v == null) return null;
        Integer num = Ints.tryParse(v);
        return (num != null) ? forNumValue(num.byteValue()) : forStrValue(v);
    }
}
