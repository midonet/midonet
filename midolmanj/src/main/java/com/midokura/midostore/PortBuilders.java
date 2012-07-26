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

    interface PortBuilder<
        ConcretePortBuilder extends PortBuilder<ConcretePortBuilder>
        >
        extends DeviceBuilder<ConcretePortBuilder> {

        ConcretePortBuilder setDeviceID(UUID id);

        ConcretePortBuilder setPortGroupIDs(Set<UUID> portGroupIDs);
    }

    interface ExteriorPortBuilder<Builder extends ExteriorPortBuilder<Builder>>
        extends PortBuilder<Builder> {
        Builder setTunnelKey(long tunnelKey);
    }

    interface InteriorPortBuilder extends PortBuilder {
        void setPeerID(UUID peerID);
    }

    interface RouterPortBuilder<B extends PortBuilder<B>> extends PortBuilder<B> {
        B setNetAddr(IPv4 ipAddr);

        B setPortAddr(IPv4 ipAddr);

        void setMac(MAC mac);
    }

    interface BridgePortBuilder<C extends BridgePortBuilder<C>> extends PortBuilder<C> {
    }

    interface InteriorBridgePortBuilder
        extends InteriorPortBuilder, BridgePortBuilder {
    }

    interface ExteriorBridgePortBuilder
        extends ExteriorPortBuilder<ExteriorBridgePortBuilder>,
                BridgePortBuilder<ExteriorBridgePortBuilder> {
    }

    interface InteriorRouterPortBuilder
        extends InteriorPortBuilder, RouterPortBuilder {
    }

    interface ExteriorRouterPortBuilder
        extends ExteriorPortBuilder<ExteriorRouterPortBuilder>,
                RouterPortBuilder<ExteriorRouterPortBuilder>
    {
        ExteriorRouterPortBuilder setBgps(Set<BGP> bgps);
    }

}
