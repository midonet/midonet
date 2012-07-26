/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midostore;

import java.util.UUID;
import javax.inject.Inject;

import com.midokura.midolman.state.ZkConnection;
import com.midokura.midolman.util.Callback1;

public class LocalMidostoreClient implements MidostoreClient {

    @Inject
    ZkConnection connection;

    public LocalMidostoreClient() {
    }

    @Override
    public void getBridge(UUID bridgeID, BridgeBuilder builder) {
    }

    @Override
    public void getRouter(UUID routerID, RouterBuilder builder) {
    }

    @Override
    public void getChain(UUID chainID, ChainBuilder builder) {
    }

    @Override
    public void getType(UUID portID, Callback1<PortType> cb) {

    }

    @Override
    public void getPort(UUID portID, PortBuilders.InteriorBridgePortBuilder builder) {
    }

    @Override
    public void getPort(UUID portID, PortBuilders.ExteriorBridgePortBuilder builder) {
        builder.start().setTunnelKey(1l).build();
    }

    @Override
    public void getPort(UUID portID, PortBuilders.InteriorRouterPortBuilder builder) {
    }

    @Override
    public void getPort(UUID portID, PortBuilders.ExteriorRouterPortBuilder builder) {

    }

    @Override
    public void getLocalStateFor(String hostIdentifier, LocalStateBuilder builder) {
    }
}
