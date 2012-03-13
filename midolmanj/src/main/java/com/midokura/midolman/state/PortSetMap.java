// Copyright 2012 Midokura Inc.

package com.midokura.midolman.state;

import java.util.Set;
import java.util.HashSet;
import java.util.Iterator;
import java.util.UUID;


public class PortSetMap extends ReplicatedMap<UUID, Set<UUID>> {

    public PortSetMap(Directory dir) {
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
    protected String encodeValue(Set<UUID> value) {
        if (value.isEmpty())
            return "";
        Iterator<UUID> i = value.iterator();
        StringBuffer buf = new StringBuffer(i.next().toString());
        while (i.hasNext()) {
            buf.append(";").append(i.next().toString());
        }
        return buf.toString();
    }

    @Override
    protected Set<UUID> decodeValue(String str) {
        Set<UUID> value = new HashSet<UUID>();
        for (String uuidStr : str.split(";"))
            value.add(UUID.fromString(uuidStr));
        return value;
    }
}
