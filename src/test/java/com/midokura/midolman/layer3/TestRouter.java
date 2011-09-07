package com.midokura.midolman.layer3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.action.OFActionOutput;

import scala.actors.threadpool.Arrays;

import com.midokura.midolman.L3DevicePort;
import com.midokura.midolman.eventloop.MockReactor;
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
import com.midokura.midolman.state.RouterDirectory.RouterConfig;
import com.midokura.midolman.util.Callback;

public class TestRouter {

    private int uplinkGatewayAddr;
    private int uplinkPortAddr;
    private Route uplinkRoute;
    private Router rtr;
    private RuleEngine ruleEngine;
    private ReplicatedRoutingTable rTable;
    private PortDirectory portDir;
    private MockReactor reactor;
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
        UUID tenantId = new UUID(1234, 6789);
        RouterConfig cfg = new RouterConfig("Test Router", tenantId);
        routerDir.addRouter(rtrId, cfg);
        // TODO(pino): replace the following with a real implementation.
        NatMapping natMap = new MockNatMapping();
        ruleEngine = new RuleEngine(routerDir, rtrId, natMap);
        rTable = new ReplicatedRoutingTable(rtrId, routerDir
                .getRoutingTableDirectory(rtrId), CreateMode.EPHEMERAL);
        reactor = new MockReactor();
        rtr = new Router(rtrId, ruleEngine, rTable, portDir, reactor);
        controllerStub = new MockControllerStub();

        // Add a route directly to the router.
        Route rt = new Route(0x0a000000, 24, 0x0a000100, 24, NextHop.BLACKHOLE,
                null, 0, 1, null);
        routerDir.addRoute(rtrId, rt);
        // rTable.addRoute(rt);

