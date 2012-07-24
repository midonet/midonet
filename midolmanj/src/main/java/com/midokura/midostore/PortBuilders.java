/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midostore;

import java.util.Set;
import java.util.UUID;

import com.midokura.midolman.state.BGP;
import com.midokura.packets.IPv4;
import com.midokura.packets.MAC;

public interface PortBuilders {
    interface PortBuilder extends DeviceBuilder {
        void setDeviceID(UUID id);
        void setPortGroupIDs(Set<UUID> portGroupIDs);
    }

    interface ExteriorPortBuilder extends PortBuilder {
        void setTunnelKey(long tunnelKey);
    }

    interface InteriorPortBuilder extends PortBuilder {
        void setPeerID(UUID peerID);
    }

    interface RouterPortBuilder extends PortBuilder {
        void setNetAddr(IPv4 ipAddr);
        void setPortAddr(IPv4 ipAddr);
        void setMac(MAC mac);
    }

    interface BridgePortBuilder extends PortBuilder {}

    interface InteriorBridgePortBuilder
            extends  InteriorPortBuilder, BridgePortBuilder{}

    interface ExteriorBridgePortBuilder
            extends ExteriorPortBuilder, BridgePortBuilder {}

    interface InteriorRouterPortBuilder
            extends InteriorPortBuilder, RouterPortBuilder {}

    interface ExteriorRouterPortBuilder
            extends ExteriorPortBuilder, RouterPortBuilder{
        void setBgps(Set<BGP> bgps);
    }
}
