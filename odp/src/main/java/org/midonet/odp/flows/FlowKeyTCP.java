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

import java.nio.ByteBuffer;

import org.midonet.netlink.BytesUtil;
import org.midonet.odp.OpenVSwitch;
import org.midonet.packets.TCP;
import org.midonet.packets.Unsigned;

/**
 * Flow key/mask for TCP
 *
 * When using TCP masks, the flow must also include an exact match for the
 * IP protocol equal to TCP. Otherwise, no packets will match this key/mask.
 *
 * When using TCP masks, a group of them can be used to reduce the number of
 * flows required to match on a range of transport ports. For example, suppose
 * that the goal is to match TCP source ports 1000 to 1999, inclusive. One way
 * is to insert 1000 flows, each of which matches on a single source port.
 * Another way is to look at the binary representations of 1000 and 1999, as
 * follows:
 *
 * <pre>
 * 01111101000
 * 11111001111
 * </pre>
 *
 * and then to transform those into a series of bitwise matches that
 * accomplish the same results:
 *
 * <pre>
 * 01111101xxx  ->  key/mask=0x03e8/0xfff8
 * 0111111xxxx  ->  key/mask=0x03f0/0xfff0
 * 10xxxxxxxxx  ->  key/mask=0x0400/0xfe00
 * 110xxxxxxxx  ->  key/mask=0x0600/0xff00
 * 1110xxxxxxx  ->  key/mask=0x0700/0xff80
 * 11110xxxxxx  ->  key/mask=0x0780/0xffc0
 * 1111100xxxx  ->  key/mask=0x07c0/0xfff0
 * </pre>
 *
 * @see org.midonet.odp.flows.FlowKey
 * @see org.midonet.odp.flows.FlowKeyIPv4
 * @see org.midonet.odp.FlowMask
 */
public class FlowKeyTCP implements FlowKey {

    /*__be16*/ public int tcp_src;
    /*__be16*/ public int tcp_dst;

    // This is used for deserialization purposes only.
    FlowKeyTCP() { }

    public FlowKeyTCP(int source, int destination) {
        tcp_src = source;
        tcp_dst = destination;
    }

    public int serializeInto(ByteBuffer buffer) {
        buffer.putShort(BytesUtil.instance.reverseBE((short)tcp_src));
        buffer.putShort(BytesUtil.instance.reverseBE((short)tcp_dst));
        return 4;
    }

    public void deserializeFrom(ByteBuffer buf) {
        tcp_src = Unsigned.unsign(BytesUtil.instance.reverseBE(buf.getShort()));
        tcp_dst = Unsigned.unsign(BytesUtil.instance.reverseBE(buf.getShort()));
    }

    @Override
    public void wildcard() {
        tcp_src = 0;
        tcp_dst = 0;
    }

    public short attrId() {
        return OpenVSwitch.FlowKey.Attr.TCP;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        @SuppressWarnings("unchecked")
        FlowKeyTCP that = (FlowKeyTCP) o;

        return (tcp_dst == that.tcp_dst) && (tcp_src == that.tcp_src);
    }

    @Override
    public int hashCode() {
        return 31 * tcp_src + tcp_dst;
    }

    @Override
    public String toString() {
        return "TCP{src=" + tcp_src + ", dst=" + tcp_dst + '}';
    }
}
