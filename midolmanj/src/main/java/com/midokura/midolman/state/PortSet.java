// Copyright 2012 Midokura Inc.

package com.midokura.midolman.state;

import java.util.UUID;


public class PortSet extends ReplicatedSet<UUID> {
    public String encode(UUID item) { return item.toString(); }
    public UUID decode(String str) { return new UUID(str); }
}
