/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midostore;

import java.util.UUID;

import com.midokura.midostore.PortBuilders.*;
import com.midokura.midolman.util.Callback1;

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

    void getLocalStateFor(String hostIdentifier, LocalStateBuilder builder);
}
