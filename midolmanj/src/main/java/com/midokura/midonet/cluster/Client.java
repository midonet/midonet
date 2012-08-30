/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midonet.cluster;

import java.util.UUID;

import com.midokura.midonet.cluster.client.BridgeBuilder;
import com.midokura.midonet.cluster.client.Builder;
import com.midokura.midonet.cluster.client.ChainBuilder;
import com.midokura.midonet.cluster.client.LocalStateBuilder;
import com.midokura.midonet.cluster.client.RouterBuilder;

public interface Client {

    enum PortType {
        InteriorBridge, ExteriorBridge, InteriorRouter, ExteriorRouter
    }

    void getBridge(UUID bridgeID, BridgeBuilder builder);

    void getRouter(UUID routerID, RouterBuilder builder);

    void getChain(UUID chainID, ChainBuilder builder);

    void getPort(UUID portID, PortBuilder builder);

    interface PortBuilder extends Builder {
        void setPort(/*Port p*/);
    }

    void getLocalStateFor(UUID hostIdentifier, LocalStateBuilder builder);

//    void setLocalVrnDatapath(UUID hostIdentifier, String datapathName);
//
//    void setLocalVrnPortMapping(UUID hostInterface, UUID portId, String tapName);
//
//    void removeLocalPortMapping(UUID hostIdentifier, UUID portId);
}
