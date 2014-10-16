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
import org.midonet.packets.VLAN;

public class FlowKeyVLAN implements CachedFlowKey {

    /* be16 */
    //short pcp; // Priority Code Point 3 bits
    //short dei; // Drop Elegible Indicator 1 bit
    private short vlan; // 12 bit

    // This is used for deserialization purposes only.
    FlowKeyVLAN() { }

    FlowKeyVLAN(short vlanTCI) {
        vlan = vlanTCI;
    }

    public int serializeInto(ByteBuffer buffer) {
        buffer.putShort(BytesUtil.instance.reverseBE(VLAN.setDEI(vlan)));
        return 2;
    }

    public void deserializeFrom(ByteBuffer buf) {
        vlan = VLAN.unsetDEI(BytesUtil.instance.reverseBE(buf.getShort()));
    }

    public short attrId() {
        return OpenVSwitch.FlowKey.Attr.VLan;
    }

    public short getVLAN() {
        return vlan;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        @SuppressWarnings("unchecked")
        FlowKeyVLAN that = (FlowKeyVLAN) o;

        return vlan == that.vlan;
    }

    @Override
    public int hashCode() {
        return vlan;
    }

    @Override
    public int connectionHash() { return 0; }

    @Override
    public String toString() {
        return "VLAN{" + vlan + '}';
    }
}
