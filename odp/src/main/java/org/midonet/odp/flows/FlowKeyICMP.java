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

import org.midonet.odp.OpenVSwitch;

public class FlowKeyICMP implements FlowKey {

    /*__u8*/ protected byte icmp_type;
    /*__u8*/ protected byte icmp_code;

    // This is used for deserialization purposes only.
    FlowKeyICMP() { }

    FlowKeyICMP(byte type, byte code) {
        icmp_type = type;
        icmp_code = code;
    }

    public int serializeInto(ByteBuffer buffer) {
        buffer.put(icmp_type);
        buffer.put(icmp_code);
        return 2;
    }

    public void deserializeFrom(ByteBuffer buf) {
        icmp_type = buf.get();
        icmp_code = buf.get();
    }

    public short attrId() {
        return OpenVSwitch.FlowKey.Attr.ICMP;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        @SuppressWarnings("unchecked")
        FlowKeyICMP that = (FlowKeyICMP) o;

        return (icmp_code == that.icmp_code) && (icmp_type == that.icmp_type);
    }

    @Override
    public int hashCode() {
        return 31 * icmp_type + icmp_code;
    }

    @Override
    public int connectionHash() {
        return hashCode();
    }

    @Override
    public String toString() {
        return "ICMP{type=0x"+Integer.toHexString(icmp_type) +
                         ", code=" + icmp_code + "}";
    }

    public byte getType() {
        return icmp_type;
    }

    public byte getCode() {
        return icmp_code;
    }
}
