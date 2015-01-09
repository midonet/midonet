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

public class FlowKeySCTP implements FlowKey {

    /*__be16*/ public int sctp_src;
    /*__be16*/ public int sctp_dst;

    FlowKeySCTP() { }

    public FlowKeySCTP(int source, int destination) {
        TCP.ensurePortInRange(source);
        TCP.ensurePortInRange(destination);
        sctp_src = source;
        sctp_dst = destination;
    }

    public int serializeInto(ByteBuffer buffer) {
        buffer.putShort(BytesUtil.instance.reverseBE((short)sctp_src));
        buffer.putShort(BytesUtil.instance.reverseBE((short)sctp_dst));
        return 4;
    }

    public void deserializeFrom(ByteBuffer buf) {
        sctp_src = Unsigned.unsign(BytesUtil.instance.reverseBE(buf.getShort()));
        sctp_dst = Unsigned.unsign(BytesUtil.instance.reverseBE(buf.getShort()));
    }

    @Override
    public void wildcard() {
        sctp_src = 0;
        sctp_dst = 0;
    }

    public short attrId() {
        return OpenVSwitch.FlowKey.Attr.SCTP;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        @SuppressWarnings("unchecked")
        FlowKeySCTP that = (FlowKeySCTP) o;

        return (this.sctp_dst == that.sctp_dst)
            && (this.sctp_src == that.sctp_src);
    }

    @Override
    public int hashCode() {
        return 31 * sctp_src + sctp_dst;
    }

    @Override
    public String toString() {
        return "SCTP{src=" + sctp_src + ", dst=" + sctp_dst + "}";
    }
}
