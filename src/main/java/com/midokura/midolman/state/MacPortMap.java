/* Copyright 2011 Midokura Inc. */

package com.midokura.midolman.state;

import java.util.Map;
import java.util.UUID;

import com.midokura.midolman.packets.Ethernet;
import com.midokura.midolman.packets.MAC;
import com.midokura.midolman.util.Net;

public class MacPortMap extends ReplicatedMap<MAC, UUID> {

    public MacPortMap(Directory dir) {
        super(dir);
    }

    @Override
    protected String encodeKey(MAC key) {
        return key.toString();
    }

    @Override
    protected MAC decodeKey(String str) {
        return MAC.fromString(str);
        // TODO: Test this.
    }

    @Override
    protected String encodeValue(UUID value) {
        return value.toString();
    }

    @Override
    protected UUID decodeValue(String str) {
        return UUID.fromString(str);
    }
}
