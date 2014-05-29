/*
 * Copyright (c) 2012 Midokura SARL, All Rights Reserved.
 */
package org.midonet.odp.flows;

import java.nio.ByteOrder;

import org.midonet.netlink.NetlinkMessage;
import org.midonet.netlink.messages.Builder;

public class FlowKeyEtherType implements CachedFlowKey {

    public enum Type {
        /**
         * Used for frames that have no Ethernet
         * type, that is, pure 802.2 frames.
         */
        ETH_P_NONE(0x05FF),

        /**
         * Internet Protocol packet
         */
        ETH_P_IP(0x0800),

        /**
         * Address Resolution packet
         */
        ETH_P_ARP(0x0806),

        /**
         * IPv6 over bluebook
         */
        ETH_P_IPV6(0x86DD),

        /**
         * 802.1Q VLAN Extended Header
         */
        ETH_P_8021Q(0x8100);

        int value;
        Type(int value) { this.value = value; }
    }

    /* be16 */ private short etherType;

    // This is used for deserialization purposes only.
    FlowKeyEtherType() { }

    FlowKeyEtherType(short etherType) {
        this.etherType = etherType;
    }

    @Override
    public void serialize(Builder builder) {
        builder.addValue(etherType, ByteOrder.BIG_ENDIAN);
    }

    @Override
    public boolean deserialize(NetlinkMessage message) {
        try {
            etherType = message.getShort(ByteOrder.BIG_ENDIAN);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public NetlinkMessage.AttrKey<FlowKeyEtherType> getKey() {
        return FlowKeyAttr.ETHERTYPE;
    }

    @Override
    public FlowKeyEtherType getValue() {
        return this;
    }

    public short getEtherType() {
        return etherType;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        FlowKeyEtherType that = (FlowKeyEtherType) o;

        if (etherType != that.etherType) return false;

        return true;
    }

    @Override
    public int hashCode() {
        return etherType;
    }

    @Override
    public String toString() {
        return "FlowKeyEtherType{etherType=0x"
               + Integer.toHexString(etherType) + "}";
    }
}
