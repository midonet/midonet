/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midonet.cluster;

import java.util.UUID;

import com.midokura.midolman.util.Callback1;

public interface MidostoreClient {


    enum PortType {
        InteriorBridge, ExteriorBridge, InteriorRouter, ExteriorRouter
    }

    void getBridge(UUID bridgeID, BridgeBuilder builder);

    void getRouter(UUID routerID, RouterBuilder builder);

    void getChain(UUID chainID, ChainBuilder builder);

    void getType(UUID portID, Callback1<PortType> cb);

    void getPort(UUID portID, PortBuilders.InteriorBridgePortBuilder builder);

    void getPort(UUID portID, PortBuilders.ExteriorBridgePortBuilder builder);

    void getPort(UUID portID, PortBuilders.InteriorRouterPortBuilder builder);

    void getPort(UUID portID, PortBuilders.ExteriorRouterPortBuilder builder);

    void getLocalStateFor(UUID hostIdentifier, LocalStateBuilder builder);

    void setLocalVrnDatapath(UUID hostIdentifier, String datapathName);

    void setLocalVrnPortMapping(UUID hostInterface, UUID portId, String tapName);

    void removeLocalPortMapping(UUID hostIdentifier, UUID portId);
}
