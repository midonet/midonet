/*
 * Copyright 2011 Midokura KK 
 */

package com.midokura.midolman.state;

import java.util.UUID;

public class PortToIntNwAddrMap extends ReplicatedMap<UUID, Integer> {

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
    protected String encodeValue(Integer value) {
        return Integer.toHexString(value);
    }

    @Override
    protected Integer decodeValue(String str) {
        // Can't use Integer.parseInt because it treats the string as a signed
        // value.
        return (int)Long.parseLong(str, 16);
    }
}
