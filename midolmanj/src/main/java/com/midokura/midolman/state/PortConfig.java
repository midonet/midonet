package com.midokura.midolman.state;

import java.util.UUID;


public abstract class PortConfig {

    PortConfig(UUID device_id) {
        super();
        this.device_id = device_id;
    }
    // Default constructor for the Jackson deserialization.
    PortConfig() { super(); }
    public UUID device_id;
}