/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midonet.cluster;

import java.util.UUID;
import javax.inject.Inject;

import com.midokura.midolman.state.PortConfig;
import com.midokura.midolman.state.PortConfigCache;
import com.midokura.midolman.state.PortDirectory;
import com.midokura.midolman.state.zkManagers.PortZkManager;
import com.midokura.midonet.cluster.client.InteriorBridgePort;
import com.midokura.midonet.cluster.client.PortBuilder;

public class ClusterPortsManager extends ClusterManager<PortBuilder> {

    @Inject
    PortZkManager portMgr;

    @Inject
    PortConfigCache portConfigCache;

    @Override
    public Runnable getConfig(final UUID id) {
        return new Runnable() {

            @Override
            public void run() {
                PortConfig config = portConfigCache.get(id);
                if (config instanceof PortDirectory.LogicalBridgePortConfig) {
                    PortDirectory.LogicalBridgePortConfig cfg =
                        (PortDirectory.LogicalBridgePortConfig) config;
                    InteriorBridgePort thisPort = new InteriorBridgePort();
                    thisPort.setDeviceID(cfg.device_id);
                    thisPort.setInFilter(cfg.inboundFilter);
                    thisPort.setOutFilter(cfg.outboundFilter);
                    thisPort.setPeerID(cfg.peerId());
                    //TODO(rossella) call builder
                    //implement other ports type

                }
            }
        };
    }
}
