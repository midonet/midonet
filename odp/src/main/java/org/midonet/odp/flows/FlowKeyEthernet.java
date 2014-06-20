/*
 * Copyright (c) 2012 Midokura SARL, All Rights Reserved.
 */
package org.midonet.odp.flows;

import java.util.Arrays;
import java.nio.ByteBuffer;

import org.midonet.odp.OpenVSwitch;
import org.midonet.packets.MAC;

public class FlowKeyEthernet implements CachedFlowKey {

    /*__u8*/ private byte[] eth_src = new byte[6]; // always 6 bytes long
    /*__u8*/ private byte[] eth_dst = new byte[6]; // always 6 bytes long

    private int hashCode = 0;

    // This is used for deserialization purposes only.
    FlowKeyEthernet() { }

    FlowKeyEthernet(byte[] src, byte[] dst) {
        eth_src = src;
        eth_dst = dst;
    }

    public int serializeInto(ByteBuffer buffer) {
        buffer.put(eth_src);
        buffer.put(eth_dst);
        return 12;
    }

    public void deserializeFrom(ByteBuffer buf) {
        buf.get(eth_src);
        buf.get(eth_dst);
        hashCode = 0;
    }

    public short attrId() {
        return OpenVSwitch.FlowKey.Attr.Ethernet;
    }

    public byte[] getSrc() {
        return eth_src;
    }

    public byte[] getDst() {
        return eth_dst;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        FlowKeyEthernet that = (FlowKeyEthernet) o;

        if (!Arrays.equals(eth_dst, that.eth_dst)) return false;
        if (!Arrays.equals(eth_src, that.eth_src)) return false;

        return true;
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            int result = eth_src != null ? Arrays.hashCode(eth_src) : 0;
            result = 31 * result + (eth_dst != null ? Arrays.hashCode(eth_dst) : 0);
            hashCode = result;
        }
        return hashCode;
    }

    @Override
    public String toString() {
        return "FlowKeyEthernet{" +
            "eth_src=" +
                (eth_src == null ? "null" : MAC.bytesToString(eth_src)) +
            ", eth_dst=" +
                (eth_dst == null ? "null" : MAC.bytesToString(eth_dst)) +
            '}';
    }
}

