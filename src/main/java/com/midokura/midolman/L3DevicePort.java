package com.midokura.midolman;

import java.util.Collection;
import java.util.UUID;

import com.midokura.midolman.layer3.Route;
import com.midokura.midolman.state.PortDirectory.MaterializedRouterPortConfig;

public interface L3DevicePort {

    public interface Listener {
        void configChanged(UUID portId, MaterializedRouterPortConfig old,
                MaterializedRouterPortConfig current);
        void routesChanged(UUID portId, Collection<Route> added,
                Collection<Route> removed);
    }

    UUID getId();
    byte[] getMacAddr();
    void send(byte[] ethernetPacket);
    MaterializedRouterPortConfig getVirtualConfig();
    void setVirtualConfig(MaterializedRouterPortConfig config);
    void addListener(Listener listener);
    void removeListener(Listener listener);
}
