package com.midokura.midolman.layer3;

import org.junit.Assert;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import com.midokura.midolman.L3DevicePort;
import com.midokura.midolman.eventloop.MockReactor;
import com.midokura.midolman.eventloop.Reactor;
import com.midokura.midolman.layer3.Route.NextHop;
import com.midokura.midolman.layer3.Router.Action;
import com.midokura.midolman.layer3.Router.ForwardInfo;
import com.midokura.midolman.layer4.MockNatMapping;
import com.midokura.midolman.layer4.NatMapping;
import com.midokura.midolman.openflow.ControllerStub;
import com.midokura.midolman.openflow.MidoMatch;
import com.midokura.midolman.openflow.MockControllerStub;
import com.midokura.midolman.packets.ARP;
import com.midokura.midolman.packets.Data;
import com.midokura.midolman.packets.Ethernet;
import com.midokura.midolman.packets.IPv4;
import com.midokura.midolman.packets.UDP;
import com.midokura.midolman.rules.RuleEngine;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.MockDirectory;
import com.midokura.midolman.state.PortDirectory;
import com.midokura.midolman.state.RouterDirectory;
import com.midokura.midolman.state.PortDirectory.MaterializedRouterPortConfig;
import com.midokura.midolman.util.Callback;

public class TestRouter {

    private int uplinkGatewayAddr;
    private int uplinkPortAddr;
    private Route uplinkRoute;
    private Router rtr;
    private RuleEngine ruleEngine;
    private ReplicatedRoutingTable rTable;
    private PortDirectory portDir;
    private Reactor reactor;
    private MockControllerStub controllerStub;

