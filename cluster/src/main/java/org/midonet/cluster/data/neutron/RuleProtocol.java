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

public enum RuleProtocol {

    TCP("tcp", org.midonet.packets.TCP.PROTOCOL_NUMBER),
    UDP("udp", org.midonet.packets.UDP.PROTOCOL_NUMBER),
    ICMP("icmp", org.midonet.packets.ICMP.PROTOCOL_NUMBER),
    ICMPv6("icmpv6", org.midonet.packets.ICMPv6.PROTOCOL_NUMBER);

    private final String value;
    private final byte number;

    private RuleProtocol(final String value, final byte number) {
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

    @JsonCreator
    public static RuleProtocol forValue(String v) {
        if (v == null) return null;
        for (RuleProtocol protocol : RuleProtocol.values()) {
            if (v.equalsIgnoreCase(protocol.value)) {
                return protocol;
            }
        }

        return null;
    }
}
