/*
 * Copyright (c) 2012 Midokura SARL, All Rights Reserved.
 */
package org.midonet.odp.flows;

import java.util.Arrays;

/**
 * This FlowKey is not supported by Netlink. FlowMatch is aware of this and
 * will report that the match is not Netlink-compatible so that the
 * FlowController never installs a kernel flow with this key. Otherwise it's
 * functional and can be used for WildcardFlows.
 *
 * By making this class implement FlowKey.UserSpaceOnly we ensure that flows
 * that require matching on this key will never be installed in the kernel.
 */
public class FlowKeyICMPError extends FlowKeyICMP
                              implements FlowKey.UserSpaceOnly {
    private byte[] icmp_data = null;

    FlowKeyICMPError(byte type, byte code, byte[] data) {
        super(type, code);
        icmp_data = data;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        @SuppressWarnings("unchecked")
        FlowKeyICMPError that = (FlowKeyICMPError) o;

        return super.equals(that) && Arrays.equals(icmp_data, that.icmp_data);
    }

    @Override
    public int hashCode() {
        return 35 * super.hashCode() + Arrays.hashCode(icmp_data);
    }

    @Override
    public String toString() {
        return "ICMPError{type=0x" + Integer.toHexString(icmp_type)
             + ", code=" + icmp_code
             + ", data=" + Arrays.toString(icmp_data) + "}";
    }

    public byte[] getIcmpData() {
        return (icmp_data == null) ? null
                                   : Arrays.copyOf(icmp_data, icmp_data.length);
    }
}
