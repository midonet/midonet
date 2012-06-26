/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midolman.test;

import java.util.Random;
import java.util.UUID;

import org.openflow.protocol.OFPhysicalPort;
import org.openflow.protocol.OFPortStatus;

import com.midokura.midolman.layer3.TestRouter;
import com.midokura.midolman.packets.Data;
import com.midokura.midolman.packets.Ethernet;
import com.midokura.midolman.packets.IPv4;
import com.midokura.midolman.packets.IntIPv4;
import com.midokura.midolman.packets.MAC;
import com.midokura.midolman.packets.UDP;
import com.midokura.midolman.rules.Condition;
import com.midokura.midolman.rules.LiteralRule;
import com.midokura.midolman.rules.Rule;
import com.midokura.midolman.rules.RuleResult;
import com.midokura.midolman.state.ChainZkManager;
import com.midokura.midolman.state.PortConfig;
import com.midokura.midolman.state.PortDirectory.BridgePortConfig;
import com.midokura.midolman.state.RuleIndexOutOfBoundsException;
import com.midokura.midolman.state.StateAccessException;

public class BridgePort {

    static Random rnd = new Random();
    BridgePortConfig config;
    OFPhysicalPort ovsPort;
    Host host;
    final UUID portID;
    final Bridge bridge;
    // The MAC of the VM connected to this bridge port.
    final MAC hwAddr;
    // The IP of the VM connected to this bridge port.
    final IntIPv4 nwAddr;

    public BridgePort(UUID portID, Bridge bridge, IntIPv4 nwAddr)
            throws StateAccessException {
        this.portID = portID;
        this.bridge = bridge;
        this.config = bridge.network.getPortManager().get(
                portID, BridgePortConfig.class);
        this.hwAddr = MAC.random();
        this.nwAddr = nwAddr;
    }

    public static BridgePort makeMaterialized(Bridge bridge, int index,
                                              IntIPv4 ip)
            throws StateAccessException {
        BridgePortConfig cfg = new BridgePortConfig(bridge.getId());
        UUID portID = bridge.network.getPortManager().create(cfg);
        return new BridgePort(portID, bridge, ip);
    }

    public BridgePortConfig getConfig() {
        return config;
    }

    public UUID getId() {
        return portID;
    }

    public OFPhysicalPort getOVSPort() {
        return ovsPort;
    }

    public Host getHost() {
        return host;
    }

    public void setHost(Host h, short ovsPortNum) {
        host = h;
        ovsPort = h.makePhysicalPort(ovsPortNum, getId());
        h.getController().onPortStatus(
                ovsPort, OFPortStatus.OFPortReason.OFPPR_ADD);
    }

    public void addFilters() throws StateAccessException {
        config.inboundFilter = bridge.network.getChainManager().create(
                new ChainZkManager.ChainConfig("DUMMY_IN"));
        config.outboundFilter = bridge.network.getChainManager().create(
                new ChainZkManager.ChainConfig("DUMMY_OUT"));
        bridge.network.getPortManager().update(portID, config);
    }

    public void addDummyInboundRule()
            throws StateAccessException, RuleIndexOutOfBoundsException {
        // Make a condition that matches no packets.
        Condition cond = new Condition();
        cond.conjunctionInv = true;
        Rule r = new LiteralRule(cond, RuleResult.Action.ACCEPT,
                config.inboundFilter, 1);
        bridge.network.getRuleManager().create(r);
    }

    public void addDummyOutboundRule()
            throws StateAccessException, RuleIndexOutOfBoundsException {
        // Make a condition that matches no packets.
        Condition cond = new Condition();
        cond.conjunctionInv = true;
        Rule r = new LiteralRule(cond, RuleResult.Action.REJECT,
                config.outboundFilter, 1);
        bridge.network.getRuleManager().create(r);
    }

    public MAC getMAC() {
        return hwAddr;
    }

    public IntIPv4 getIp() {
        return nwAddr;
    }

    private short randomUdpPort() {
        return (short)(10000 + rnd.nextInt() % 50000);
    }

    public byte[] sendUDP(BridgePort dstBridgePort, boolean expectFlood) {
        byte[] payload = new byte[100];
        rnd.nextBytes(payload);
        Ethernet pkt = TestRouter.makeUDP(hwAddr,
                (bridge == dstBridgePort.bridge)
                        ? dstBridgePort.getMAC() : bridge.routerMac,
                nwAddr.getAddress(), dstBridgePort.nwAddr.getAddress(),
                randomUdpPort(), randomUdpPort(), payload);
        byte[] data = pkt.serialize();
        host.getController().onPacketIn(rnd.nextInt(), data.length,
                ovsPort.getPortNumber(), data);
        return data;
    }

    public void sendUDP(MAC srcMac, BridgePort dstBridgePort,
                        boolean expectFlood) {
        byte[] payload = new byte[100];
        rnd.nextBytes(payload);
        Ethernet pkt = TestRouter.makeUDP(srcMac,
                (bridge == dstBridgePort.bridge)
                        ? dstBridgePort.getMAC() : bridge.routerMac,
                nwAddr.getAddress(), dstBridgePort.nwAddr.getAddress(),
                randomUdpPort(), randomUdpPort(), payload);
        byte[] data = pkt.serialize();
        host.getController().onPacketIn(rnd.nextInt(), data.length,
                ovsPort.getPortNumber(), data);
    }

    public void sendUDP(String destination) {
        IntIPv4 dstIp = IntIPv4.fromString(destination);
        byte[] payload = new byte[100];
        rnd.nextBytes(payload);
        Ethernet pkt = TestRouter.makeUDP(
                hwAddr, bridge.routerMac, nwAddr.getAddress(),
                dstIp.getAddress(), randomUdpPort(), randomUdpPort(), payload);
        byte[] data = pkt.serialize();
        host.getController().onPacketIn(rnd.nextInt(), data.length,
                ovsPort.getPortNumber(), data);
    }

    public void sendRouterArpReply() {
        Ethernet pkt = TestRouter.makeArpReply(
                hwAddr, bridge.routerMac,
                nwAddr.getAddress(), bridge.routerIp.getAddress());
        byte[] data = pkt.serialize();
        host.getController().onPacketIn(rnd.nextInt(), data.length,
                ovsPort.getPortNumber(), data);
    }
}
