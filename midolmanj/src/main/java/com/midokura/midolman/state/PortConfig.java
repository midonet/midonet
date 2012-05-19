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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        PortConfig that = (PortConfig) o;

        if (greKey != that.greKey) return false;
        if (device_id != null
                ? !device_id.equals(that.device_id) : that.device_id != null)
            return false;
        if (inboundFilter != null
                ? !inboundFilter.equals(that.inboundFilter)
                : that.inboundFilter != null)
            return false;
        if (outboundFilter != null
                ? !outboundFilter.equals(that.outboundFilter)
                : that.outboundFilter != null)
            return false;
        if (portGroupIDs != null ? !portGroupIDs.equals(that.portGroupIDs)
                : that.portGroupIDs != null)
            return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = device_id != null ? device_id.hashCode() : 0;
        result = 31 * result +
                (inboundFilter != null ? inboundFilter.hashCode() : 0);
        result = 31 * result +
                (outboundFilter != null ? outboundFilter.hashCode() : 0);
        result = 31 * result +
                (portGroupIDs != null ? portGroupIDs.hashCode() : 0);
        result = 31 * result + greKey;
        return result;
    }
}