    @Before
    public void setUp() throws Exception {
        Directory dir = new MockDirectory();
        dir.add("/midonet", null, CreateMode.PERSISTENT);
        dir.add("/midonet/ports", null, CreateMode.PERSISTENT);
        Directory portsSubdir = dir.getSubDirectory("/midonet/ports");
        portDir = new PortDirectory(portsSubdir);
        dir.add("/midonet/routers", null, CreateMode.PERSISTENT);
        Directory routersSubdir = dir.getSubDirectory("/midonet/routers");
        RouterDirectory routerDir = new RouterDirectory(routersSubdir);
        UUID rtrId = new UUID(1234, 5678);
        routerDir.addRouter(rtrId);
        // TODO(pino): replace the following with a real implementation.
        NatMapping natMap = new MockNatMapping();
        ruleEngine = new RuleEngine(routerDir, rtrId, natMap);
        rTable = new ReplicatedRoutingTable(rtrId, routerDir
                .getRoutingTableDirectory(rtrId));
        reactor = new MockReactor();
        rtr = new Router(rtrId, ruleEngine, rTable, portDir, reactor);
        controllerStub = new MockControllerStub();

        // Add a route directly to the router.
        Route rt = new Route(0x0a000000, 24, 0x0a000100, 24, NextHop.BLACKHOLE,
                null, 0, 1, null);
        routerDir.addRoute(rtrId, rt);
        //rTable.addRoute(rt);

        // Create ports in ZK.
        // Create one port that works as an uplink for the router.
        UUID portId = PortDirectory.intTo32BitUUID(1000);
        uplinkGatewayAddr = 0x0a0b0c0d;
        uplinkPortAddr = 0xb4000102; // 180.0.1.2
        int nwAddr = 0x00000000; // 0.0.0.0/0
        uplinkRoute = new Route(nwAddr, 0, nwAddr, 0, NextHop.PORT, portId, uplinkGatewayAddr, 1, null);
        Set<Route> routes = new HashSet<Route>();
        routes.add(uplinkRoute);
        MaterializedRouterPortConfig portConfig = new MaterializedRouterPortConfig(
                rtrId, nwAddr, 0, uplinkPortAddr, routes, nwAddr, 0, null);
        // Pretend the uplink port is managed by a remote controller.
        rTable.addRoute(uplinkRoute);

        // Create ports with id 0, 1, 2, 10, 11, 12, 20, 21, 22.
        // 1, 2, 3 will be in subnet 10.0.0.0/24
        // 11, 12, 13 will be in subnet 10.0.1.0/24
        // 21, 22, 23 will be in subnet 10.0.2.0/24
        // Each of the ports 'spoofs' a /30 range of its subnet, for example
        // port 21 will route to 10.0.2.4/30, 22 to 10.0.2.8/30, etc.
        for (int i = 0; i < 3; i++) {
            // Nw address is 10.0.<i>.0/24
            nwAddr = 0x0a000000 + (i << 8);
            // All ports in this subnet share the same ip address: 10.0.<i>.1
            int portAddr = nwAddr + 1;
            for (int j = 1; j < 4; j++) {
                short portNum = (short)(i * 10 + j);
                portId = PortDirectory.intTo32BitUUID(portNum);
                // The port will route to 10.0.<i>.<j*4>/30
                int segmentAddr = nwAddr + (j * 4);
                routes.clear();
                // Default route to port based on destination only. Weight 2.
                rt = new Route(0, 0, segmentAddr, 30, NextHop.PORT, portId, 0,
                        2, null);
                routes.add(rt);
                // Anything from 10.0.0.0/16 is allowed through. Weight 1.
                rt = new Route(0x0a000000, 16, segmentAddr, 30, NextHop.PORT,
                        portId, 0, 1, null);
                routes.add(rt);
                // Anything from 11.0.0.0/24 is silently dropped. Weight 1.
                rt = new Route(0x0b000000, 24, segmentAddr, 30,
                        NextHop.BLACKHOLE, null, 0, 1, null);
                routes.add(rt);
                // Anything from 12.0.0.0/24 is rejected (ICMP filter
                // prohibited).
                rt = new Route(0x0c000000, 24, segmentAddr, 30, NextHop.REJECT,
                        null, 0, 1, null);
                routes.add(rt);
                portConfig = new MaterializedRouterPortConfig(
                        rtrId, nwAddr, 24, portAddr, routes, segmentAddr, 30,
                        null);
                portDir.addPort(portId, portConfig);
                if (1 == j) {
                    // We pretend that the first port is up but managed by a
                    // remote controller. We have to manually add its routes
                    // to the routing table.
                    for (Route r : routes) {
                        rTable.addRoute(r);
                    }
                }
                else {
                    // The other ports are on the local controller.
                    L3DevicePort devPort = new L3DevicePort(portDir, portId,
                            portNum, new byte[]{(byte)0x02, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)portNum},
                            controllerStub);
                    rtr.addPort(devPort);
                }
            } // end for-loop on j
        } // end for-loop on i
    }

    @Test
    public void testDropsIPv6() {
        short IPv6_ETHERTYPE = (short) 0x86DD;
        Ethernet eth = new Ethernet();
        eth.setSourceMACAddress("02:aa:bb:cc:dd:01");
        eth.setDestinationMACAddress("02:aa:bb:cc:dd:02");
        eth.setEtherType(IPv6_ETHERTYPE);
        eth.setPad(true);
        UUID port12Id = PortDirectory.intTo32BitUUID(12);
        ForwardInfo fInfo = routePacket(port12Id, eth);
        checkForwardInfo(fInfo, Action.NOT_IPV4, null, 0);
    }

    public static Ethernet makeUDP(byte[] dlSrc, byte[] dlDst, int nwSrc,
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

    public ForwardInfo routePacket(UUID inPortId, Ethernet ethPkt) {
        byte[] pktData = ethPkt.serialize();
        MidoMatch match = new MidoMatch();
        match.loadFromPacket(pktData, (short) 0);
        ForwardInfo fInfo = new ForwardInfo();
        fInfo.inPortId = inPortId;
        fInfo.matchIn = match;
        fInfo.pktIn = ethPkt;
        rtr.process(fInfo);
        return fInfo;
    }

    public void checkForwardInfo(ForwardInfo fInfo, Action action,
            UUID outPortId, int nextHopNwAddr) {
        Assert.assertTrue(fInfo.action.equals(action));
        if (null == outPortId)
            Assert.assertNull(fInfo.outPortId);
        else
            Assert.assertTrue(fInfo.outPortId.equals(outPortId));
        Assert.assertEquals(nextHopNwAddr, fInfo.gatewayNwAddr);
    }

    @Test
    public void testForwardToUplink() {
        // Make a packet that comes in on port 23 (dlDst set to port 23's mac,
        // nwSrc inside 10.0.2.12/30) and has a nwDst that matches the uplink
        // port (e.g. anything outside 10.0.0.0/16).
        byte[] payload = new byte[] {(byte)0x0a, (byte)0x0b, (byte)0x0c};
        UUID port23Id = PortDirectory.intTo32BitUUID(23);
        UUID uplinkId = PortDirectory.intTo32BitUUID(1000);
        L3DevicePort devPort23 = rtr.devicePorts.get(port23Id);
        Ethernet eth = makeUDP(Ethernet.toMACAddress("02:00:11:22:00:01"),
                devPort23.getMacAddr(), 0x0a00020c, 0x11223344, (short)1111,
                (short)2222, payload);
        ForwardInfo fInfo = routePacket(port23Id, eth);
        checkForwardInfo(fInfo, Action.FORWARD, uplinkId, uplinkGatewayAddr);
    }

    @Test
    public void testForwardBetweenDownlinks() {
        // Make a packet that comes in on port 23 (dlDst set to port 23's mac,
        // nwSrc inside 10.0.2.12/30) and has a nwDst that matches port 12
        // (i.e. inside 10.0.1.8/30).
        byte[] payload = new byte[] {(byte)0x0a, (byte)0x0b, (byte)0x0c};
        UUID port23Id = PortDirectory.intTo32BitUUID(23);
        UUID port12Id = PortDirectory.intTo32BitUUID(12);
        L3DevicePort devPort23 = rtr.devicePorts.get(port23Id);
        Ethernet eth = makeUDP(Ethernet.toMACAddress("02:00:11:22:00:01"),
                devPort23.getMacAddr(), 0x0a00020d, 0x0a000109, (short)1111,
                (short)2222, payload);
        ForwardInfo fInfo = routePacket(port23Id, eth);
        checkForwardInfo(fInfo, Action.FORWARD, port12Id, 0x0a000109);
    }

    @Test
    public void testBlackholeRoute() {
        // Make a packet that comes in on the uplink port from a nw address in 
        // 11.0.0.0/24 and with a nwAddr that matches port 21 - in 10.0.2.4/30.
        byte[] payload = new byte[] {(byte)0x0a, (byte)0x0b, (byte)0x0c};
        UUID uplinkId = PortDirectory.intTo32BitUUID(1000);
        Ethernet eth = makeUDP(Ethernet.toMACAddress("02:00:11:22:00:01"),
                Ethernet.toMACAddress("02:00:11:22:00:01"), 0x0b0000ab,
                0x0a000207, (short)1234, (short)4321, payload);
        ForwardInfo fInfo = routePacket(uplinkId, eth);
        checkForwardInfo(fInfo, Action.BLACKHOLE, null, 0);
    }

    @Test
    public void testRejectRoute() {
        // Make a packet that comes in on the uplink port from a nw address in 
        // 12.0.0.0/24 and with a nwAddr that matches port 21 - in 10.0.2.4/30.
        byte[] payload = new byte[] {(byte)0x0a, (byte)0x0b, (byte)0x0c};
        UUID uplinkId = PortDirectory.intTo32BitUUID(1000);
        UUID port12Id = PortDirectory.intTo32BitUUID(12);
        Ethernet eth = makeUDP(Ethernet.toMACAddress("02:00:11:22:00:01"),
                Ethernet.toMACAddress("02:00:11:22:00:01"), 0x0c0000ab,
                0x0a000207, (short)1234, (short)4321, payload);
        ForwardInfo fInfo = routePacket(uplinkId, eth);
        checkForwardInfo(fInfo, Action.REJECT, null, 0);
    }

    @Test
    public void testNoRoute() throws KeeperException, InterruptedException {
        // First, pretend that the remote controller that manages the uplink
        // removes it or crashes. The uplink route is removed from the rTable.
        rTable.deleteRoute(uplinkRoute);
        // Make a packet that comes in on the port 3 (nwSrc in 10.0.0.12/30)
        // and is destined for 10.5.0.10.
        byte[] payload = new byte[] {(byte)0x0a, (byte)0x0b, (byte)0x0c};
        UUID port3Id = PortDirectory.intTo32BitUUID(3);
        Ethernet eth = makeUDP(Ethernet.toMACAddress("02:00:11:22:00:01"),
                Ethernet.toMACAddress("02:00:11:22:00:01"), 0x0a00000f,
                0x0a05000a, (short)1234, (short)4321, payload);
        ForwardInfo fInfo = routePacket(port3Id, eth);
        checkForwardInfo(fInfo, Action.NO_ROUTE, null, 0);

        // Try forwarding between down-links.
        // Make a packet that comes in on port 3 (dlDst set to port 3's mac,
        // nwSrc inside 10.0.0.12/30) and has a nwDst that matches port 12
        // (i.e. inside 10.0.1.8/30).
        UUID port12Id = PortDirectory.intTo32BitUUID(12);
        L3DevicePort devPort3 = rtr.devicePorts.get(port3Id);
        eth = makeUDP(Ethernet.toMACAddress("02:00:11:22:00:01"),
                devPort3.getMacAddr(), 0x0a00000e, 0x0a00010b, (short)1111,
                (short)2222, payload);
        fInfo = routePacket(port3Id, eth);
        checkForwardInfo(fInfo, Action.FORWARD, port12Id, 0x0a00010b);
        // Now have the local controller remove port 12.
        L3DevicePort devPort12 = rtr.devicePorts.get(port12Id);
        rtr.removePort(devPort12);
        fInfo = routePacket(port3Id, eth);
        checkForwardInfo(fInfo, Action.NO_ROUTE, null, 0);
    }

    private class ArpCompletedCallback implements Callback<byte[]> {
        byte[] mac;
        boolean called = false;

        @Override
        public void call(byte[] mac) {
            this.mac = mac;
            this.called = true;
        }
    }

    public static Ethernet makeArpRequest(byte[] sha, int spa, int tpa) {
        ARP arp = new ARP();
        arp.setHardwareType(ARP.HW_TYPE_ETHERNET);
        arp.setProtocolType(ARP.PROTO_TYPE_IP);
        arp.setHardwareAddressLength((byte) 6);
        arp.setProtocolAddressLength((byte) 4);
        arp.setOpCode(ARP.OP_REQUEST);
        arp.setSenderHardwareAddress(sha);
        arp.setSenderProtocolAddress(IPv4.toIPv4AddressBytes(spa));
        arp.setTargetProtocolAddress(IPv4.toIPv4AddressBytes(tpa));
        Ethernet pkt = new Ethernet();
        pkt.setPayload(arp);
        pkt.setSourceMACAddress(sha);
        byte b = (byte) 0xff;
        pkt.setDestinationMACAddress(new byte[] { b, b, b, b, b, b });
        pkt.setEtherType(ARP.ETHERTYPE);
        return pkt;
    }

    @Ignore
    @Test
    public void testArpRequests() {
        // Try to get the MAC for a nwAddr on port 2 (i.e. in 10.0.0.8/30).
        ArpCompletedCallback cb = new ArpCompletedCallback();
        UUID port2Id = PortDirectory.intTo32BitUUID(2);
        Assert.assertEquals(0, controllerStub.sentPackets.size());
        rtr.getMacForIp(port2Id, 0x0a00000a, cb);
        // There should now be an ARP request in the MockProtocolStub
        Assert.assertEquals(1, controllerStub.sentPackets.size());
        L3DevicePort devPort2 = rtr.devicePorts.get(port2Id);
        Ethernet expectedArp = makeArpRequest(devPort2.getMacAddr(),
                devPort2.getVirtualConfig().portAddr, 0x0a00000a);
        Assert.assertTrue(expectedArp.equals(controllerStub.sentPackets.get(0)));
    }

}
