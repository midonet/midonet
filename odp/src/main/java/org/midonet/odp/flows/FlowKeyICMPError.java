/*
 * Copyright (c) 2012 Midokura Europe SARL, All Rights Reserved.
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

        FlowKeyICMPError that = (FlowKeyICMPError) o;
        if (!super.equals(that)) return false;
        if (!Arrays.equals(icmp_data, that.icmp_data)) return false;
        return true;
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 35 * result + Arrays.hashCode(icmp_data);
        return result;
    }

    @Override
    public String toString() {
        return "FlowKeyICMPError{icmp_type=0x" + Integer.toHexString(icmp_type)
             + ", icmp_code=" + icmp_code
             + ", icmp_data=" + Arrays.toString(icmp_data) + "}";
    }

    public byte[] getIcmpData() {
        return (icmp_data == null) ? null
                                   : Arrays.copyOf(icmp_data, icmp_data.length);
    }

    @Override
    public boolean isChildOf(FlowKey key) {
        return key instanceof FlowKeyICMP;
    }
}
