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

public class FlowActionOutput implements FlowAction {

    /** u32 port number. */
    private int portNumber;

    // This is used for deserialization purposes only.
    FlowActionOutput() { }

    FlowActionOutput(int portNumber) {
        this.portNumber = portNumber;
    }

    public int serializeInto(ByteBuffer buffer) {
        buffer.putInt(portNumber);
        return 4;
    }

    public void deserializeFrom(ByteBuffer buf) {
        portNumber = buf.getInt();
    }

    public short attrId() {
        return OpenVSwitch.FlowAction.Attr.Output;
    }

    public int getPortNumber() {
        return portNumber;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        @SuppressWarnings("unchecked")
        FlowActionOutput that = (FlowActionOutput) o;

        return this.portNumber == that.portNumber;
    }

    @Override
    public int hashCode() {
        return portNumber;
    }

    @Override
    public String toString() {
        return "Output{port=" + portNumber + '}';
    }
}
