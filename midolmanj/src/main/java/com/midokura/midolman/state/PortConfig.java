// Copyright 2012 Midokura Inc.

package com.midokura.midolman.state;

import java.util.Set;
import java.util.UUID;


public abstract class PortConfig {

    PortConfig(UUID device_id) {
        super();
        this.device_id = device_id;
    }
    // Default constructor for the Jackson deserialization.
    PortConfig() { super(); }
    public UUID device_id;
    public UUID inboundFilter;
    public UUID outboundFilter;
    public Set<UUID> portGroupIDs;
    public int greKey;
}
