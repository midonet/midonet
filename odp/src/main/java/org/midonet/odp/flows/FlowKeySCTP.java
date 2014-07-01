/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.odp.flows;

import java.nio.ByteBuffer;

import org.midonet.netlink.BytesUtil;
import org.midonet.odp.OpenVSwitch;
import org.midonet.packets.TCP;
import org.midonet.packets.Unsigned;

public class FlowKeySCTP implements FlowKey {

    /*__be16*/ private int sctp_src;
    /*__be16*/ private int sctp_dst;

    FlowKeySCTP() { }

    FlowKeySCTP(int source, int destination) {
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

    public short attrId() {
        return OpenVSwitch.FlowKey.Attr.SCTP;
    }

    public int getSCTPSrc() {
        return sctp_src;
    }

    public int getSCTPDst() {
        return sctp_dst;
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
        return "FlowKeySCTP{sctp_src=" + sctp_src + ", sctp_dst=" + sctp_dst + "}";
    }
}
