/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midolman.test;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.midokura.packets.IntIPv4;
import com.midokura.packets.MAC;
import com.midokura.midolman.state.BridgeZkManager.BridgeConfig;
import com.midokura.midolman.state.ChainZkManager;
import com.midokura.midolman.state.StateAccessException;

public class Bridge {
    UUID bridgeID;
    VirtualNetwork network;
    BridgeConfig config;
    Map<Integer, BridgePort> ports = new HashMap<Integer, BridgePort>();
    Map<String, BridgePort> portsByName = new HashMap<String, BridgePort>();
    IntIPv4 routerIp;
    MAC routerMac;

    public Bridge(VirtualNetwork network)
            throws StateAccessException {
        this.network = network;
        this.bridgeID =
                this.network.getBridgeManager().create(new BridgeConfig());
        this.config = this.network.getBridgeManager().get(bridgeID);
        config.inboundFilter = this.network.getChainManager().create(
                new ChainZkManager.ChainConfig("prebridging"));
        config.outboundFilter = this.network.getChainManager().create(
                new ChainZkManager.ChainConfig("postbridging"));
        this.network.getBridgeManager().update(bridgeID, config);

    }

    public BridgePort getPort(int i) {
        return ports.get(i);
    }

    public BridgePort getPort(String name) {
        return portsByName.get(name);
    }

    public void addPorts(int startIndex, int endIndex)
            throws StateAccessException {
        for (int i = 0; i < endIndex; i++)
            ports.put(i, BridgePort.makeMaterialized(this, i,
                    new IntIPv4(routerIp.getAddress() + 1 + i)));
    }

    public void addPort(int bridgePortIndex, String bridgePortName,
                        BridgePort bridgePort) {
        if (ports.containsKey(bridgePortIndex))
            throw new IllegalArgumentException("Port index already used.");
        ports.put(bridgePortIndex, bridgePort);
        portsByName.put(bridgePortName, bridgePort);
    }

    public Collection<BridgePort> getPorts() {
        return ports.values();
    }

    public BridgeConfig getConfig() {
        return config;
    }

    public UUID getId() {
        return bridgeID;
    }

    public UUID getFloodId() {
        return com.midokura.midolman.Bridge.bridgeIdToFloodId(bridgeID);
    }

    public void changeInboundFilter() throws StateAccessException {
        config.inboundFilter = network.getChainManager().create(
                new ChainZkManager.ChainConfig("DUMMY"));
        network.getBridgeManager().update(bridgeID, config);
    }

    /*package*/ void setRouterAddrs(IntIPv4 routerIp, MAC routerMac) {
        this.routerIp = routerIp;
        this.routerMac = routerMac;
    }
}
