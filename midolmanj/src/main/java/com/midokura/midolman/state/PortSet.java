// Copyright 2012 Midokura Inc.

package com.midokura.midolman.state;

import java.util.UUID;

import org.apache.zookeeper.CreateMode;


public class PortSet extends ReplicatedSet<UUID> {
    public PortSet(Directory d, CreateMode createMode) {
        super(d, createMode);
    }

    public String encode(UUID item) { return item.toString(); }
    public UUID decode(String str) { return UUID.fromString(str); }
}
