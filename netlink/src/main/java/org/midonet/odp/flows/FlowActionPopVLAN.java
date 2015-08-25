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

public class FlowActionPopVLAN implements FlowAction {

    FlowActionPopVLAN() { }

    /** For the POP_VLAN action, nothing is actually serialised after the
     *  netlink attribute header, since POP_VLAN is a "value-less" action. In
     *  the datapath code, the 0 length (not-counting the header) is checked and
     *  enforced in flow-netlink.c in ovs_nla_copy_actions(). */
    public int serializeInto(ByteBuffer buffer) {
        return 0;
    }

    public void deserializeFrom(ByteBuffer buf) {
    }

    public short attrId() {
        return OpenVSwitch.FlowAction.Attr.PopVLan;
    }

    @Override
    public int hashCode() {
        return 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        return true;
    }

    @Override
    public String toString() {
        return "PopVLAN{}";
    }
}
