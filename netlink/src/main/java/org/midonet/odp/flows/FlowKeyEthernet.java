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
package org.midonet.odp.flows;

import java.util.Arrays;
import java.nio.ByteBuffer;

import org.midonet.odp.OpenVSwitch;
import org.midonet.packets.MAC;

public class FlowKeyEthernet implements CachedFlowKey {
    public static int ETH_ALEN = 6;

    /*__u8*/ public byte[] eth_src = new byte[ETH_ALEN];
    /*__u8*/ public byte[] eth_dst = new byte[ETH_ALEN];

    private int hashCode = 0;

    // This is used for deserialization purposes only.
    FlowKeyEthernet() { }

    public FlowKeyEthernet(byte[] src, byte[] dst) {
        eth_src = src;
        eth_dst = dst;
        computeHashCode();
    }

    public int serializeInto(ByteBuffer buffer) {
        buffer.put(eth_src);
        buffer.put(eth_dst);
        return 12;
    }

    public void deserializeFrom(ByteBuffer buf) {
        buf.get(eth_src);
        buf.get(eth_dst);
        computeHashCode();
    }

    @Override
    public void wildcard() {
        Arrays.fill(eth_src, (byte) 0);
        Arrays.fill(eth_dst, (byte) 0);
    }

    public short attrId() {
        return OpenVSwitch.FlowKey.Attr.Ethernet;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        @SuppressWarnings("unchecked")
        FlowKeyEthernet that = (FlowKeyEthernet) o;

        return Arrays.equals(eth_dst, that.eth_dst)
            && Arrays.equals(eth_src, that.eth_src);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    private void computeHashCode() {
        hashCode = 31 * Arrays.hashCode(eth_src) + Arrays.hashCode(eth_dst);
    }

    @Override
    public String toString() {
        return "Ethernet{" +
              "src=" + MAC.bytesToString(eth_src) +
            ", dst=" + MAC.bytesToString(eth_dst) +
            '}';
    }
}
