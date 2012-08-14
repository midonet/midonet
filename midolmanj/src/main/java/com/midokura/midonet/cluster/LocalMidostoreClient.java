/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midonet.cluster;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.host.state.HostDirectory;
import com.midokura.midolman.host.state.HostZkManager;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.zkManagers.BgpZkManager;
import com.midokura.midolman.util.Callback1;

public class LocalMidostoreClient implements MidostoreClient {

    private static final Logger log = LoggerFactory
        .getLogger(LocalMidostoreClient.class);

    @Inject
    HostZkManager hostZkManager;

    @Inject
    BgpZkManager bgpZkManager;

    public LocalMidostoreClient() {
        int a = 10;
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

    Map<UUID, LocalStateBuilder> localStateBuilders =
        new HashMap<UUID, LocalStateBuilder>();

    @Override
    public void getLocalStateFor(UUID hostIdentifier, LocalStateBuilder builder) {
        localStateBuilders.put(hostIdentifier, builder);
        triggerUpdate(hostIdentifier);
    }

    @Override
    public void setLocalVrnDatapath(UUID hostIdentifier, String datapathName) {
        try {
            hostZkManager.addVirtualDatapathMapping(hostIdentifier,
                                                    datapathName);
            triggerUpdate(hostIdentifier);
        } catch (StateAccessException e) {
            log.error("Exception: ", e);
        }
    }

    private void triggerUpdate(UUID hostIdentifier) {
        try {

            LocalStateBuilder builder = localStateBuilders.get(hostIdentifier);
            if (builder == null)
                return;

            builder.setDatapathName(
                hostZkManager.getVirtualDatapathMapping(hostIdentifier));

            Set<HostDirectory.VirtualPortMapping> portMappings =
                hostZkManager.getVirtualPortMappings(hostIdentifier);

            for (HostDirectory.VirtualPortMapping portMapping : portMappings) {
                builder.addLocalPortInterface(portMapping.getVirtualPortId(),
                                              portMapping.getLocalDeviceName());
            }

            builder.build();
        } catch (StateAccessException e) {
            log.error("Exception: ", e);
        }
    }

    @Override
    public void setLocalVrnPortMapping(UUID hostIdentifier, UUID portId, String tapName) {
        try {
            hostZkManager.addVirtualPortMapping(hostIdentifier,
                                                new HostDirectory.VirtualPortMapping(
                                                    portId, tapName));
            triggerUpdate(hostIdentifier);
        } catch (StateAccessException e) {
            log.error("Exception: ", e);
        }
    }

    @Override
    public void removeLocalPortMapping(UUID hostIdentifier, UUID portId) {
        try {
            hostZkManager.removeVirtualPortMapping(hostIdentifier, portId);
            triggerUpdate(hostIdentifier);
        } catch (StateAccessException e) {
            log.error("Exception: ", e);
        }
    }
}
