/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.neutron;

import org.codehaus.jackson.annotate.JsonCreator;
import org.codehaus.jackson.annotate.JsonValue;
import org.midonet.packets.ICMP;
import org.midonet.packets.ICMPv6;
import org.midonet.packets.TCP;
import org.midonet.packets.UDP;

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
