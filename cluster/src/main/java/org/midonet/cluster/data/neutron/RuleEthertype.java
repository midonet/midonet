/*
* Copyright (c) 2014 Midokura SARL, All Rights Reserved.
*/
package org.midonet.cluster.data.neutron;

import org.codehaus.jackson.annotate.JsonCreator;
import org.codehaus.jackson.annotate.JsonValue;
import org.midonet.packets.ARP;
import org.midonet.packets.IPv4;
import org.midonet.packets.IPv6;

public enum RuleEthertype {

    ARP("arp", org.midonet.packets.ARP.ETHERTYPE),
    IPv4("ipv4", org.midonet.packets.IPv4.ETHERTYPE),
    IPv6("ipv6", org.midonet.packets.IPv6.ETHERTYPE);

    private final String value;
    private final int number;

    private RuleEthertype(final String value, final int number) {
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
