/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midolman.test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.midokura.midolman.packets.IntIPv4;
import com.midokura.midolman.packets.TCP;
import com.midokura.midolman.packets.UDP;
import com.midokura.midolman.rules.Condition;
import com.midokura.midolman.rules.ForwardNatRule;
import com.midokura.midolman.rules.LiteralRule;
import com.midokura.midolman.rules.NatTarget;
import com.midokura.midolman.rules.ReverseNatRule;
import com.midokura.midolman.rules.Rule;
import com.midokura.midolman.rules.RuleResult;
import com.midokura.midolman.state.ChainZkManager;
import com.midokura.midolman.state.PortDirectory.LogicalBridgePortConfig;
import com.midokura.midolman.state.PortDirectory.LogicalRouterPortConfig;
import com.midokura.midolman.state.RouterZkManager;
import com.midokura.midolman.state.RuleIndexOutOfBoundsException;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkNodeEntry;

public class Router {
    UUID routerID;
    VirtualNetwork network;
    RouterZkManager.RouterConfig config;
    Map<String, RouterPort> ports =
            new HashMap<String, RouterPort>();

    public Router(VirtualNetwork network)
            throws StateAccessException {
        this.network = network;
        this.routerID = this.network.getRouterManager().create();
        this.config = this.network.getRouterManager().get(routerID).value;
        config.inboundFilter = this.network.getChainManager().create(
                new ChainZkManager.ChainConfig("PREROUTING"));
        config.outboundFilter = this.network.getChainManager().create(
                new ChainZkManager.ChainConfig("POSTROUTING"));
        this.network.getRouterManager().update(routerID, config);
    }

    public RouterPort addPort(String name, String portAddr, int nwLength)
            throws StateAccessException {
        RouterPort port = RouterPort.makeMaterialized(
                this, IntIPv4.fromString(portAddr), nwLength);
        ports.put(name, port);
        return port;
    }

    public void linkRouter(String localPortName, Router peerRouter,
                          String peerPortName) throws StateAccessException {
        IntIPv4 localPortAddr = IntIPv4.fromString("10.100.0.1");
        IntIPv4 peerPortAddr = IntIPv4.fromString("10.100.0.2");
        LogicalRouterPortConfig localPortConfig = new LogicalRouterPortConfig(
                        routerID, localPortAddr.addressAsInt(), 30,
                        localPortAddr.addressAsInt(), null, null, null);
        UUID localPortID = network.getPortManager().create(localPortConfig);
        localPortConfig = network.getPortManager().get(
                localPortID, LogicalRouterPortConfig.class);
        LogicalRouterPortConfig peerPortConfig = new LogicalRouterPortConfig(
                        peerRouter.routerID, peerPortAddr.addressAsInt(), 30,
                        peerPortAddr.addressAsInt(), null, null, null);
        UUID peerPortID = network.getPortManager().create(peerPortConfig);
        peerPortConfig = network.getPortManager().get(
                peerPortID, LogicalRouterPortConfig.class);
        localPortConfig.setPeerId(peerPortID);
        peerPortConfig.setPeerId(localPortID);
        network.getPortManager().update(localPortID, localPortConfig);
        network.getPortManager().update(peerPortID, peerPortConfig);
        ports.put(localPortName, new RouterPort(localPortID, this));
        peerRouter.ports.put(
                peerPortName, new RouterPort(peerPortID, peerRouter));
    }

    public RouterPort getPort(String portName) {
        return ports.get(portName);
    }

    public void linkBridge(String localPortName, String localPortAddress,
                           Bridge peerBridge, String bridgePortName,
                           int bridgePortIndex) throws StateAccessException {
        IntIPv4 localPortAddr = IntIPv4.fromString(localPortAddress);
        LogicalRouterPortConfig localPortConfig = new LogicalRouterPortConfig(
                        routerID, localPortAddr.addressAsInt(), 24,
                        localPortAddr.addressAsInt(), null, null, null);
        UUID localPortID = network.getPortManager().create(localPortConfig);
        localPortConfig = network.getPortManager().get(
                localPortID, LogicalRouterPortConfig.class);
        LogicalBridgePortConfig peerPortConfig =
                new LogicalBridgePortConfig(peerBridge.bridgeID, null);
        UUID peerPortID = network.getPortManager().create(peerPortConfig);
        peerPortConfig = network.getPortManager().get(
                peerPortID, LogicalBridgePortConfig.class);
        localPortConfig.setPeerId(peerPortID);
        peerPortConfig.setPeerId(localPortID);
        network.getPortManager().update(localPortID, localPortConfig);
        network.getPortManager().update(peerPortID, peerPortConfig);
        ports.put(localPortName, new RouterPort(localPortID, this));
        peerBridge.addPort(bridgePortIndex, bridgePortName,
                new BridgePort(peerPortID, peerBridge, null));
        peerBridge.setRouterAddrs(localPortAddr, localPortConfig.getHwAddr());
    }