        // Create ports in ZK.
        // Create one port that works as an uplink for the router.
        UUID portId = PortDirectory.intTo32BitUUID(1000);
        uplinkGatewayAddr = 0x0a0b0c0d;
        uplinkPortAddr = 0xb4000102; // 180.0.1.2
        int nwAddr = 0x00000000; // 0.0.0.0/0
        uplinkRoute = new Route(nwAddr, 0, nwAddr, 0, NextHop.PORT, portId,
                uplinkGatewayAddr, 1, null);
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
                short portNum = (short) (i * 10 + j);
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
                portConfig = new MaterializedRouterPortConfig(rtrId, nwAddr,
                        24, portAddr, routes, segmentAddr, 30, null);
                portDir.addPort(portId, portConfig);
                if (1 == j) {
                    // We pretend that the first port is up but managed by a
                    // remote controller. We have to manually add its routes
                    // to the routing table.
                    for (Route r : routes) {
                        rTable.addRoute(r);
                    }
                } else {
                    // The other ports are on the local controller.
                    L3DevicePort devPort = new L3DevicePort(portDir, portId,
                            portNum, new byte[] { (byte) 0x02, (byte) 0x00,
                                    (byte) 0x00, (byte) 0x00, (byte) 0x00,
                                    (byte) portNum }, controllerStub);
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

    public static void checkForwardInfo(ForwardInfo fInfo, Action action,
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
        byte[] payload = new byte[] { (byte) 0x0a, (byte) 0x0b, (byte) 0x0c };
        UUID port23Id = PortDirectory.intTo32BitUUID(23);
        UUID uplinkId = PortDirectory.intTo32BitUUID(1000);
        L3DevicePort devPort23 = rtr.devicePorts.get(port23Id);
        Ethernet eth = makeUDP(Ethernet.toMACAddress("02:00:11:22:00:01"),
                devPort23.getMacAddr(), 0x0a00020c, 0x11223344, (short) 1111,
                (short) 2222, payload);
        ForwardInfo fInfo = routePacket(port23Id, eth);
        checkForwardInfo(fInfo, Action.FORWARD, uplinkId, uplinkGatewayAddr);
    }

    @Test
    public void testForwardBetweenDownlinks() {
        // Make a packet that comes in on port 23 (dlDst set to port 23's mac,
        // nwSrc inside 10.0.2.12/30) and has a nwDst that matches port 12
        // (i.e. inside 10.0.1.8/30).
        byte[] payload = new byte[] { (byte) 0x0a, (byte) 0x0b, (byte) 0x0c };
        UUID port23Id = PortDirectory.intTo32BitUUID(23);
        UUID port12Id = PortDirectory.intTo32BitUUID(12);
        L3DevicePort devPort23 = rtr.devicePorts.get(port23Id);
        Ethernet eth = makeUDP(Ethernet.toMACAddress("02:00:11:22:00:01"),
                devPort23.getMacAddr(), 0x0a00020d, 0x0a000109, (short) 1111,
                (short) 2222, payload);
        ForwardInfo fInfo = routePacket(port23Id, eth);
        checkForwardInfo(fInfo, Action.FORWARD, port12Id, 0x0a000109);
    }

    @Test
    public void testBlackholeRoute() {
        // Make a packet that comes in on the uplink port from a nw address in
        // 11.0.0.0/24 and with a nwAddr that matches port 21 - in 10.0.2.4/30.
        byte[] payload = new byte[] { (byte) 0x0a, (byte) 0x0b, (byte) 0x0c };
        UUID uplinkId = PortDirectory.intTo32BitUUID(1000);
        Ethernet eth = makeUDP(Ethernet.toMACAddress("02:00:11:22:00:01"),
                Ethernet.toMACAddress("02:00:11:22:00:01"), 0x0b0000ab,
                0x0a000207, (short) 1234, (short) 4321, payload);
        ForwardInfo fInfo = routePacket(uplinkId, eth);
        checkForwardInfo(fInfo, Action.BLACKHOLE, null, 0);
    }

    @Test
    public void testRejectRoute() {
        // Make a packet that comes in on the uplink port from a nw address in
        // 12.0.0.0/24 and with a nwAddr that matches port 21 - in 10.0.2.4/30.
        byte[] payload = new byte[] { (byte) 0x0a, (byte) 0x0b, (byte) 0x0c };
        UUID uplinkId = PortDirectory.intTo32BitUUID(1000);
        UUID port12Id = PortDirectory.intTo32BitUUID(12);
        Ethernet eth = makeUDP(Ethernet.toMACAddress("02:00:11:22:00:01"),
                Ethernet.toMACAddress("02:00:11:22:00:01"), 0x0c0000ab,
                0x0a000207, (short) 1234, (short) 4321, payload);
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
        byte[] payload = new byte[] { (byte) 0x0a, (byte) 0x0b, (byte) 0x0c };
        UUID port3Id = PortDirectory.intTo32BitUUID(3);
        Ethernet eth = makeUDP(Ethernet.toMACAddress("02:00:11:22:00:01"),
                Ethernet.toMACAddress("02:00:11:22:00:01"), 0x0a00000f,
                0x0a05000a, (short) 1234, (short) 4321, payload);
        ForwardInfo fInfo = routePacket(port3Id, eth);
        checkForwardInfo(fInfo, Action.NO_ROUTE, null, 0);

        // Try forwarding between down-links.
        // Make a packet that comes in on port 3 (dlDst set to port 3's mac,
        // nwSrc inside 10.0.0.12/30) and has a nwDst that matches port 12
        // (i.e. inside 10.0.1.8/30).
        UUID port12Id = PortDirectory.intTo32BitUUID(12);
        L3DevicePort devPort3 = rtr.devicePorts.get(port3Id);
        eth = makeUDP(Ethernet.toMACAddress("02:00:11:22:00:01"), devPort3
                .getMacAddr(), 0x0a00000e, 0x0a00010b, (short) 1111,
                (short) 2222, payload);
        fInfo = routePacket(port3Id, eth);
        checkForwardInfo(fInfo, Action.FORWARD, port12Id, 0x0a00010b);
        // Now have the local controller remove port 12.
        L3DevicePort devPort12 = rtr.devicePorts.get(port12Id);
        rtr.removePort(devPort12);
        fInfo = routePacket(port3Id, eth);
        checkForwardInfo(fInfo, Action.NO_ROUTE, null, 0);
    }

    static class ArpCompletedCallback implements Callback<byte[]> {
        List<byte[]> macsReturned = new ArrayList<byte[]>();

        @Override
        public void call(byte[] mac) {
            macsReturned.add(mac);
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
        arp.setTargetHardwareAddress(Ethernet.toMACAddress("00:00:00:00:00:00"));
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

    public static Ethernet makeArpReply(byte[] sha, byte[] tha, int spa, int tpa) {
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
        return pkt;
    }

    @Test(expected = IllegalArgumentException.class)
    public void testArpRequestNonLocalPort() {
        // Try to get the MAC for a nwAddr on port 11 (i.e. in 10.0.1.4/30).
        // Port 11 is not local to this controller (it was never added to the
        // router as a L3DevicePort). So the router can't ARP to it.
        ArpCompletedCallback cb = new ArpCompletedCallback();
        UUID port1Id = PortDirectory.intTo32BitUUID(11);
        rtr.getMacForIp(port1Id, 0x0a000105, cb);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testArpRequestNonLocalAddress() {
        // Try to get the MAC via port 12 for an address that isn't in that
        // port's local network segment (i.e. not in 10.0.1.8/30).
        ArpCompletedCallback cb = new ArpCompletedCallback();
        UUID port1Id = PortDirectory.intTo32BitUUID(12);
        rtr.getMacForIp(port1Id, 0x0a000105, cb);
    }

    @Test
    public void testArpRequestGeneration() {
        // Try to get the MAC for a nwAddr on port 2 (i.e. in 10.0.0.8/30).
        ArpCompletedCallback cb = new ArpCompletedCallback();
        UUID port2Id = PortDirectory.intTo32BitUUID(2);
        Assert.assertEquals(0, controllerStub.sentPackets.size());
        rtr.getMacForIp(port2Id, 0x0a00000a, cb);
        // There should now be an ARP request in the MockProtocolStub
        Assert.assertEquals(1, controllerStub.sentPackets.size());
        L3DevicePort devPort2 = rtr.devicePorts.get(port2Id);
        MockControllerStub.Packet pkt = controllerStub.sentPackets.get(0);
        Assert.assertEquals(1, pkt.actions.size());
        OFAction ofAction = new OFActionOutput(devPort2.getNum(), (short) 0);
        Assert.assertTrue(ofAction.equals(pkt.actions.get(0)));
        Assert.assertEquals(ControllerStub.UNBUFFERED_ID, pkt.bufferId);
        Ethernet expectedArp = makeArpRequest(devPort2.getMacAddr(), devPort2
                .getVirtualConfig().portAddr, 0x0a00000a);
        Assert.assertTrue(Arrays.equals(expectedArp.serialize(), pkt.data));

        // Verify that a second getMacForIp call doesn't trigger another ARP.
        rtr.getMacForIp(port2Id, 0x0a00000a, cb);
        Assert.assertEquals(1, controllerStub.sentPackets.size());

        // Verify that a getMacForIp call for a different address does trigger
        // another ARP request.
        reactor.incrementTime(2, TimeUnit.SECONDS);
        rtr.getMacForIp(port2Id, 0x0a000009, cb);
        Assert.assertEquals(2, controllerStub.sentPackets.size());
        pkt = controllerStub.sentPackets.get(1);
        Assert.assertEquals(1, pkt.actions.size());
        Assert.assertTrue(ofAction.equals(pkt.actions.get(0)));
        Assert.assertEquals(ControllerStub.UNBUFFERED_ID, pkt.bufferId);
        Ethernet expectedArp2 = makeArpRequest(devPort2.getMacAddr(), devPort2
                .getVirtualConfig().portAddr, 0x0a000009);
        Assert.assertTrue(Arrays.equals(expectedArp2.serialize(), pkt.data));

        // Verify that the ARP request for the first address is resent at 10s.
        reactor.incrementTime(7, TimeUnit.SECONDS);
        Assert.assertEquals(2, controllerStub.sentPackets.size());
        reactor.incrementTime(1, TimeUnit.SECONDS);
        Assert.assertEquals(3, controllerStub.sentPackets.size());
        pkt = controllerStub.sentPackets.get(2);
        Assert.assertEquals(1, pkt.actions.size());
        Assert.assertTrue(ofAction.equals(pkt.actions.get(0)));
        Assert.assertEquals(ControllerStub.UNBUFFERED_ID, pkt.bufferId);
        Assert.assertTrue(Arrays.equals(expectedArp.serialize(), pkt.data));

        // The ARP request for the second address is resent at 12s.
        reactor.incrementTime(2, TimeUnit.SECONDS);
        Assert.assertEquals(4, controllerStub.sentPackets.size());
        pkt = controllerStub.sentPackets.get(3);
        Assert.assertEquals(1, pkt.actions.size());
        Assert.assertTrue(ofAction.equals(pkt.actions.get(0)));
        Assert.assertEquals(ControllerStub.UNBUFFERED_ID, pkt.bufferId);
        Assert.assertTrue(Arrays.equals(expectedArp2.serialize(), pkt.data));

        // Verify that the first ARP times out at 60s and triggers the callback
        // twice (because we called getMacForIp twice for the first address).
        reactor.incrementTime(47, TimeUnit.SECONDS);
        Assert.assertEquals(0, cb.macsReturned.size());
        reactor.incrementTime(1, TimeUnit.SECONDS);
        Assert.assertEquals(2, cb.macsReturned.size());
        Assert.assertNull(cb.macsReturned.get(0));
        Assert.assertNull(cb.macsReturned.get(1));
        // At 62 seconds, the second ARP times out and invokes the callback.
        reactor.incrementTime(2, TimeUnit.SECONDS);
        Assert.assertEquals(3, cb.macsReturned.size());
        Assert.assertNull(cb.macsReturned.get(2));
    }

    @Test
    public void testArpReplyProcessing() {
        // Try to get the MAC for a nwAddr on port 2 (i.e. in 10.0.0.8/30).
        ArpCompletedCallback cb = new ArpCompletedCallback();
        UUID port2Id = PortDirectory.intTo32BitUUID(2);
        Assert.assertEquals(0, controllerStub.sentPackets.size());
        rtr.getMacForIp(port2Id, 0x0a00000a, cb);
        Assert.assertEquals(1, controllerStub.sentPackets.size());
        // Now create the ARP response form 10.0.0.10. Make up its mac address,
        // but use port 2's L2 and L3 addresses for the destination.
        L3DevicePort devPort2 = rtr.devicePorts.get(port2Id);
        byte[] hostMac = Ethernet.toMACAddress("02:00:11:22:33:44");
        Ethernet arpReplyPkt = makeArpReply(hostMac, devPort2.getMacAddr(),
                0x0a00000a, devPort2.getVirtualConfig().portAddr);
        ForwardInfo fInfo = routePacket(port2Id, arpReplyPkt);
        checkForwardInfo(fInfo, Action.CONSUMED, null, 0);
        // Verify that the callback was triggered with the correct mac.
        Assert.assertEquals(1, cb.macsReturned.size());
        Assert.assertTrue(Arrays.equals(hostMac, cb.macsReturned.get(0)));
        // The ARP should be stale after 1800 seconds. So any request after
        // that long should trigger another ARP request to refresh the entry.
        // However, the stale Mac can still be returned immediately.
        reactor.incrementTime(1800, TimeUnit.SECONDS);
        // At this point a call to getMacForIP won't trigger an ARP request.
        rtr.getMacForIp(port2Id, 0x0a00000a, cb);
        Assert.assertEquals(1, controllerStub.sentPackets.size());
        Assert.assertEquals(2, cb.macsReturned.size());
        Assert.assertTrue(Arrays.equals(hostMac, cb.macsReturned.get(1)));
        reactor.incrementTime(1, TimeUnit.SECONDS);
        // At this point a call to getMacForIP should trigger an ARP request.
        rtr.getMacForIp(port2Id, 0x0a00000a, cb);
        Assert.assertEquals(2, controllerStub.sentPackets.size());
        Assert.assertEquals(3, cb.macsReturned.size());
        Assert.assertTrue(Arrays.equals(hostMac, cb.macsReturned.get(2)));
        // Let's send an ARP response that refreshes the ARP cache entry.
        fInfo = routePacket(port2Id, arpReplyPkt);
        checkForwardInfo(fInfo, Action.CONSUMED, null, 0);
        // Now, if we wait 3599 seconds the cached response is stale but can
        // still be returned. Again, getMacForIp will trigger an ARP request.
        reactor.incrementTime(3599, TimeUnit.SECONDS);
        rtr.getMacForIp(port2Id, 0x0a00000a, cb);
        Assert.assertEquals(3, controllerStub.sentPackets.size());
        Assert.assertEquals(4, cb.macsReturned.size());
        Assert.assertTrue(Arrays.equals(hostMac, cb.macsReturned.get(3)));
        // Now we increment the time by 1 second and the cached response
        // is expired (completely removed). Therefore getMacForIp will trigger
        // another ARP request and the callback has to wait for the response.
        reactor.incrementTime(1, TimeUnit.SECONDS);
        rtr.getMacForIp(port2Id, 0x0a00000a, cb);
        Assert.assertEquals(4, controllerStub.sentPackets.size());
        // No new callbacks because it waits for the ARP response.
        Assert.assertEquals(4, cb.macsReturned.size());
        // Say someone asks for the same mac again.
        reactor.incrementTime(1, TimeUnit.SECONDS);
        rtr.getMacForIp(port2Id, 0x0a00000a, cb);
        // No new ARP request since only one second passed since the last one.
        Assert.assertEquals(4, controllerStub.sentPackets.size());
        // Again no new callbacks because it must wait for the ARP response.
        Assert.assertEquals(4, cb.macsReturned.size());
        // Now send the ARP reply and the callback should be triggered twice.
        fInfo = routePacket(port2Id, arpReplyPkt);
        checkForwardInfo(fInfo, Action.CONSUMED, null, 0);
        Assert.assertEquals(6, cb.macsReturned.size());
        Assert.assertTrue(Arrays.equals(hostMac, cb.macsReturned.get(4)));
        Assert.assertTrue(Arrays.equals(hostMac, cb.macsReturned.get(5)));
    }

    @Test
    public void testArpRequestProcessing() {
        // Checks that we correctly respond to a peer's ARP request with
        // an ARP reply.
        // Let's work with port 2 whose subnet is 10.0.0.0/24 and whose segment
        // is 10.0.0.8/30. Assume the requests are coming from 10.0.0.9.
        int arpSpa = 0x0a000009;
        UUID port2Id = PortDirectory.intTo32BitUUID(2);
        // Check for addresses inside the subnet but outside the segment.
        checkPeerArp(port2Id, arpSpa, 0x0a000005, true);
        checkPeerArp(port2Id, arpSpa, 0x0a00000e, true);
        checkPeerArp(port2Id, arpSpa, 0x0a000041, true);
        // Outside the subnet.
        checkPeerArp(port2Id, arpSpa, 0x0a000201, false);
        checkPeerArp(port2Id, arpSpa, 0x0a030201, false);
        // Inside the segment.
        checkPeerArp(port2Id, arpSpa, 0x0a000008, false);
        checkPeerArp(port2Id, arpSpa, 0x0a00000a, false);
        checkPeerArp(port2Id, arpSpa, 0x0a00000b, false);
        // Exactly the port's own address.
        checkPeerArp(port2Id, arpSpa, 0x0a000001, true);
    }

    /**
     * Check that the router does/doesn't reply to an ARP request for the given
     * target protocol address.
     * 
     * @param portId
     *            The port at which the ARP request is received.
     * @param arpTpa
     *            The network address that request wants to resolve.
     * @param arpSpa
     *            The network address issuing the request.
     * @param shouldReply
     *            Whether we expect the router to reply.
     */
    public void checkPeerArp(UUID portId, int arpSpa, int arpTpa,
            boolean shouldReply) {
        controllerStub.sentPackets.clear();
        byte[] arpSha = Ethernet.toMACAddress("02:aa:aa:aa:aa:01");
        Ethernet eth = makeArpRequest(arpSha, arpSpa, arpTpa);
        ForwardInfo fInfo = routePacket(portId, eth);
        checkForwardInfo(fInfo, Action.CONSUMED, null, 0);
        if (shouldReply) {
            Assert.assertEquals(1, controllerStub.sentPackets.size());
            MockControllerStub.Packet pkt = controllerStub.sentPackets.get(0);
            L3DevicePort devPort = rtr.devicePorts.get(portId);
            OFAction ofAction = new OFActionOutput(devPort.getNum(), (short) 0);
            Assert.assertTrue(ofAction.equals(pkt.actions.get(0)));
            Assert.assertEquals(ControllerStub.UNBUFFERED_ID, pkt.bufferId);
            Ethernet expArpReply = makeArpReply(devPort.getMacAddr(), arpSha,
                    arpTpa, arpSpa);
            Assert.assertTrue(Arrays.equals(expArpReply.serialize(), pkt.data));
        } else {
            Assert.assertEquals(0, controllerStub.sentPackets.size());
        }
    }

    @Ignore
    @Test
    public void testPortConfigChanges() {
        
    }
}
