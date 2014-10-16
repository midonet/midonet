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
import java.util.List;

import org.midonet.netlink.BytesUtil;
import org.midonet.odp.OpenVSwitch;
import org.midonet.packets.TCP;
import org.midonet.packets.Unsigned;


/**
 * Flow key/mask for TCP flags
 *
 * When using TCP flags, the flow must also include an **exact** match for the
 * TCP source and destination. Otherwise, no packets will match this key/mask.
 *
 * @see org.midonet.odp.flows.FlowKey
 * @see org.midonet.odp.flows.FlowKeyTCP
 * @see org.midonet.odp.FlowMask
 */
public class FlowKeyTCPFlags implements FlowKey {

    /*__be16*/ private short flags;

    // This is used for deserialization purposes only.
    FlowKeyTCPFlags() { }

    FlowKeyTCPFlags(short f) {
        flags = f;
    }

    FlowKeyTCPFlags(List<TCP.Flag> flst) {
        flags = TCP.Flag.allOf(flst);
    }

    public short getFlags() { return flags; }

    // TODO: probably we do not need these two functions... [alvaro]

    public boolean getFlag(TCP.Flag f) { return (flags & f.bit) != 0; }

    public void setFlag(TCP.Flag f, boolean v) {
        if (v)
            flags |= f.bit;
        else
            flags &= ~f.bit;
    }

    public int serializeInto(ByteBuffer buffer) {
        buffer.putShort(BytesUtil.instance.reverseBE(flags));
        return 2;
    }

    public void deserializeFrom(ByteBuffer buf) {
        flags = (short) Unsigned.unsign(BytesUtil.instance.reverseBE(buf.getShort()));
    }

    public short attrId() {
        return OpenVSwitch.FlowKey.Attr.TcpFlags;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        @SuppressWarnings("unchecked")
        FlowKeyTCPFlags that = (FlowKeyTCPFlags) o;

        return (flags == that.flags);
    }

    @Override
    public int hashCode() {
        return flags;
    }

    @Override
    public int connectionHash() {
        return 0;
    }

    @Override
    public String toString() {
        return "TCPFlags{'" +
               TCP.Flag.allOfToString(flags) + "'}";
    }
}

