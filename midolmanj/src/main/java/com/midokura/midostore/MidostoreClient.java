/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midostore;

import java.util.UUID;

import com.midokura.midolman.util.Callback1;
import com.midokura.midostore.PortBuilders.ExteriorBridgePortBuilder;
import com.midokura.midostore.PortBuilders.ExteriorRouterPortBuilder;
import com.midokura.midostore.PortBuilders.InteriorBridgePortBuilder;
import com.midokura.midostore.PortBuilders.InteriorRouterPortBuilder;

public interface MidostoreClient {


    enum PortType {
        InteriorBridge, ExteriorBridge, InteriorRouter, ExteriorRouter
    }

    void getBridge(UUID bridgeID, BridgeBuilder builder);

    void getRouter(UUID routerID, RouterBuilder builder);

    void getChain(UUID chainID, ChainBuilder builder);

    void getType(UUID portID, Callback1<PortType> cb);

    void getPort(UUID portID, InteriorBridgePortBuilder builder);

    void getPort(UUID portID, ExteriorBridgePortBuilder builder);

    void getPort(UUID portID, InteriorRouterPortBuilder builder);

    void getPort(UUID portID, ExteriorRouterPortBuilder builder);

    void getLocalStateFor(UUID hostIdentifier, LocalStateBuilder builder);

    void setLocalVrnDatapath(UUID hostIdentifier, String datapathName);

    void setLocalVrnPortMapping(UUID hostInterface, UUID portId, String tapName);

    void removeLocalPortMapping(UUID hostIdentifier, UUID portId);
}
