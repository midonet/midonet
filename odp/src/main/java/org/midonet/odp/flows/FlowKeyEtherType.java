/*
 * Copyright (c) 2012 Midokura SARL, All Rights Reserved.
 */
package org.midonet.odp.flows;

import java.nio.ByteBuffer;

import org.midonet.netlink.BytesUtil;
import org.midonet.odp.OpenVSwitch;

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

        public final int value;
        Type(int value) { this.value = value; }
    }

    /* be16 */ private short etherType;

    // This is used for deserialization purposes only.
    FlowKeyEtherType() { }

    FlowKeyEtherType(short etherType) {
        this.etherType = etherType;
    }

    public int serializeInto(ByteBuffer buffer) {
        buffer.putShort(BytesUtil.instance.reverseBE(etherType));
        return 2;
    }

    public void deserializeFrom(ByteBuffer buf) {
        etherType = BytesUtil.instance.reverseBE(buf.getShort());
    }

    public short attrId() {
        return OpenVSwitch.FlowKey.Attr.Ethertype;
    }

    public short getEtherType() {
        return etherType;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        @SuppressWarnings("unchecked")
        FlowKeyEtherType that = (FlowKeyEtherType) o;

        return etherType == that.etherType;
    }

    @Override
    public int hashCode() {
        return etherType;
    }

    @Override
    public int connectionHash() { return 0; }

    @Override
    public String toString() {
        return "FlowKeyEtherType{etherType=0x"
               + Integer.toHexString(etherType) + "}";
    }
}
