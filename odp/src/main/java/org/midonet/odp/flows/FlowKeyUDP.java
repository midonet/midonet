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

public class FlowKeyUDP implements FlowKey {

    /*__be16*/ private int udp_src;
    /*__be16*/ private int udp_dst;

    // This is used for deserialization purposes only.
    FlowKeyUDP() { }

    FlowKeyUDP(int source, int destination) {
        TCP.ensurePortInRange(source);
        TCP.ensurePortInRange(destination);
        udp_src = source;
        udp_dst = destination;
    }

    public int serializeInto(ByteBuffer buffer) {
        buffer.putShort(BytesUtil.instance.reverseBE((short)udp_src));
        buffer.putShort(BytesUtil.instance.reverseBE((short)udp_dst));
        return 4;
    }

    public void deserializeFrom(ByteBuffer buf) {
        udp_src = Unsigned.unsign(BytesUtil.instance.reverseBE(buf.getShort()));
        udp_dst = Unsigned.unsign(BytesUtil.instance.reverseBE(buf.getShort()));
    }

    public short attrId() {
        return OpenVSwitch.FlowKey.Attr.UDP;
    }

    public int getUdpSrc() {
        return udp_src;
    }

    public int getUdpDst() {
        return udp_dst;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        @SuppressWarnings("unchecked")
        FlowKeyUDP that = (FlowKeyUDP) o;

        return (udp_dst == that.udp_dst) && (udp_src == that.udp_src);
    }

    @Override
    public int hashCode() {
        return 31 * udp_src + udp_dst;
    }

    @Override
    public int connectionHash() {
        return hashCode();
    }

    @Override
    public String toString() {
        return "UDP{src=" + udp_src + ", dst=" + udp_dst + "}";
    }
}
