/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.midolman.state;

import java.util.UUID;

import org.midonet.packets.IPv4Addr;

/**
 * A range of ports associated with a particular IP address under a
 * virtual device.
 */
public class NatRange {
    public final UUID deviceId;
    public final IPv4Addr ip;
    public final int tpPortStart;
    public final int tpPortEnd;

    public NatRange(UUID deviceId, IPv4Addr ip, int tpPortStart, int tpPortEnd) {
        if (deviceId == null)
            throw new IllegalArgumentException("deviceId cannot be null");
        if (ip == null)
            throw new IllegalArgumentException("ip cannot be null");
        if (tpPortStart < 0)
            throw new IllegalArgumentException("tpPortStart must be greater or equal to 0");
        if (tpPortEnd < tpPortStart || tpPortEnd > 0xFFFF)
            throw new IllegalArgumentException(
                "tpPortEnd must be greater or equal to tpPortStart and less than 65536");

        this.deviceId = deviceId;
        this.ip = ip;
        this.tpPortStart = tpPortStart;
        this.tpPortEnd = tpPortEnd;
    }

    @Override
    public String toString() {
        return "NatRange[deviceId=" + deviceId + "; ip=" + ip + "; tpPortStart=" +
               tpPortStart + "; tbPortEnd=" + tpPortEnd + "]";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        NatRange natRange = (NatRange) o;
        return tpPortEnd == natRange.tpPortEnd
               && tpPortStart == natRange.tpPortStart
               && ip.equals(natRange.ip)
               && deviceId.equals(natRange.deviceId);
    }

    @Override
    public int hashCode() {
        int result = deviceId.hashCode();
        result = 31 * result + ip.hashCode();
        result = 31 * result + tpPortStart;
        result = 31 * result + tpPortEnd;
        return result;
    }
}
