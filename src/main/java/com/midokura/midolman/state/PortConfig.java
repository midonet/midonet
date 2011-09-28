package com.midokura.midolman.state;

import java.util.UUID;

public abstract class PortConfig {

    public static class BridgePortConfig extends PortConfig {
        public BridgePortConfig(UUID device_id) {
            super(device_id);
        }
        // Default constructor for the Jackson deserialization.
        private BridgePortConfig() { super(); }
    
        @Override
        public boolean equals(Object other) {
            if (other == null)
                return false;
            if (other == this)
                return true;
            if (!(other instanceof BridgePortConfig))
                return false;
            BridgePortConfig port = (BridgePortConfig) other;
            return this.device_id.equals(port.device_id);
        }
    }
    PortConfig(UUID device_id) {
        super();
        this.device_id = device_id;
    }
    // Default constructor for the Jackson deserialization.
    PortConfig() { super(); }
    public UUID device_id;
}