    public void addSNAT(String subnetIP, int subnetMaskLength,
                        String uplinkPortName, String publicIP,
                        byte ipProto)
            throws StateAccessException, RuleIndexOutOfBoundsException {
        IntIPv4 pubAddr = IntIPv4.fromString(publicIP);
        IntIPv4 subnetAddr = IntIPv4.fromString(subnetIP);
        RouterPort uplinkPort = ports.get(uplinkPortName);

        Set<NatTarget> nats = new HashSet<NatTarget>();
        nats.add(new NatTarget(pubAddr.getAddress(), pubAddr.getAddress(),
                (short) 10000, (short)20000));
        Condition cond = new Condition();
        cond.outPortIds = new HashSet<UUID>();
        cond.outPortIds.add(uplinkPort.getId());
        if (ipProto != 0 && ipProto != UDP.PROTOCOL_NUMBER &&
                ipProto != TCP.PROTOCOL_NUMBER)
            throw new IllegalArgumentException("IP proto must be UDP, TCP, " +
                    "or unspecified.");
        cond.nwProto = ipProto;
        cond.nwSrcIp = subnetAddr.getAddress();
        cond.nwSrcLength = (byte)subnetMaskLength;
        Rule r = new ForwardNatRule(cond, RuleResult.Action.ACCEPT,
                config.outboundFilter, 1, false /* snat */, nats);
        network.getRuleManager().create(r);

        cond = new Condition();
        cond.inPortIds = new HashSet<UUID>();
        cond.inPortIds.add(uplinkPort.getId());
        cond.nwProto = UDP.PROTOCOL_NUMBER;
        cond.nwDstIp = pubAddr.getAddress();
        cond.nwDstLength = 32;
        r = new ReverseNatRule(cond, RuleResult.Action.ACCEPT,
                config.inboundFilter, 1, false /* snat */);
        network.getRuleManager().create(r);
    }

    public void addFloatingIP(String privateIP, String uplinkPortName,
                              String floatingIP)
            throws StateAccessException, RuleIndexOutOfBoundsException {
        IntIPv4 pubAddr = IntIPv4.fromString(floatingIP);
        IntIPv4 privAddr = IntIPv4.fromString(privateIP);
        RouterPort uplinkPort = ports.get(uplinkPortName);

        Set<NatTarget> nats = new HashSet<NatTarget>();
        nats.add(new NatTarget(pubAddr.getAddress(), pubAddr.getAddress(),
                (short) 0, (short)0));
        Condition cond = new Condition();
        cond.outPortIds = new HashSet<UUID>();
        cond.outPortIds.add(uplinkPort.getId());
        cond.nwProto = UDP.PROTOCOL_NUMBER;
        cond.nwSrcIp = privAddr.getAddress();
        cond.nwSrcLength = 32;
        Rule r = new ForwardNatRule(cond, RuleResult.Action.ACCEPT,
                config.outboundFilter, 1, false /* snat */, nats);
        network.getRuleManager().create(r);

        nats = new HashSet<NatTarget>();
        nats.add(new NatTarget(privAddr.getAddress(), privAddr.getAddress(),
                (short) 0, (short)0));
        cond = new Condition();
        cond.inPortIds = new HashSet<UUID>();
        cond.inPortIds.add(uplinkPort.getId());
        cond.nwProto = UDP.PROTOCOL_NUMBER;
        cond.nwDstIp = pubAddr.getAddress();
        cond.nwDstLength = 32;
        r = new ForwardNatRule(cond, RuleResult.Action.ACCEPT,
                config.inboundFilter, 1, true /* dnat */, nats);
        network.getRuleManager().create(r);
    }

    public UUID getId() {
        return routerID;
    }

    public RouterZkManager.RouterConfig getConfig() {
        return config;
    }

    public void changeInboundFilter() throws StateAccessException {
        config.inboundFilter = network.getChainManager().create(
                new ChainZkManager.ChainConfig("DUMMY"));
        network.getRouterManager().update(routerID, config);
    }

    public void addDummyInboundRule()
            throws StateAccessException, RuleIndexOutOfBoundsException {
        // Make a condition that matches no packets.
        Condition cond = new Condition();
        cond.conjunctionInv = true;
        Rule r = new LiteralRule(cond, RuleResult.Action.ACCEPT,
                config.inboundFilter, 1);
        network.getRuleManager().create(r);
    }

    public void addDummyOutboundRule()
            throws StateAccessException, RuleIndexOutOfBoundsException {
        // Make a condition that matches no packets.
        Condition cond = new Condition();
        cond.conjunctionInv = true;
        Rule r = new LiteralRule(cond, RuleResult.Action.REJECT,
                config.outboundFilter, 1);
        network.getRuleManager().create(r);
    }
}
