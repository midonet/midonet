/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.odp.flows;

/**
 * This FlowKey is not supported by Netlink. FlowMatch is aware of this and
 * will report that the match is not Netlink-compatible so that the
 * FlowController never installs a kernel flow with this key. Otherwise it's
 * functional and can be used for WildcardFlows.
 *
 * By making this class implement FlowKey.UserSpaceOnly we ensure that flows
 * that require matching on this key will never be installed in the kernel.
 */
public class FlowKeyICMPEcho extends FlowKeyICMP
                             implements FlowKey.UserSpaceOnly {

    private short icmp_id;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        FlowKeyICMPEcho that = (FlowKeyICMPEcho) o;
        if (icmp_code != that.icmp_code) return false;
        if (icmp_type != that.icmp_type) return false;
        if (icmp_id != that.icmp_id) return false;
        return true;
    }

    @Override
    public int hashCode() {
        int result = (int) icmp_type;
        result = 33 * result + (int) icmp_code;
        result = 33 * result + (int) icmp_id;
        return result;
    }

    @Override
    public String toString() {
        return String.format(
            "FlowKeyICMPEcho{icmp_type=0x%X, icmp_code=%d, icmp_id=%d}",
                             icmp_type, icmp_code, icmp_id);
    }

    public FlowKeyICMPEcho setIdentifier(short identifier) {
        this.icmp_id = identifier;
        return this;
    }

    public short getIdentifier() {
        return icmp_id;
    }

    @Override
    public boolean isChildOf(FlowKey<?> key) {
        return key instanceof FlowKeyICMP;
    }

}

