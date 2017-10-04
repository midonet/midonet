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
import org.midonet.cluster.models.Commons.Protocol;

@ZoomEnum(clazz = Protocol.class)
public enum RuleProtocol {

    @ZoomEnumValue("TCP")
    TCP("tcp", (byte) Protocol.TCP.getNumber()),

    @ZoomEnumValue("UDP")
    UDP("udp", (byte) Protocol.UDP.getNumber()),

    @ZoomEnumValue("ICMP")
    ICMP("icmp", (byte) Protocol.ICMP.getNumber()),

    @ZoomEnumValue("ICMPV6")
    ICMPv6("icmpv6", (byte) Protocol.ICMPV6.getNumber()),

    @ZoomEnumValue("AH")
    AH("ah", (byte) Protocol.AH.getNumber()),

    @ZoomEnumValue("DCCP")
    DCCP("dccp", (byte) Protocol.DCCP.getNumber()),

    @ZoomEnumValue("EGP")
    EGP("egp",(byte) Protocol.EGP.getNumber()),

    @ZoomEnumValue("ESP")
    ESP("esp",(byte) Protocol.ESP.getNumber()),

    @ZoomEnumValue("GRE")
    GRE("gre",(byte) Protocol.GRE.getNumber()),

    @ZoomEnumValue("IGMP")
    IGMP("igmp",(byte) Protocol.IGMP.getNumber()),

    @ZoomEnumValue("IPV6_ENCAP")
    IPV6_ENCAP("ipv6_encap",(byte) Protocol.IPV6_ENCAP.getNumber()),

    @ZoomEnumValue("IPV6_FRAG")
    IPV6_FRAG("ipv6_frag",(byte) Protocol.IPV6_FRAG.getNumber()),

    @ZoomEnumValue("IPV6_NONXT")
    IPV6_NONXT("ipv6_nonxt",(byte) Protocol.IPV6_NONXT.getNumber()),

    @ZoomEnumValue("IPV6_OPTS")
    IPV6_OPTS("ipv6_opts",(byte) Protocol.IPV6_OPTS.getNumber()),

    @ZoomEnumValue("IPV6_ROUTE")
    IPV6_ROUTE("ipv6_route",(byte) Protocol.IPV6_ROUTE.getNumber()),

    @ZoomEnumValue("OSPF")
    OSPF("ospf",(byte) Protocol.OSPF.getNumber()),

    @ZoomEnumValue("PGM")
    PGM("pgm",(byte) Protocol.PGM.getNumber()),

    @ZoomEnumValue("RSVP")
    RSVP("rsvp",(byte) Protocol.RSVP.getNumber()),

    @ZoomEnumValue("SCTP")
    SCTP("sctp",(byte) Protocol.SCTP.getNumber()),

    @ZoomEnumValue("UDPLITE")
    UDPLITE("udplite",(byte) Protocol.UDPLITE.getNumber()),

    @ZoomEnumValue("VRRP")
    VRRP("vrrp",(byte) Protocol.VRRP.getNumber());

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
