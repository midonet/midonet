/* Copyright 2011 Midokura Inc. */

package com.midokura.midolman.state;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.midokura.midolman.packets.Ethernet;
import com.midokura.midolman.util.Net;
import com.midokura.midolman.util.MAC;

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

    // TODO: Would this be better moved to ReplicatedMap.getByValue() ?
    public List<MAC> getByUuid(UUID portUuid) {
        ArrayList<MAC> macList = new ArrayList<MAC>();
        for (Map.Entry<MAC, MapValue> entry : map.entrySet())
            if (entry.getValue().value.equals(portUuid))
                macList.add(entry.getKey());
        return macList;
    }
}
