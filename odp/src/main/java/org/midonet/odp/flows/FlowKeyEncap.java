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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.midonet.netlink.AttributeHandler;
import org.midonet.netlink.NetlinkMessage;
import org.midonet.odp.OpenVSwitch;

public class FlowKeyEncap implements FlowKey, Randomize, AttributeHandler {

    private List<FlowKey> keys;

    // This is used for deserialization purposes only.
    FlowKeyEncap() {
        keys = new ArrayList<>();
    }

    FlowKeyEncap(List<FlowKey> keys) {
        this.keys = keys;
    }

    public short attrId() {
        return OpenVSwitch.FlowKey.Attr.Encap;
    }

    public int serializeInto(ByteBuffer buffer) {
        int nBytes = 0;
        for (FlowKey key : keys) {
            nBytes += NetlinkMessage.writeAttr(buffer, key, FlowKeys.writer);
        }
        return nBytes;
    }

    public void deserializeFrom(ByteBuffer buf) {
        NetlinkMessage.scanAttributes(buf, this);
    }

    public void use(ByteBuffer buf, short id) {
        FlowKey key = FlowKeys.newBlankInstance(id);
        if (key == null)
            return;
        key.deserializeFrom(buf);
        keys.add(key);
    }

    public void randomize() {
        keys = FlowKeys.randomKeys();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        FlowKeyEncap that = (FlowKeyEncap) o;

        return Objects.equals(this.keys, that.keys);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(keys);
    }

    @Override
    public int connectionHash() { return 0; }

    @Override
    public String toString() {
        return "Encap{keys=" + keys + '}';
    }

    public Iterable<FlowKey> getKeys() {
        return keys;
    }
}
