/*
 * Copyright 2011 Midokura KK
 */

package org.midonet.midolman.state;

import java.util.UUID;

import org.midonet.packets.IntIPv4;

public class PortToIntNwAddrMap extends ReplicatedMap<UUID, IntIPv4> {

    public PortToIntNwAddrMap(Directory dir) {
        super(dir);
    }

    @Override
    protected String encodeKey(UUID key) {
        return key.toString();
    }

    @Override
    protected UUID decodeKey(String str) {
        return UUID.fromString(str);
    }

    @Override
    protected String encodeValue(IntIPv4 value) {
        return value.toString();
    }

    @Override
    protected IntIPv4 decodeValue(String str) {
        return IntIPv4.fromString(str);
    }
}
