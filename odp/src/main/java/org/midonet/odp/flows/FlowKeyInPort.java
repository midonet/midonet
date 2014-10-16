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

public class FlowKeyInPort implements CachedFlowKey {

    /*__u32*/ private int portNo;

    // This is used for deserialization purposes only.
    FlowKeyInPort() { }

    FlowKeyInPort(int portNo) {
        this.portNo = portNo;
    }

    public int serializeInto(ByteBuffer buffer) {
        buffer.putInt(portNo);
        return 4;
    }

    public void deserializeFrom(ByteBuffer buf) {
        portNo = buf.getInt();
    }

    public short attrId() {
        return OpenVSwitch.FlowKey.Attr.InPort;
    }

    public int getInPort() {
        return this.portNo;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        @SuppressWarnings("unchecked")
        FlowKeyInPort that = (FlowKeyInPort) o;

        return this.portNo == that.portNo;
    }

    @Override
    public int hashCode() {
        return portNo;
    }

    @Override
    public int connectionHash() { return 0; }

    @Override
    public String toString() {
        return "InPort{" + portNo + '}';
    }
}
