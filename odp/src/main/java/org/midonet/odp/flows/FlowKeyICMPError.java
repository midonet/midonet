/*
* Copyright 2012 Midokura Europe SARL
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        FlowKeyICMPError that = (FlowKeyICMPError) o;
        return super.equals(o) && (icmp_data == that.icmp_data);
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 31 * result + Arrays.hashCode(icmp_data);
        return result;
    }

    @Override
    public String toString() {
        return String.format(
            "FlowKeyICMPError{icmp_type=0x%X, icmp_code=%d, icmp_data=%s}",
                             icmp_type, icmp_code, Arrays.toString(icmp_data));
    }

    public FlowKeyICMPError setIcmpData(byte[] icmp_data) {
        this.icmp_data = Arrays.copyOf(icmp_data, icmp_data.length);
        return this;
    }

    public byte[] getIcmpData() {
        return (icmp_data == null)
            ? null
            :  Arrays.copyOf(icmp_data, icmp_data.length);
    }

}

