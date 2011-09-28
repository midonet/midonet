/*
 * Copyright 2011 Midokura KK 
 */

package com.midokura.midolman.state;

import java.util.UUID;

import com.midokura.midolman.util.Net;

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
        return Net.convertIntAddressToString(value);
    }

    @Override
    protected Integer decodeValue(String str) {
        return Net.convertStringAddressToInt(str);
    }
}
