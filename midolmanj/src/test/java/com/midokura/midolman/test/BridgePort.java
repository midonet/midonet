/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midolman.test;

import java.util.Random;
import java.util.UUID;

import com.midokura.midolman.state.zkManagers.ChainZkManager;
import org.openflow.protocol.OFPhysicalPort;
import org.openflow.protocol.OFPortStatus;

import com.midokura.packets.ARP;
import com.midokura.packets.Data;
import com.midokura.packets.Ethernet;
import com.midokura.packets.IPv4;
import com.midokura.packets.IntIPv4;
import com.midokura.packets.MAC;
import com.midokura.midolman.rules.Condition;
import com.midokura.midolman.rules.LiteralRule;
import com.midokura.midolman.rules.Rule;
import com.midokura.midolman.rules.RuleResult;
import com.midokura.midolman.state.PortDirectory.MaterializedBridgePortConfig;
import com.midokura.midolman.state.RuleIndexOutOfBoundsException;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.packets.UDP;

public class BridgePort {

    static Random rnd = new Random();
    MaterializedBridgePortConfig config;
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
                portID, MaterializedBridgePortConfig.class);
        this.hwAddr = MAC.random();
        this.nwAddr = nwAddr;
    }

    public static BridgePort makeMaterialized(Bridge bridge, int index,
                                              IntIPv4 ip)
            throws StateAccessException {
        MaterializedBridgePortConfig cfg = new MaterializedBridgePortConfig(
                bridge.getId());
        UUID portID = bridge.network.getPortManager().create(cfg);
        return new BridgePort(portID, bridge, ip);
    }

    public MaterializedBridgePortConfig getConfig() {
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

    public static Ethernet makeUDP(MAC dlSrc, MAC dlDst, int nwSrc,
                                   int nwDst, short tpSrc, short tpDst, byte[] data) {
        UDP udp = new UDP();
        udp.setDestinationPort(tpDst);
        udp.setSourcePort(tpSrc);
        udp.setPayload(new Data(data));
        IPv4 ip = new IPv4();
        ip.setDestinationAddress(nwDst);
        ip.setSourceAddress(nwSrc);
        ip.setProtocol(UDP.PROTOCOL_NUMBER);
        ip.setPayload(udp);
        Ethernet eth = new Ethernet();
        eth.setDestinationMACAddress(dlDst);
        eth.setSourceMACAddress(dlSrc);
        eth.setEtherType(IPv4.ETHERTYPE);
        eth.setPayload(ip);
        return eth;
    }

    public byte[] sendUDP(BridgePort dstBridgePort, boolean expectFlood) {
        byte[] payload = new byte[100];
        rnd.nextBytes(payload);
        Ethernet pkt = makeUDP(hwAddr,
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
        Ethernet pkt = makeUDP(srcMac,
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
        Ethernet pkt = makeUDP(
            hwAddr, bridge.routerMac, nwAddr.getAddress(),
            dstIp.getAddress(), randomUdpPort(), randomUdpPort(), payload);
        byte[] data = pkt.serialize();
        host.getController().onPacketIn(rnd.nextInt(), data.length,
                ovsPort.getPortNumber(), data);
    }

    public static Ethernet makeArpReply(MAC sha, MAC tha, int spa, int tpa) {
        ARP arp = new ARP();
        arp.setHardwareType(ARP.HW_TYPE_ETHERNET);
        arp.setProtocolType(ARP.PROTO_TYPE_IP);
        arp.setHardwareAddressLength((byte) 6);
        arp.setProtocolAddressLength((byte) 4);
        arp.setOpCode(ARP.OP_REPLY);
        arp.setSenderHardwareAddress(sha);
        arp.setTargetHardwareAddress(tha);
        arp.setSenderProtocolAddress(IPv4.toIPv4AddressBytes(spa));
        arp.setTargetProtocolAddress(IPv4.toIPv4AddressBytes(tpa));
        // TODO(pino) logging.
        Ethernet pkt = new Ethernet();
        pkt.setPayload(arp);
        pkt.setSourceMACAddress(sha);
        pkt.setDestinationMACAddress(tha);
        pkt.setEtherType(ARP.ETHERTYPE);
        //pkt.setPad(true);
        return pkt;
    }

    public void sendRouterArpReply() {
        Ethernet pkt = makeArpReply(
            hwAddr, bridge.routerMac,
            nwAddr.getAddress(), bridge.routerIp.getAddress());
        byte[] data = pkt.serialize();
        host.getController().onPacketIn(rnd.nextInt(), data.length,
                ovsPort.getPortNumber(), data);
    }
}
