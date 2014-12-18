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

import org.midonet.netlink.BytesUtil;
import org.midonet.odp.OpenVSwitch;
import org.midonet.packets.MAC;
import org.midonet.packets.Net;

/**
* Neighbour Discovery key
*/
public class FlowKeyND implements CachedFlowKey {

    /*__u32*/ public int[] nd_target = new int[4]; // always 4 int long
    /*__u8*/ public byte[] nd_sll = new byte[6];   // always 6 bytes long
    /*__u8*/ public byte[] nd_tll = new byte[6];   // always 6 bytes long

    // This is used for deserialization purposes only.
    FlowKeyND() { }

    FlowKeyND(int[] target) {
        nd_target = target;
    }

    public int serializeInto(ByteBuffer buffer) {
        BytesUtil.instance.writeBEIntsInto(buffer, nd_target);
        buffer.put(nd_sll);
        buffer.put(nd_tll);
        return 28;
    }

    public void deserializeFrom(ByteBuffer buf) {
        BytesUtil.instance.readBEIntsFrom(buf, nd_target);
        buf.get(nd_sll);
        buf.get(nd_tll);
    }

    @Override
    public void wildcard() {
        Arrays.fill(nd_target, 0);
        Arrays.fill(nd_sll, (byte) 0);
        Arrays.fill(nd_tll, (byte) 0);
    }

    public short attrId() {
        return OpenVSwitch.FlowKey.Attr.ND;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        @SuppressWarnings("unchecked")
        FlowKeyND that = (FlowKeyND) o;

        return Arrays.equals(this.nd_sll, that.nd_sll)
            && Arrays.equals(this.nd_target, that.nd_target)
            && Arrays.equals(this.nd_tll, that.nd_tll);
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(nd_target);
        result = 31 * result + Arrays.hashCode(nd_sll);
        result = 31 * result + Arrays.hashCode(nd_tll);
        return result;
    }

    @Override
    public String toString() {
        return "ND{" +
                  "target=" + Net.convertIPv6BytesToString(nd_target) +
                ", sll=" + MAC.bytesToString(nd_sll) +
                ", tll=" + MAC.bytesToString(nd_tll) +
                '}';
    }
}
