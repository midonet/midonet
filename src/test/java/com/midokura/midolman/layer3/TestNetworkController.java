package com.midokura.midolman.layer3;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFPhysicalPort;
import org.openflow.protocol.OFPort;
import org.openflow.protocol.OFPortStatus;
import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.action.OFActionDataLayerDestination;
import org.openflow.protocol.action.OFActionDataLayerSource;
import org.openflow.protocol.action.OFActionNetworkLayerAddress;
import org.openflow.protocol.action.OFActionNetworkLayerDestination;
import org.openflow.protocol.action.OFActionNetworkLayerSource;
import org.openflow.protocol.action.OFActionOutput;
import org.openflow.protocol.action.OFActionTransportLayer;
import org.openflow.protocol.action.OFActionTransportLayerDestination;
import org.openflow.protocol.action.OFActionTransportLayerSource;

import scala.actors.threadpool.Arrays;

import com.midokura.midolman.eventloop.MockReactor;
import com.midokura.midolman.layer3.NetworkController.DecodedMacAddrs;
import com.midokura.midolman.layer3.Route.NextHop;
import com.midokura.midolman.openflow.ControllerStub;
import com.midokura.midolman.openflow.MidoMatch;
import com.midokura.midolman.openflow.MockControllerStub;
import com.midokura.midolman.openvswitch.MockOpenvSwitchDatabaseConnection;
import com.midokura.midolman.openvswitch.MockOpenvSwitchDatabaseConnection.GrePort;
import com.midokura.midolman.packets.ARP;
import com.midokura.midolman.packets.Data;
import com.midokura.midolman.packets.Ethernet;
import com.midokura.midolman.packets.ICMP;
import com.midokura.midolman.packets.IPv4;
import com.midokura.midolman.packets.UDP;
import com.midokura.midolman.rules.Condition;
import com.midokura.midolman.rules.ForwardNatRule;
import com.midokura.midolman.rules.LiteralRule;
import com.midokura.midolman.rules.NatTarget;
import com.midokura.midolman.rules.ReverseNatRule;
import com.midokura.midolman.rules.Rule;
import com.midokura.midolman.rules.RuleResult.Action;
import com.midokura.midolman.state.ChainZkManager;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.MockDirectory;
import com.midokura.midolman.state.PortDirectory;
import com.midokura.midolman.state.PortToIntNwAddrMap;
import com.midokura.midolman.state.PortZkManager;
import com.midokura.midolman.state.RouteZkManager;
import com.midokura.midolman.state.RouterZkManager;
import com.midokura.midolman.state.RuleZkManager;
import com.midokura.midolman.state.ZkNodeEntry;
import com.midokura.midolman.state.ZkPathManager;
import com.midokura.midolman.state.ZkStateSerializationException;
import com.midokura.midolman.state.ChainZkManager.ChainConfig;
import com.midokura.midolman.util.MockCache;
import com.midokura.midolman.util.Net;
import com.midokura.midolman.util.ShortUUID;

public class TestNetworkController {

    private NetworkController networkCtrl;
    private List<List<OFPhysicalPort>> phyPorts;
    private Map<Integer, Integer> portNumToIntId;
    private List<UUID> routerIds;
    private MockReactor reactor;
    private MockControllerStub controllerStub;
    private PortToIntNwAddrMap portLocMap;
    private PortZkManager portMgr;
    private RouteZkManager routeMgr;
    private ChainZkManager chainMgr;
    private RuleZkManager ruleMgr;
    private MockOpenvSwitchDatabaseConnection ovsdb;
    private long datapathId;
    private OFPhysicalPort uplinkPhyPort;
    private UUID uplinkId;
    private int uplinkGatewayAddr;
    private int uplinkPortAddr;
    private UUID portOn0to2;
    private UUID portOn2to0;
    private int rtr2LogPortNwAddr;
    private int rtr0to2LogPortNwAddr;

    @Before
    public void setUp() throws Exception {
        phyPorts = new ArrayList<List<OFPhysicalPort>>();
        portNumToIntId = new HashMap<Integer, Integer>();
        routerIds = new ArrayList<UUID>();
        reactor = new MockReactor();
        controllerStub = new MockControllerStub();

        String basePath = "/midolman";
        ZkPathManager pathMgr = new ZkPathManager(basePath);
        Directory dir = new MockDirectory();
        dir.add(pathMgr.getBasePath(), null, CreateMode.PERSISTENT);
        // Add the paths for rules and chains
        dir.add(pathMgr.getChainsPath(), null, CreateMode.PERSISTENT);
        dir.add(pathMgr.getRulesPath(), null, CreateMode.PERSISTENT);
        dir.add(pathMgr.getRoutersPath(), null, CreateMode.PERSISTENT);
        dir.add(pathMgr.getRoutesPath(), null, CreateMode.PERSISTENT);
        dir.add(pathMgr.getPortsPath(), null, CreateMode.PERSISTENT);
        portMgr = new PortZkManager(dir, basePath);
        routeMgr = new RouteZkManager(dir, basePath);
        chainMgr = new ChainZkManager(dir, basePath);
        ruleMgr = new RuleZkManager(dir, basePath);
        RouterZkManager routerMgr = new RouterZkManager(dir, basePath);

        // Now build the Port to Location Map.
        UUID networkId = new UUID(1, 1);
        StringBuilder strBuilder = new StringBuilder(basePath);
        strBuilder.append("/networks");
        dir.add(strBuilder.toString(), null, CreateMode.PERSISTENT);
        strBuilder.append('/').append(networkId.toString());
        dir.add(strBuilder.toString(), null, CreateMode.PERSISTENT);
        strBuilder.append("/portLocation");
        dir.add(strBuilder.toString(), null, CreateMode.PERSISTENT);
        Directory portLocSubdir = dir.getSubDirectory(strBuilder.toString());
        portLocMap = new PortToIntNwAddrMap(portLocSubdir);

        // Now create the Open vSwitch database connection
        ovsdb = new MockOpenvSwitchDatabaseConnection();

        // Now we can create the NetworkController itself.
        InetAddress localNwAddr = InetAddress.getByName("192.168.1.4"); // 0xc0a80104;
        datapathId = 43;
        networkCtrl = new NetworkController(datapathId, networkId,
                5 /* greKey */, portLocMap, (long) (60 * 1000), localNwAddr,
                portMgr, routerMgr, routeMgr, chainMgr, ruleMgr, ovsdb,
                reactor, new MockCache(), "midonet");
        networkCtrl.setControllerStub(controllerStub);

        /*
         * Create 3 routers such that: 1) router0 handles traffic to 10.0.0.0/16
         * 2) router1 handles traffic to 10.1.0.0/16 3) router2 handles traffic
         * to 10.2.0.0/16 4) router0 and router1 are connected via logical
         * ports. 5) router0 and router2 are connected via logical ports 6)
         * router0 is the default next hop for router1 and router2 7) router0
         * has a single uplink to the global internet.
         */
        Set<Route> routes = new HashSet<Route>();
        Route rt;
        PortDirectory.MaterializedRouterPortConfig portConfig;
        List<ReplicatedRoutingTable> rTables = new ArrayList<ReplicatedRoutingTable>();
        for (int i = 0; i < 3; i++) {
            phyPorts.add(new ArrayList<OFPhysicalPort>());
            UUID rtrId = routerMgr.create();
            routerIds.add(rtrId);

            Directory tableDir = routerMgr.getRoutingTableDirectory(rtrId);
            ReplicatedRoutingTable rTable = new ReplicatedRoutingTable(rtrId,
                    tableDir, CreateMode.PERSISTENT);
            rTables.add(rTable);

            // This router handles all traffic to 10.<i>.0.0/16
            int routerNw = 0x0a000000 + (i << 16);
            // With low weight, reject anything that is in this router's NW.
            // Routes associated with ports can override this.
            rt = new Route(0, 0, routerNw, 16, NextHop.REJECT, null, 0, 100,
                    null, rtrId);
            routeMgr.create(rt);
            // Manually add this route to the replicated routing table since
            // it's not associated with any port.
            rTable.addRoute(rt);

            // Add two ports to the router. Port-j should route to subnet
            // 10.<i>.<j>.0/24.
            for (int j = 0; j < 2; j++) {
                int portNw = routerNw + (j << 8);
                int portAddr = portNw + 1;
                short portNum = (short) (i * 10 + j);
                portConfig = new PortDirectory.MaterializedRouterPortConfig(
                        rtrId, portNw, 24, portAddr, null, portNw, 24, null);
                UUID portId = portMgr.create(portConfig);
                portNumToIntId
                        .put((int) portNum, ShortUUID.UUID32toInt(portId));
                routes.clear();
                rt = new Route(0, 0, portNw, 24, NextHop.PORT, portId, 0, 2,
                        null, rtrId);
                routeMgr.create(rt);
                routes.add(rt);

                OFPhysicalPort phyPort = new OFPhysicalPort();
                phyPorts.get(i).add(phyPort);
                phyPort.setPortNumber(portNum);
                phyPort.setHardwareAddress(new byte[] { (byte) 0x02,
                        (byte) 0xee, (byte) 0xdd, (byte) 0xcc, (byte) 0xff,
                        (byte) portNum });
                if (0 == portNum % 2) {
                    // Even-numbered ports will be local to the controller.
                    ovsdb.setPortExternalId(datapathId, portNum, "midonet",
                            portId.toString());
                    phyPort.setName("port" + Integer.toString(portNum));
                } else {
                    // Odd-numbered ports are remote. Place port num x at
                    // 192.168.1.x.
                    int underlayIp = 0xc0a80100 + portNum;
                    portLocMap.put(portId, underlayIp);
                    // The new port id in portLocMap should have resulted
                    // in a call to to the mock ovsdb to open a gre port.
                    phyPort.setName(networkCtrl.makeGREPortName(underlayIp));
                    GrePort expectGrePort = new GrePort(Long
                            .toString(datapathId), phyPort.getName(), Net
                            .convertIntAddressToString(underlayIp));
                    Assert.assertEquals(expectGrePort, ovsdb.addedGrePorts
                            .get(ovsdb.addedGrePorts.size() - 1));
                    // Manually add the remote port's route
                    rTable.addRoute(rt);
                }
                networkCtrl.onPortStatus(phyPort,
                        OFPortStatus.OFPortReason.OFPPR_ADD);
            }
        }
        // Now add the logical links between router 0 and 1.
        // First from 0 to 1
        PortDirectory.LogicalRouterPortConfig logPortConfig1 = new PortDirectory.LogicalRouterPortConfig(
                routerIds.get(0), 0xc0a80100, 30, 0xc0a80101, null, null);
        PortDirectory.LogicalRouterPortConfig logPortConfig2 = new PortDirectory.LogicalRouterPortConfig(
                routerIds.get(1), 0xc0a80100, 30, 0xc0a80102, null, null);
        ZkNodeEntry<UUID, UUID> idPair = portMgr.createLink(logPortConfig1,
                logPortConfig2);
        UUID portOn0to1 = idPair.key;
        UUID portOn1to0 = idPair.value;
        rt = new Route(0, 0, 0x0a010000, 16, NextHop.PORT, portOn0to1, 0, 2,
                null, routerIds.get(0));
        routeMgr.create(rt);
        rTables.get(0).addRoute(rt);
        rt = new Route(0, 0, 0, 0, NextHop.PORT, portOn1to0, 0, 10, null,
                routerIds.get(1));
        routeMgr.create(rt);
        rTables.get(1).addRoute(rt);
        // Now add the logical links between router 0 and 2.
        // First from 0 to 2
        rtr0to2LogPortNwAddr = 0xc0a80101;
        rtr2LogPortNwAddr = 0xc0a80102;
        logPortConfig1 = new PortDirectory.LogicalRouterPortConfig(routerIds
                .get(0), 0xc0a80100, 30, rtr0to2LogPortNwAddr, null, null);
        logPortConfig2 = new PortDirectory.LogicalRouterPortConfig(routerIds
                .get(2), 0xc0a80100, 30, rtr2LogPortNwAddr, null, null);
        idPair = portMgr.createLink(logPortConfig1, logPortConfig2);
        portOn0to2 = idPair.key;
        portOn2to0 = idPair.value;
        rt = new Route(0, 0, 0x0a020000, 16, NextHop.PORT, portOn0to2, 0, 2,
                null, routerIds.get(0));
        routeMgr.create(rt);
        rTables.get(0).addRoute(rt);
        // Now from 2 to 0. Note that this is router2's uplink.
        rt = new Route(0, 0, 0, 0, NextHop.PORT, portOn2to0, 0, 10, null,
                routerIds.get(2));
        routeMgr.create(rt);
        rTables.get(2).addRoute(rt);

        // For now, don't add an uplink. Instead add a route that drops anything
        // in 10.0.0.0/8 that isn't going to router0's local or logical ports.
        rt = new Route(0, 0, 0x0a000000, 8, NextHop.BLACKHOLE, null, 0, 2,
                null, routerIds.get(0));
        routeMgr.create(rt);
        // Manually add this route since it no local controller owns it.
        rTables.get(0).addRoute(rt);
    }

    public static void checkInstalledFlow(MockControllerStub.Flow flow,
            OFMatch match, short idleTimeoutSecs, int bufferId,
            boolean sendFlowRemove, List<OFAction> actions) {
        Assert.assertTrue(match.equals(flow.match));
        Assert.assertEquals(idleTimeoutSecs, flow.idleTimeoutSecs);
        Assert.assertEquals(bufferId, flow.bufferId);
        Assert.assertEquals(sendFlowRemove, flow.sendFlowRemove);
        Assert.assertEquals(actions.size(), flow.actions.size());
        for (int i = 0; i < actions.size(); i++)
            Assert.assertTrue(actions.get(i).equals(flow.actions.get(i)));
    }

    @Test
    public void testOneRouterBlackhole() {
        // Send a packet to router0's first materialized port to a destination
        // that's blackholed.
        byte[] payload = { (byte) 0xab, (byte) 0xcd, (byte) 0xef };
        OFPhysicalPort phyPort = phyPorts.get(0).get(0);
        Ethernet eth = TestRouter.makeUDP(Ethernet
                .toMACAddress("02:00:11:22:00:01"), phyPort
                .getHardwareAddress(), 0x0a000005, 0x0a040005, (short) 101,
                (short) 212, payload);
        byte[] data = eth.serialize();
        networkCtrl.onPacketIn(55, data.length, phyPort.getPortNumber(), data);
        Assert.assertEquals(0, controllerStub.sentPackets.size());
        Assert.assertEquals(1, controllerStub.addedFlows.size());
        Assert.assertEquals(0, controllerStub.droppedPktBufIds.size());
        MidoMatch match = new MidoMatch();
        match.loadFromPacket(data, phyPort.getPortNumber());
        List<OFAction> actions = new ArrayList<OFAction>();
        checkInstalledFlow(controllerStub.addedFlows.get(0), match,
                NetworkController.IDLE_TIMEOUT_SECS, 55, true, actions);
    }

    @Test
    public void testOneRouterPktConsumed() {
        // Send an ARP to router0's first materialized port. Note any ARP will
        // be consumed (but possibly not replied to).
        Ethernet eth = TestRouter.makeArpRequest(Ethernet
                .toMACAddress("02:aa:bb:aa:bb:0c"), 0x01234567, 0x76543210);
        byte[] data = eth.serialize();
        OFPhysicalPort phyPort = phyPorts.get(0).get(0);
        networkCtrl
                .onPacketIn(1001, data.length, phyPort.getPortNumber(), data);
        Assert.assertEquals(0, controllerStub.sentPackets.size());
        Assert.assertEquals(0, controllerStub.addedFlows.size());
        Assert.assertEquals(1, controllerStub.droppedPktBufIds.size());
        Assert.assertTrue(1001 == controllerStub.droppedPktBufIds.get(0));

        // Try again, this time with an unbuffered packet:
        networkCtrl.onPacketIn(ControllerStub.UNBUFFERED_ID, data.length,
                phyPort.getPortNumber(), data);
        Assert.assertEquals(0, controllerStub.sentPackets.size());
        Assert.assertEquals(0, controllerStub.addedFlows.size());
        // No new call to stub since nothing to be done to free unbuffered pkt.
        Assert.assertEquals(1, controllerStub.droppedPktBufIds.size());
    }

    @Test
    public void testOneRouterNotIPv4() {
        // This isn't a real IPv6 packet. So this will break if we add an
        // IPv6 class in com.midokura.midolman.packets.
        Data payload = new Data();
        payload.deserialize(new byte[100], 0, 100);
        OFPhysicalPort phyPort = phyPorts.get(0).get(0);
        Ethernet eth = new Ethernet();
        eth.setSourceMACAddress("02:ab:cd:ef:01:23");
        eth.setDestinationMACAddress(phyPort.getHardwareAddress());
        eth.setEtherType((short) 0x86dd); // IPv6
        byte[] data = eth.serialize();
        networkCtrl.onPacketIn(123456, data.length, phyPort.getPortNumber(),
                data);
        Assert.assertEquals(0, controllerStub.sentPackets.size());
        Assert.assertEquals(1, controllerStub.addedFlows.size());
        Assert.assertEquals(0, controllerStub.droppedPktBufIds.size());
        MidoMatch match = new MidoMatch();
        match.loadFromPacket(data, phyPort.getPortNumber());
        List<OFAction> actions = new ArrayList<OFAction>();
        checkInstalledFlow(controllerStub.addedFlows.get(0), match,
                NetworkController.IDLE_TIMEOUT_SECS, 123456, true, actions);
    }

    @Test
    public void testOneRouterNoRoute() {
        // Send a packet to router0's first materialized port to a destination
        // that has no route.
        byte[] payload = new byte[] { (byte) 0xab, (byte) 0xcd, (byte) 0xef };
        OFPhysicalPort phyPort = phyPorts.get(0).get(0);
        byte[] mac = Ethernet.toMACAddress("02:00:11:22:00:01");
        Ethernet eth = TestRouter.makeUDP(mac, phyPort.getHardwareAddress(),
                0x0a000005, 0x0b000005, (short) 101, (short) 212, payload);
        byte[] data = eth.serialize();
        networkCtrl.onPacketIn(565656, data.length, phyPort.getPortNumber(),
                data);
        // This time along with the 'drop' flow, we expect an ICMP N addressed
        // to the source of the UDP.
        Assert.assertEquals(1, controllerStub.sentPackets.size());
        MockControllerStub.Packet pkt = controllerStub.sentPackets.get(0);
        Assert.assertEquals(1, pkt.actions.size());
        OFAction ofAction = new OFActionOutput(phyPort.getPortNumber(),
                (short) 0);
        Assert.assertTrue(ofAction.equals(pkt.actions.get(0)));
        Assert.assertEquals(ControllerStub.UNBUFFERED_ID, pkt.bufferId);
        Assert.assertEquals(OFPort.OFPP_CONTROLLER.getValue(), pkt.inPort);
        checkICMP(ICMP.TYPE_UNREACH, ICMP.UNREACH_CODE.UNREACH_NET.toChar(),
                IPv4.class.cast(eth.getPayload()),
                phyPort.getHardwareAddress(), mac, 0x0a000001, 0x0a000005,
                pkt.data);

        Assert.assertEquals(0, controllerStub.droppedPktBufIds.size());
        Assert.assertEquals(1, controllerStub.addedFlows.size());
        MidoMatch match = new MidoMatch();
        match.loadFromPacket(data, phyPort.getPortNumber());
        List<OFAction> actions = new ArrayList<OFAction>();
        checkInstalledFlow(controllerStub.addedFlows.get(0), match,
                NetworkController.IDLE_TIMEOUT_SECS, 565656, true, actions);
    }

    @Test
    public void testOneRouterReject() {
        // Send a packet to router1's first materialized port to a destination
        // that will be rejected (in 10.1.0.0/16, not in 10.1.<0 or 1>.0/24).
        byte[] payload = new byte[] { (byte) 0xab, (byte) 0xcd, (byte) 0xef };
        OFPhysicalPort phyPort = phyPorts.get(1).get(0);
        byte[] mac = Ethernet.toMACAddress("02:00:11:22:00:01");
        Ethernet eth = TestRouter.makeUDP(mac, phyPort.getHardwareAddress(),
                0x0a010005, 0x0a010305, (short) 101, (short) 212, payload);
        byte[] data = eth.serialize();
        networkCtrl.onPacketIn(11111, data.length, phyPort.getPortNumber(),
                data);
        // Along with the 'drop' flow, we expect an ICMP X.
        Assert.assertEquals(1, controllerStub.sentPackets.size());
        MockControllerStub.Packet pkt = controllerStub.sentPackets.get(0);
        Assert.assertEquals(1, pkt.actions.size());
        OFAction ofAction = new OFActionOutput(phyPort.getPortNumber(),
                (short) 0);
        Assert.assertTrue(ofAction.equals(pkt.actions.get(0)));
        Assert.assertEquals(ControllerStub.UNBUFFERED_ID, pkt.bufferId);
        Assert.assertEquals(OFPort.OFPP_CONTROLLER.getValue(), pkt.inPort);
        checkICMP(ICMP.TYPE_UNREACH, ICMP.UNREACH_CODE.UNREACH_FILTER_PROHIB
                .toChar(), IPv4.class.cast(eth.getPayload()), phyPort
                .getHardwareAddress(), mac, 0x0a010001, 0x0a010005, pkt.data);

        Assert.assertEquals(1, controllerStub.addedFlows.size());
        Assert.assertEquals(0, controllerStub.droppedPktBufIds.size());
        MidoMatch match = new MidoMatch();
        match.loadFromPacket(data, phyPort.getPortNumber());
        List<OFAction> actions = new ArrayList<OFAction>();
        checkInstalledFlow(controllerStub.addedFlows.get(0), match,
                NetworkController.IDLE_TIMEOUT_SECS, 11111, true, actions);
    }

    @Test
    public void testMultipleRoutersLocalOutput() {
        // Send a packet to router1's first port to an address on router2's
        // first port.
        byte[] payload = new byte[] { (byte) 0xab, (byte) 0xcd, (byte) 0xef };
        OFPhysicalPort phyPortIn = phyPorts.get(1).get(0);
        OFPhysicalPort phyPortOut = phyPorts.get(2).get(0);
        Ethernet eth = TestRouter.makeUDP(Ethernet
                .toMACAddress("02:00:11:22:00:01"), phyPortIn
                .getHardwareAddress(), 0x0a010005, 0x0a020008, (short) 101,
                (short) 212, payload);
        byte[] data = eth.serialize();
        networkCtrl.onPacketIn(ControllerStub.UNBUFFERED_ID, data.length,
                phyPortIn.getPortNumber(), data);
        // The router will have to ARP, so no flows installed yet, but one
        // unbuffered packet should have been emitted.
        Assert.assertEquals(1, controllerStub.sentPackets.size());
        Assert.assertEquals(0, controllerStub.addedFlows.size());
        Assert.assertEquals(0, controllerStub.droppedPktBufIds.size());
        MockControllerStub.Packet pkt = controllerStub.sentPackets.get(0);
        Assert.assertEquals(1, pkt.actions.size());
        OFAction ofAction = new OFActionOutput(phyPortOut.getPortNumber(),
                (short) 0);
        Assert.assertTrue(ofAction.equals(pkt.actions.get(0)));
        Assert.assertEquals(ControllerStub.UNBUFFERED_ID, pkt.bufferId);
        byte[] arpData = TestRouter.makeArpRequest(
                phyPortOut.getHardwareAddress(), 0x0a020001, 0x0a020008)
                .serialize();
        Assert.assertArrayEquals(arpData, pkt.data);

        // Now send an ARP reply. The flow should be installed as a result,
        // and since the original packet was unbuffered, there should be an
        // additional packet in the sentPackets queue. Finally, this ARP reply
        // itself will be consumed, and since it's buffered, its bufferId will
        // should appear in the droppedPktBufIds list.
        byte[] mac = Ethernet.toMACAddress("02:dd:dd:dd:dd:01");
        arpData = TestRouter.makeArpReply(mac, phyPortOut.getHardwareAddress(),
                0x0a020008, 0x0a020001).serialize();
        networkCtrl.onPacketIn(8765, arpData.length,
                phyPortOut.getPortNumber(), arpData);
        Assert.assertEquals(1, controllerStub.droppedPktBufIds.size());
        Assert.assertTrue(8765 == controllerStub.droppedPktBufIds.get(0));
        Assert.assertEquals(1, controllerStub.addedFlows.size());
        MidoMatch match = new MidoMatch();
        match.loadFromPacket(data, phyPortIn.getPortNumber());
        List<OFAction> actions = new ArrayList<OFAction>();
        OFAction tmp = ofAction;
        ofAction = new OFActionDataLayerSource();
        ((OFActionDataLayerSource) ofAction).setDataLayerAddress(phyPortOut
                .getHardwareAddress());
        actions.add(ofAction);
        ofAction = new OFActionDataLayerDestination();
        ((OFActionDataLayerDestination) ofAction).setDataLayerAddress(mac);
        actions.add(ofAction);
        actions.add(tmp); // the Output action goes at the end.
        checkInstalledFlow(controllerStub.addedFlows.get(0), match,
                NetworkController.IDLE_TIMEOUT_SECS,
                ControllerStub.UNBUFFERED_ID, true, actions);

        Assert.assertEquals(2, controllerStub.sentPackets.size());
        pkt = controllerStub.sentPackets.get(1);
        Assert.assertEquals(ControllerStub.UNBUFFERED_ID, pkt.bufferId);
        Assert.assertEquals(phyPortIn.getPortNumber(), pkt.inPort);
        Assert.assertTrue(Arrays.equals(data, pkt.data));
        Assert.assertEquals(3, pkt.actions.size());
        for (int i = 0; i < 3; i++)
            Assert.assertTrue(actions.get(i).equals(pkt.actions.get(i)));

        // Send a packet to router0's first port to the same address on
        // router2's
        // first port. No ARP will be needed this time so the flow gets
        // installed immediately. No additional sent/dropped packets.
        phyPortIn = phyPorts.get(0).get(0);
        eth = TestRouter.makeUDP(Ethernet.toMACAddress("02:44:33:ff:22:01"),
                phyPortIn.getHardwareAddress(), 0x0a0000d4, 0x0a020008,
                (short) 101, (short) 212, payload);
        data = eth.serialize();
        networkCtrl.onPacketIn(9896, data.length, phyPortIn.getPortNumber(),
                data);
        // Assert.assertEquals(2, controllerStub.sentPackets.size());
        Assert.assertEquals(1, controllerStub.droppedPktBufIds.size());
        Assert.assertEquals(2, controllerStub.addedFlows.size());
        match = new MidoMatch();
        match.loadFromPacket(data, phyPortIn.getPortNumber());
        checkInstalledFlow(controllerStub.addedFlows.get(1), match,
                NetworkController.IDLE_TIMEOUT_SECS, 9896, true, actions);
    }

    @Test
    public void testOneRouterOutputRemote() {
        // Send a packet to router2's first port to an address on router2's
        // second port.
        byte[] payload = new byte[] { (byte) 0xab, (byte) 0xcd, (byte) 0xef };
        OFPhysicalPort phyPortIn = phyPorts.get(2).get(0);
        Ethernet eth = TestRouter.makeUDP(Ethernet
                .toMACAddress("02:00:11:22:00:01"), phyPortIn
                .getHardwareAddress(), 0x0a020012, 0x0a020145, (short) 101,
                (short) 212, payload);
        byte[] data = eth.serialize();
        networkCtrl.onPacketIn(999, data.length, phyPortIn.getPortNumber(),
                data);
        // No packets were dropped or sent (the processed packet was buffered
        // and therefore did not need to be sent manually.
        Assert.assertEquals(0, controllerStub.droppedPktBufIds.size());
        Assert.assertEquals(0, controllerStub.sentPackets.size());

        Assert.assertEquals(1, controllerStub.addedFlows.size());
        MidoMatch match = new MidoMatch();
        match.loadFromPacket(data, phyPortIn.getPortNumber());
        byte[] dlSrc = new byte[6];
        byte[] dlDst = new byte[6];
        NetworkController.setDlHeadersForTunnel(dlSrc, dlDst, portNumToIntId
                .get(20), portNumToIntId.get(21), 0x0a020145);
        List<OFAction> actions = new ArrayList<OFAction>();
        OFAction ofAction = new OFActionDataLayerSource();
        ((OFActionDataLayerSource) ofAction).setDataLayerAddress(dlSrc);
        actions.add(ofAction);
        ofAction = new OFActionDataLayerDestination();
        ((OFActionDataLayerDestination) ofAction).setDataLayerAddress(dlDst);
        actions.add(ofAction);
        // Router2's second port is reachable via the tunnel OF port number 21.
        ofAction = new OFActionOutput((short) 21, (short) 0);
        actions.add(ofAction); // the Output action goes at the end.
        checkInstalledFlow(controllerStub.addedFlows.get(0), match,
                NetworkController.IDLE_TIMEOUT_SECS, 999, true, actions);
    }

    @Test
    public void testThreeRouterOutputRemote() {
        // Send a packet to router1's first port to an address on router2's
        // second port.
        byte[] payload = new byte[] { (byte) 0xab, (byte) 0xcd };
        OFPhysicalPort phyPortIn = phyPorts.get(1).get(0);
        Ethernet eth = TestRouter.makeUDP(Ethernet
                .toMACAddress("02:00:11:22:00:01"), phyPortIn
                .getHardwareAddress(), 0x0a0100c5, 0x0a0201e4, (short) 101,
                (short) 212, payload);
        byte[] data = eth.serialize();
        networkCtrl.onPacketIn(37654, data.length, phyPortIn.getPortNumber(),
                data);
        // No packets were dropped or sent (the processed packet was buffered
        // and therefore did not need to be sent manually.
        Assert.assertEquals(0, controllerStub.droppedPktBufIds.size());
        Assert.assertEquals(0, controllerStub.sentPackets.size());

        Assert.assertEquals(1, controllerStub.addedFlows.size());
        MidoMatch match = new MidoMatch();
        match.loadFromPacket(data, phyPortIn.getPortNumber());
        byte[] dlSrc = new byte[6];
        byte[] dlDst = new byte[6];
        // The last ingress port is router2's logical port.
        NetworkController.setDlHeadersForTunnel(dlSrc, dlDst, ShortUUID
                .UUID32toInt(portOn2to0), portNumToIntId.get(21), 0x0a0201e4);
        List<OFAction> actions = new ArrayList<OFAction>();
        OFAction ofAction = new OFActionDataLayerSource();
        ((OFActionDataLayerSource) ofAction).setDataLayerAddress(dlSrc);
        actions.add(ofAction);
        ofAction = new OFActionDataLayerDestination();
        ((OFActionDataLayerDestination) ofAction).setDataLayerAddress(dlDst);
        actions.add(ofAction);
        // Router2's second port is reachable via the tunnel OF port number 21.
        ofAction = new OFActionOutput((short) 21, (short) 0);
        actions.add(ofAction); // the Output action goes at the end.
        checkInstalledFlow(controllerStub.addedFlows.get(0), match,
                NetworkController.IDLE_TIMEOUT_SECS, 37654, true, actions);
    }

    @Test
    public void testRemoteOutputTunnelDown() {
        // First, with the tunnel up.
        // Send a packet to router1's first port destined to an address on
        // router2's second port.
        byte[] payload = new byte[] { (byte) 0xab, (byte) 0xcd };
        OFPhysicalPort phyPortIn = phyPorts.get(1).get(0);
        OFPhysicalPort phyPortOut = phyPorts.get(2).get(1);
        byte[] dlSrc = Ethernet.toMACAddress("02:00:11:22:00:01");
        int nwSrc = 0x0a0100c5;
        int nwDst = 0x0a0201e4;
        Ethernet eth = TestRouter.makeUDP(dlSrc,
                phyPortIn.getHardwareAddress(), nwSrc, nwDst, (short) 101,
                (short) 212, payload);
        byte[] data = eth.serialize();
        networkCtrl.onPacketIn(22333, data.length, phyPortIn.getPortNumber(),
                data);
        // No packets were dropped or sent (the processed packet was buffered
        // and therefore did not need to be sent manually. A fow was installed.
        Assert.assertEquals(0, controllerStub.droppedPktBufIds.size());
        Assert.assertEquals(0, controllerStub.sentPackets.size());
        Assert.assertEquals(1, controllerStub.addedFlows.size());
        MidoMatch match = new MidoMatch();
        match.loadFromPacket(data, phyPortIn.getPortNumber());
        byte[] codedSrc = new byte[6];
        byte[] codedDst = new byte[6];
        // Encode the logical router port as the last ingress.
        NetworkController.setDlHeadersForTunnel(codedSrc, codedDst, ShortUUID
                .UUID32toInt(portOn2to0), portNumToIntId.get(21), nwDst);
        List<OFAction> actions = new ArrayList<OFAction>();
        OFAction ofAction = new OFActionDataLayerSource();
        ((OFActionDataLayerSource) ofAction).setDataLayerAddress(codedSrc);
        actions.add(ofAction);
        ofAction = new OFActionDataLayerDestination();
        ((OFActionDataLayerDestination) ofAction).setDataLayerAddress(codedDst);
        actions.add(ofAction);
        ofAction = new OFActionOutput(phyPortOut.getPortNumber(), (short) 0);
        actions.add(ofAction); // the Output action goes at the end.
        checkInstalledFlow(controllerStub.addedFlows.get(0), match,
                NetworkController.IDLE_TIMEOUT_SECS, 22333, true, actions);

        // Now bring the tunnel down.
        networkCtrl.onPortStatus(phyPortOut,
                OFPortStatus.OFPortReason.OFPPR_DELETE);
        // Send the packet again.
        networkCtrl.onPacketIn(22111, data.length, phyPortIn.getPortNumber(),
                data);
        // Since the tunnel is down, a temporary drop flow should have been
        // installed and an ICMP !N packet sent to the source of the trigger
        // packet.
        Assert.assertEquals(0, controllerStub.droppedPktBufIds.size());
        Assert.assertEquals(1, controllerStub.sentPackets.size());
        Assert.assertEquals(2, controllerStub.addedFlows.size());
        // Now check the Drop Flow.
        checkInstalledFlow(controllerStub.addedFlows.get(1), match,
                NetworkController.IDLE_TIMEOUT_SECS, 22111, true,
                new ArrayList<OFAction>());
        MockControllerStub.Packet pkt = controllerStub.sentPackets.get(0);
        Assert.assertEquals(1, pkt.actions.size());
        ofAction = new OFActionOutput(phyPortIn.getPortNumber(), (short) 0);
        Assert.assertTrue(ofAction.equals(pkt.actions.get(0)));
        Assert.assertEquals(ControllerStub.UNBUFFERED_ID, pkt.bufferId);
        Assert.assertEquals(OFPort.OFPP_CONTROLLER.getValue(), pkt.inPort);
        // The ICMP's source address is that of router2's logical port.
        checkICMP(ICMP.TYPE_UNREACH, ICMP.UNREACH_CODE.UNREACH_NET.toChar(),
                IPv4.class.cast(eth.getPayload()), phyPortIn
                        .getHardwareAddress(), dlSrc, rtr2LogPortNwAddr, nwSrc,
                pkt.data);
    }

    @Test
    public void testDeliverTunneledICMP() {
        // Send a packet into the tunnel port corresponding to router2's
        // second port and destined for router0's first port. The ethernet
        // header also encodes that it's an ICMP so that no flow is installed.
        byte[] dlSrc = new byte[6];
        byte[] dlDst = new byte[6];
        short outPortNum = 0;
        short tunnelPortNum = 21;
        int nwDst = 0x0a000041;
        NetworkController.setDlHeadersForTunnel(dlSrc, dlDst,
                NetworkController.ICMP_TUNNEL, portNumToIntId
                        .get((int) outPortNum), nwDst);
        byte[] payload = new byte[] { (byte) 0xab, (byte) 0xcd, (byte) 0xef };
        // The packet should look like it came from router0's logical port.
        // Note that the controller's logic trusts the ethernet headers and
        // doesn't inspect the contents of the packet to verify it's ICMP.
        Ethernet eth = TestRouter.makeUDP(dlSrc, dlDst, rtr2LogPortNwAddr,
                nwDst, (short) 101, (short) 212, payload);
        byte[] data = eth.serialize();
        networkCtrl.onPacketIn(8888, data.length, tunnelPortNum, data);
        // The router will have to ARP, so no flows installed yet, but one
        // unbuffered packet should have been emitted.
        Assert.assertEquals(0, controllerStub.addedFlows.size());
        Assert.assertEquals(0, controllerStub.droppedPktBufIds.size());
        Assert.assertEquals(1, controllerStub.sentPackets.size());
        MockControllerStub.Packet pkt = controllerStub.sentPackets.get(0);
        Assert.assertEquals(1, pkt.actions.size());
        OFAction ofAction = new OFActionOutput(outPortNum, (short) 0);
        Assert.assertTrue(ofAction.equals(pkt.actions.get(0)));
        Assert.assertEquals(ControllerStub.UNBUFFERED_ID, pkt.bufferId);
        OFPhysicalPort phyPortOut = phyPorts.get(0).get(0);
        byte[] arpData = TestRouter.makeArpRequest(
                phyPortOut.getHardwareAddress(), 0x0a000001, nwDst).serialize();
        Assert.assertArrayEquals(arpData, pkt.data);

        // Now send an ARP reply. The ICMP will be delivered as a result but
        // no flow will be installed.
        byte[] mac = Ethernet.toMACAddress("02:dd:dd:dd:dd:01");
        arpData = TestRouter.makeArpReply(mac, phyPortOut.getHardwareAddress(),
                nwDst, 0x0a000001).serialize();
        networkCtrl.onPacketIn(ControllerStub.UNBUFFERED_ID, arpData.length,
                phyPortOut.getPortNumber(), arpData);
        Assert.assertEquals(0, controllerStub.addedFlows.size());
        Assert.assertEquals(0, controllerStub.droppedPktBufIds.size());
        Assert.assertEquals(2, controllerStub.sentPackets.size());
        pkt = controllerStub.sentPackets.get(1);
        List<OFAction> actions = new ArrayList<OFAction>();
        OFAction tmp = ofAction;
        ofAction = new OFActionDataLayerSource();
        ((OFActionDataLayerSource) ofAction).setDataLayerAddress(phyPortOut
                .getHardwareAddress());
        actions.add(ofAction);
        ofAction = new OFActionDataLayerDestination();
        ((OFActionDataLayerDestination) ofAction).setDataLayerAddress(mac);
        actions.add(ofAction);
        actions.add(tmp); // the Output action goes at the end.
        Assert.assertEquals(3, pkt.actions.size());
        for (int i = 0; i < 3; i++)
            Assert.assertEquals(actions.get(i), pkt.actions.get(i));
        Assert.assertEquals(8888, pkt.bufferId);
        Assert.assertArrayEquals(data, pkt.data);
    }

    @Test
    public void testDontSendICMP() {
        // Only IPv4 packets trigger ICMPs.
        Ethernet eth = new Ethernet();
        byte[] dlSrc = Ethernet.toMACAddress("02:aa:aa:aa:aa:01");
        byte[] dlDst = Ethernet.toMACAddress("02:aa:aa:aa:aa:23");
        eth.setDestinationMACAddress(dlDst);
        eth.setSourceMACAddress(dlSrc);
        eth.setEtherType(ARP.ETHERTYPE);
        Assert.assertFalse(networkCtrl.canSendICMP(eth, null));

        // Make a normal UDP packet from a host on router2's second port
        // to a host on router0's second port. This can trigger ICMP.
        short srcPort = 21;
        short dstPort = 1;
        UUID dstPortId = ShortUUID
                .intTo32BitUUID(portNumToIntId.get((int)dstPort));
        int nwSrc = 0x0a020109;
        int nwDst = 0x0a00010d;
        byte[] payload = new byte[] { (byte) 0xab, (byte) 0xcd, (byte) 0xef };
        eth = TestRouter.makeUDP(dlSrc, dlDst, nwSrc, nwDst, (short) 2345,
                (short) 1221, payload);
        Assert.assertTrue(networkCtrl.canSendICMP(eth, null));
        Assert.assertTrue(networkCtrl.canSendICMP(eth, dstPortId));

        // Now change the destination address to router0's second port's
        // broadcast address.
        IPv4 origIpPkt = IPv4.class.cast(eth.getPayload());
        origIpPkt.setDestinationAddress(0x0a0001ff);
        // Still triggers ICMP if we don't supply the output port.
        Assert.assertTrue(networkCtrl.canSendICMP(eth, null));
        // Still triggers ICMP if we supply the wrong output port.
        Assert.assertTrue(networkCtrl.canSendICMP(eth, ShortUUID
                .intTo32BitUUID(portNumToIntId.get(10))));
        // Doesn't trigger ICMP if we supply the output port.
        Assert.assertFalse(networkCtrl.canSendICMP(eth, dstPortId));

        // Now change the destination address to a multicast address.
        origIpPkt.setDestinationAddress((225 << 24) + 0x000001ff);
        Assert.assertTrue(origIpPkt.isMcast());
        // Won't trigger ICMP regardless of the output port id.
        Assert.assertFalse(networkCtrl.canSendICMP(eth, null));
        Assert.assertFalse(networkCtrl.canSendICMP(eth, dstPortId));

        // Now change the network dst address back to normal and then change
        // the ethernet dst address to a multicast/broadcast.
        origIpPkt.setDestinationAddress(nwDst);
        Assert.assertTrue(networkCtrl.canSendICMP(eth, ShortUUID
                .intTo32BitUUID(portNumToIntId.get((int)dstPort))));
        // Use any address that has an odd number if first byte.
        byte[] mcastMac = Ethernet.toMACAddress("07:cd:cd:ab:ab:34");
        eth.setDestinationMACAddress(mcastMac);
        Assert.assertTrue(eth.isMcast());
        // Won't trigger ICMP regardless of the output port id.
        Assert.assertFalse(networkCtrl.canSendICMP(eth, null));
        Assert.assertFalse(networkCtrl.canSendICMP(eth, dstPortId));

        // Now change the ethernet dst address back to normal and then change
        // the ip packet's fragment offset.
        eth.setDestinationMACAddress(dlDst);
        Assert.assertTrue(networkCtrl.canSendICMP(eth, dstPortId));
        origIpPkt.setFragmentOffset((short) 3);
        // Won't trigger ICMP regardless of the output port id.
        Assert.assertFalse(networkCtrl.canSendICMP(eth, null));
        Assert.assertFalse(networkCtrl.canSendICMP(eth, dstPortId));

        // Change the fragment offset back to zero. Then make and ICMP error in
        // response to the original UDP.
        origIpPkt.setFragmentOffset((short) 0);
        Assert.assertTrue(networkCtrl.canSendICMP(eth, dstPortId));
        ICMP icmp = new ICMP();
        icmp.setUnreachable(ICMP.UNREACH_CODE.UNREACH_HOST, origIpPkt);
        // The icmp packet will be emitted from the lastIngress port:
        // router0's logical port to router2.
        IPv4 ip = new IPv4();
        ip.setSourceAddress(rtr0to2LogPortNwAddr);
        ip.setDestinationAddress(origIpPkt.getSourceAddress());
        ip.setProtocol(ICMP.PROTOCOL_NUMBER);
        ip.setPayload(icmp);
        eth = new Ethernet();
        eth.setEtherType(IPv4.ETHERTYPE);
        eth.setPayload(ip);
        eth.setSourceMACAddress("02:a1:b2:c3:d4:e5");
        eth.setDestinationMACAddress("02:a1:b2:c3:d4:e6");
        // ICMP errors can't trigger ICMP errors.
        Assert.assertTrue(networkCtrl.canSendICMP(eth, null));
        Assert.assertTrue(networkCtrl.canSendICMP(eth, dstPortId));

    }

    @Test
    public void testPacketFromTunnel() {
        // Send a packet into the tunnel port corresponding to router2's
        // second port and destined for router2's first port.
        byte[] dlSrc = new byte[6];
        byte[] dlDst = new byte[6];
        short inPortNum = 21;
        short outPortNum = 20;
        NetworkController.setDlHeadersForTunnel(dlSrc, dlDst, portNumToIntId
                .get((int) inPortNum), portNumToIntId.get((int) outPortNum),
                0x0a020011);
        byte[] payload = new byte[] { (byte) 0xab, (byte) 0xcd, (byte) 0xef };
        Ethernet eth = TestRouter.makeUDP(dlSrc, dlDst, 0x0a020133, 0x0a020011,
                (short) 101, (short) 212, payload);
        byte[] data = eth.serialize();
        networkCtrl.onPacketIn(32331, data.length, inPortNum, data);
        // The router will have to ARP, so no flows installed yet, but one
        // unbuffered packet should have been emitted.
        Assert.assertEquals(0, controllerStub.addedFlows.size());
        Assert.assertEquals(0, controllerStub.droppedPktBufIds.size());
        Assert.assertEquals(1, controllerStub.sentPackets.size());
        MockControllerStub.Packet pkt = controllerStub.sentPackets.get(0);
        Assert.assertEquals(1, pkt.actions.size());
        OFAction ofAction = new OFActionOutput(outPortNum, (short) 0);
        Assert.assertTrue(ofAction.equals(pkt.actions.get(0)));
        Assert.assertEquals(ControllerStub.UNBUFFERED_ID, pkt.bufferId);
        OFPhysicalPort phyPortOut = phyPorts.get(2).get(0);
        byte[] arpData = TestRouter.makeArpRequest(
                phyPortOut.getHardwareAddress(), 0x0a020001, 0x0a020011)
                .serialize();
        Assert.assertArrayEquals(arpData, pkt.data);

        // Now send an ARP reply. The flow should be installed as a result,
        // and since the tunneled packet was buffered, it won't result in
        // other packets in the sent/dropped packets queues. However,
        // the ARP itself will be consumed, and since we're making it buffered,
        // it will be in the dropped packets queue.
        byte[] mac = Ethernet.toMACAddress("02:dd:dd:dd:dd:01");
        arpData = TestRouter.makeArpReply(mac, phyPortOut.getHardwareAddress(),
                0x0a020011, 0x0a020001).serialize();
        networkCtrl.onPacketIn(8765, arpData.length,
                phyPortOut.getPortNumber(), arpData);
        Assert.assertEquals(1, controllerStub.sentPackets.size());
        Assert.assertEquals(1, controllerStub.droppedPktBufIds.size());
        Assert.assertTrue(8765 == controllerStub.droppedPktBufIds.get(0));
        Assert.assertEquals(1, controllerStub.addedFlows.size());
        MidoMatch match = new MidoMatch();
        match.loadFromPacket(data, inPortNum);
        List<OFAction> actions = new ArrayList<OFAction>();
        OFAction tmp = ofAction;
        ofAction = new OFActionDataLayerSource();
        ((OFActionDataLayerSource) ofAction).setDataLayerAddress(phyPortOut
                .getHardwareAddress());
        actions.add(ofAction);
        ofAction = new OFActionDataLayerDestination();
        ((OFActionDataLayerDestination) ofAction).setDataLayerAddress(mac);
        actions.add(ofAction);
        actions.add(tmp); // the Output action goes at the end.
        checkInstalledFlow(controllerStub.addedFlows.get(0), match,
                NetworkController.IDLE_TIMEOUT_SECS, 32331, true, actions);

        // Now if another packet comes in from the tunnel for the same
        // destination port and ip address, it will immediately result in a
        // flow being installed since the ARP is in the cache.
        reactor.incrementTime(10, TimeUnit.MINUTES);
        eth = TestRouter.makeUDP(dlSrc, dlDst, 0x0a0201ee, 0x0a020011,
                (short) 103, (short) 2122, payload);
        data = eth.serialize();
        networkCtrl.onPacketIn(4444, data.length, inPortNum, data);
        Assert.assertEquals(1, controllerStub.sentPackets.size());
        Assert.assertEquals(1, controllerStub.droppedPktBufIds.size());
        Assert.assertEquals(2, controllerStub.addedFlows.size());
        match = new MidoMatch();
        match.loadFromPacket(data, inPortNum);
        checkInstalledFlow(controllerStub.addedFlows.get(1), match,
                NetworkController.IDLE_TIMEOUT_SECS, 4444, true, actions);
    }

    public static void checkICMP(char type, char code, IPv4 triggerIPPkt,
            byte[] dlSrc, byte[] dlDst, int nwSrc, int nwDst, byte[] icmpData) {
        Ethernet eth = new Ethernet();
        eth.deserialize(icmpData, 0, icmpData.length);
        Assert.assertTrue(Arrays.equals(dlSrc, eth.getSourceMACAddress()));
        Assert.assertTrue(Arrays.equals(dlDst, eth.getDestinationMACAddress()));
        Assert.assertEquals(IPv4.ETHERTYPE, eth.getEtherType());
        IPv4 ip = IPv4.class.cast(eth.getPayload());
        Assert.assertEquals(nwSrc, ip.getSourceAddress());
        Assert.assertEquals(nwDst, ip.getDestinationAddress());
        Assert.assertEquals(ICMP.PROTOCOL_NUMBER, ip.getProtocol());
        ICMP icmp = ICMP.class.cast(ip.getPayload());
        Assert.assertEquals(type, icmp.getType());
        Assert.assertEquals(code, icmp.getCode());
        byte[] data = triggerIPPkt.serialize();
        int length = triggerIPPkt.getHeaderLength() * 4 + 8;
        if (length < data.length)
            data = Arrays.copyOf(data, length);
        Assert.assertTrue(Arrays.equals(data, icmp.getData()));
    }

    @Test
    public void testPacketFromTunnelMaterializedIngressArpTimeout() {
        // Send a packet into the tunnel port corresponding to router2's
        // second port and destined for router2's first port.
        byte[] dlSrc = new byte[6];
        byte[] dlDst = new byte[6];
        short inPortNum = 21;
        short outPortNum = 20;
        NetworkController.setDlHeadersForTunnel(dlSrc, dlDst, portNumToIntId
                .get((int) inPortNum), portNumToIntId.get((int) outPortNum),
                0x0a020011);
        byte[] payload = new byte[] { (byte) 0xab, (byte) 0xcd, (byte) 0xef };
        Ethernet eth = TestRouter.makeUDP(dlSrc, dlDst, 0x0a020133, 0x0a020011,
                (short) 101, (short) 212, payload);
        byte[] data = eth.serialize();
        networkCtrl.onPacketIn(32331, data.length, inPortNum, data);
        // The router will have to ARP, so no flows installed yet, but one
        // unbuffered packet should have been emitted.
        Assert.assertEquals(0, controllerStub.addedFlows.size());
        Assert.assertEquals(0, controllerStub.droppedPktBufIds.size());
        Assert.assertEquals(1, controllerStub.sentPackets.size());
        MockControllerStub.Packet pkt = controllerStub.sentPackets.get(0);
        Assert.assertEquals(1, pkt.actions.size());
        OFAction ofAction = new OFActionOutput(outPortNum, (short) 0);
        Assert.assertTrue(ofAction.equals(pkt.actions.get(0)));
        Assert.assertEquals(ControllerStub.UNBUFFERED_ID, pkt.bufferId);
        OFPhysicalPort phyPortOut = phyPorts.get(2).get(0);
        byte[] arpData = TestRouter.makeArpRequest(
                phyPortOut.getHardwareAddress(), 0x0a020001, 0x0a020011)
                .serialize();
        Assert.assertArrayEquals(arpData, pkt.data);
        // If we let 10 seconds go by without an ARP reply, another request
        // will have been emitted.
        reactor.incrementTime(Router.ARP_RETRY_MILLIS, TimeUnit.MILLISECONDS);
        Assert.assertEquals(0, controllerStub.addedFlows.size());
        Assert.assertEquals(0, controllerStub.droppedPktBufIds.size());
        Assert.assertEquals(2, controllerStub.sentPackets.size());
        pkt = controllerStub.sentPackets.get(1);
        Assert.assertEquals(1, pkt.actions.size());
        Assert.assertTrue(ofAction.equals(pkt.actions.get(0)));
        Assert.assertEquals(ControllerStub.UNBUFFERED_ID, pkt.bufferId);
        Assert.assertArrayEquals(arpData, pkt.data);
        // If we let another 50 seconds go by another ARP request will have
        // been emitted, but also an ICMP !H and a 'drop' flow installed.
        reactor.incrementTime(Router.ARP_TIMEOUT_MILLIS
                - Router.ARP_RETRY_MILLIS, TimeUnit.MILLISECONDS);
        Assert.assertEquals(1, controllerStub.addedFlows.size());
        Assert.assertEquals(0, controllerStub.droppedPktBufIds.size());
        Assert.assertEquals(4, controllerStub.sentPackets.size());
        pkt = controllerStub.sentPackets.get(2);
        Assert.assertEquals(1, pkt.actions.size());
        Assert.assertTrue(ofAction.equals(pkt.actions.get(0)));
        Assert.assertEquals(ControllerStub.UNBUFFERED_ID, pkt.bufferId);
        Assert.assertArrayEquals(arpData, pkt.data);
        // Now check the ICMP.
        pkt = controllerStub.sentPackets.get(3);
        Assert.assertEquals(1, pkt.actions.size());
        ofAction = new OFActionOutput(inPortNum, (short) 0);
        Assert.assertTrue(ofAction.equals(pkt.actions.get(0)));
        Assert.assertEquals(ControllerStub.UNBUFFERED_ID, pkt.bufferId);
        Assert.assertEquals(OFPort.OFPP_CONTROLLER.getValue(), pkt.inPort);
        dlSrc = new byte[6];
        dlDst = new byte[6];
        NetworkController.setDlHeadersForTunnel(dlSrc, dlDst,
                NetworkController.ICMP_TUNNEL, portNumToIntId.get(21),
                0x0a020133);
        checkICMP(ICMP.TYPE_UNREACH, ICMP.UNREACH_CODE.UNREACH_HOST.toChar(),
                IPv4.class.cast(eth.getPayload()), dlSrc, dlDst, 0x0a020101,
                0x0a020133, pkt.data);
        // Now check the Drop Flow.
        MidoMatch match = new MidoMatch();
        match.loadFromPacket(data, inPortNum);
        checkInstalledFlow(controllerStub.addedFlows.get(0), match,
                NetworkController.IDLE_TIMEOUT_SECS, 32331, true,
                new ArrayList<OFAction>());
    }

    @Test
    public void testPacketFromTunnelLogicalIngressArpTimeout() {
        // A packet that entered router0's second port (on a remote host) and
        // was destined for router2's first port (local) would come over the
        // tunnel corresponding to router0's first port. The hardware addresses
        // of the packet would encode router2's logical port as the last ingress
        // and router2's first port as the last egress.
        byte[] dlSrc = new byte[6];
        byte[] dlDst = new byte[6];
        short tunnelPort = 1;
        short outPort = 20;
        int dstNwAddr = 0x0a020034;
        // The source ip address must be on router0's second port.
        int srcNwAddr = 0x0a0001c5;
        NetworkController.setDlHeadersForTunnel(dlSrc, dlDst, ShortUUID
                .UUID32toInt(portOn2to0), portNumToIntId.get((int) outPort),
                dstNwAddr);
        byte[] payload = new byte[] { (byte) 0xab, (byte) 0xcd, (byte) 0xef };
        Ethernet eth = TestRouter.makeUDP(dlSrc, dlDst, srcNwAddr, dstNwAddr,
                (short) 101, (short) 212, payload);
        byte[] data = eth.serialize();
        networkCtrl.onPacketIn(32331, data.length, tunnelPort, data);
        // The router will have to ARP, so no flows installed yet, but one
        // unbuffered packet should have been emitted.
        Assert.assertEquals(0, controllerStub.addedFlows.size());
        Assert.assertEquals(0, controllerStub.droppedPktBufIds.size());
        Assert.assertEquals(1, controllerStub.sentPackets.size());
        MockControllerStub.Packet pkt = controllerStub.sentPackets.get(0);
        Assert.assertEquals(1, pkt.actions.size());
        OFAction ofAction = new OFActionOutput(outPort, (short) 0);
        Assert.assertTrue(ofAction.equals(pkt.actions.get(0)));
        Assert.assertEquals(ControllerStub.UNBUFFERED_ID, pkt.bufferId);
        OFPhysicalPort phyPortOut = phyPorts.get(2).get(0);
        byte[] arpData = TestRouter.makeArpRequest(
                phyPortOut.getHardwareAddress(), 0x0a020001, dstNwAddr)
                .serialize();
        Assert.assertArrayEquals(arpData, pkt.data);
        // If we let 60 seconds go by without an ARP reply, another ARP
        // will have been emitted as well as an ICMP !H, and a 'drop' flow
        // will have been installed.
        reactor.incrementTime(Router.ARP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        Assert.assertEquals(1, controllerStub.addedFlows.size());
        Assert.assertEquals(0, controllerStub.droppedPktBufIds.size());
        Assert.assertEquals(3, controllerStub.sentPackets.size());
        pkt = controllerStub.sentPackets.get(1);
        Assert.assertEquals(1, pkt.actions.size());
        Assert.assertTrue(ofAction.equals(pkt.actions.get(0)));
        Assert.assertEquals(ControllerStub.UNBUFFERED_ID, pkt.bufferId);
        Assert.assertArrayEquals(arpData, pkt.data);
        // Now check the ICMP.
        pkt = controllerStub.sentPackets.get(2);
        Assert.assertEquals(1, pkt.actions.size());
        ofAction = new OFActionOutput(tunnelPort, (short) 0);
        Assert.assertTrue(ofAction.equals(pkt.actions.get(0)));
        Assert.assertEquals(ControllerStub.UNBUFFERED_ID, pkt.bufferId);
        Assert.assertEquals(OFPort.OFPP_CONTROLLER.getValue(), pkt.inPort);
        dlSrc = new byte[6];
        dlDst = new byte[6];
        NetworkController.setDlHeadersForTunnel(dlSrc, dlDst,
                NetworkController.ICMP_TUNNEL, portNumToIntId
                        .get((int) tunnelPort), srcNwAddr);
        // Note that router2's logical port is the source of the ICMP
        checkICMP(ICMP.TYPE_UNREACH, ICMP.UNREACH_CODE.UNREACH_HOST.toChar(),
                IPv4.class.cast(eth.getPayload()), dlSrc, dlDst,
                rtr2LogPortNwAddr, srcNwAddr, pkt.data);
        // Now check the Drop Flow.
        MidoMatch match = new MidoMatch();
        match.loadFromPacket(data, tunnelPort);
        checkInstalledFlow(controllerStub.addedFlows.get(0), match,
                NetworkController.IDLE_TIMEOUT_SECS, 32331, true,
                new ArrayList<OFAction>());
    }

    @Test
    public void testLocalPacketArpTimeout() {
        // Send a packet to router1's first port to an address on router2's
        // first port. Note that we traverse 3 routers.
        byte[] payload = new byte[] { (byte) 0xab, (byte) 0xcd, (byte) 0xef };
        OFPhysicalPort phyPortIn = phyPorts.get(1).get(0);
        OFPhysicalPort phyPortOut = phyPorts.get(2).get(0);
        byte[] mac = Ethernet.toMACAddress("02:00:11:22:00:01");
        Ethernet eth = TestRouter.makeUDP(mac, phyPortIn.getHardwareAddress(),
                0x0a010005, 0x0a020008, (short) 101, (short) 212, payload);
        byte[] data = eth.serialize();
        networkCtrl.onPacketIn(123456, data.length, phyPortIn.getPortNumber(),
                data);
        // The router will have to ARP, so no flows installed yet, but one
        // unbuffered packet should have been emitted.
        Assert.assertEquals(1, controllerStub.sentPackets.size());
        Assert.assertEquals(0, controllerStub.addedFlows.size());
        Assert.assertEquals(0, controllerStub.droppedPktBufIds.size());
        MockControllerStub.Packet pkt = controllerStub.sentPackets.get(0);
        Assert.assertEquals(1, pkt.actions.size());
        OFAction ofAction = new OFActionOutput(phyPortOut.getPortNumber(),
                (short) 0);
        Assert.assertTrue(ofAction.equals(pkt.actions.get(0)));
        Assert.assertEquals(ControllerStub.UNBUFFERED_ID, pkt.bufferId);
        byte[] arpData = TestRouter.makeArpRequest(
                phyPortOut.getHardwareAddress(), 0x0a020001, 0x0a020008)
                .serialize();
        Assert.assertArrayEquals(arpData, pkt.data);

        // If we let 60 seconds go by another ARP request will have
        // been emitted, but also an ICMP !H and a 'drop' flow installed.
        reactor.incrementTime(Router.ARP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        Assert.assertEquals(1, controllerStub.addedFlows.size());
        Assert.assertEquals(0, controllerStub.droppedPktBufIds.size());
        Assert.assertEquals(3, controllerStub.sentPackets.size());
        pkt = controllerStub.sentPackets.get(1);
        Assert.assertEquals(1, pkt.actions.size());
        Assert.assertTrue(ofAction.equals(pkt.actions.get(0)));
        Assert.assertEquals(ControllerStub.UNBUFFERED_ID, pkt.bufferId);
        Assert.assertArrayEquals(arpData, pkt.data);
        // Now check the ICMP.
        pkt = controllerStub.sentPackets.get(2);
        Assert.assertEquals(1, pkt.actions.size());
        ofAction = new OFActionOutput(phyPortIn.getPortNumber(), (short) 0);
        Assert.assertTrue(ofAction.equals(pkt.actions.get(0)));
        Assert.assertEquals(ControllerStub.UNBUFFERED_ID, pkt.bufferId);
        Assert.assertEquals(OFPort.OFPP_CONTROLLER.getValue(), pkt.inPort);
        // The network source address is that of the port on router2 that
        // generated the ICMP (the logical port): 0xc0a80102.
        checkICMP(ICMP.TYPE_UNREACH, ICMP.UNREACH_CODE.UNREACH_HOST.toChar(),
                IPv4.class.cast(eth.getPayload()), phyPortIn
                        .getHardwareAddress(), mac, 0xc0a80102, 0x0a010005,
                pkt.data);
        // Now check the Drop Flow.
        MidoMatch match = new MidoMatch();
        match.loadFromPacket(data, phyPortIn.getPortNumber());
        checkInstalledFlow(controllerStub.addedFlows.get(0), match,
                NetworkController.IDLE_TIMEOUT_SECS, 123456, true,
                new ArrayList<OFAction>());

    }

    @Test
    public void testSetDlHeadersForTunnel() {
        byte[] dlSrc = new byte[6];
        byte[] dlDst = new byte[6];
        int inPort = 0xeeffccaa;
        int outPort = 0xf0e1d2c3;
        int nwAddr = 0xd4d4d4ff;
        NetworkController.setDlHeadersForTunnel(dlSrc, dlDst, inPort, outPort,
                nwAddr);
        DecodedMacAddrs decoded = NetworkController
                .decodeMacAddrs(dlSrc, dlDst);
        Assert.assertEquals(inPort, ShortUUID
                .UUID32toInt(decoded.lastIngressPortId));
        Assert.assertEquals(outPort, ShortUUID
                .UUID32toInt(decoded.lastEgressPortId));
        Assert.assertEquals(nwAddr, decoded.gatewayNwAddr);
    }

    private void addUplink() throws IOException, KeeperException,
            InterruptedException, ZkStateSerializationException {
        // Add an uplink to router0.
        uplinkId = ShortUUID.intTo32BitUUID(26473345);
        Set<Route> routes = new HashSet<Route>();
        int p2pUplinkNwAddr = 0xc0a80004;
        uplinkGatewayAddr = p2pUplinkNwAddr + 1;
        uplinkPortAddr = p2pUplinkNwAddr + 2;

        PortDirectory.PortConfig portConfig = new PortDirectory.MaterializedRouterPortConfig(
                routerIds.get(0), p2pUplinkNwAddr, 30, uplinkPortAddr, null,
                0xc0a80004, 30, null);
        uplinkId = portMgr.create(portConfig);
        Route rt = new Route(0, 0, 0, 0, NextHop.PORT, uplinkId,
                uplinkGatewayAddr, 1, null, routerIds.get(0));
        routeMgr.create(rt);
        ovsdb.setPortExternalId(datapathId, 897, "midonet", uplinkId.toString());
        uplinkPhyPort = new OFPhysicalPort();
        uplinkPhyPort.setPortNumber((short) 897);
        uplinkPhyPort.setName("uplinkPort");
        uplinkPhyPort.setHardwareAddress(new byte[] { (byte) 0x02, (byte) 0xad,
                (byte) 0xee, (byte) 0xda, (byte) 0xde, (byte) 0xed });
        networkCtrl.onPortStatus(uplinkPhyPort,
                OFPortStatus.OFPortReason.OFPPR_ADD);
    }

    @Test
    public void testDnat() throws IOException, KeeperException,
            InterruptedException, ZkStateSerializationException {
        // First add the uplink to router0.
        addUplink();
        // Now add a dnat rule to map 0x808e0005:80 to 0x0a010009:10080, an
        // address on router1's first port.
        int natPublicNwAddr = 0x808e0005;
        short natPublicTpPort = 80;
        int natPrivateNwAddr = 0x0a010009;
        short natPrivateTpPort = 10080;
        UUID chainId = chainMgr.create(new ChainConfig(Router.PRE_ROUTING,
                routerIds.get(0)));
        Set<NatTarget> nats = new HashSet<NatTarget>();
        nats.add(new NatTarget(natPrivateNwAddr, natPrivateNwAddr,
                natPrivateTpPort, natPrivateTpPort));
        Condition cond = new Condition();
        cond.inPortIds = new HashSet<UUID>();
        cond.inPortIds.add(uplinkId);
        cond.nwProto = UDP.PROTOCOL_NUMBER;
        cond.nwDstIp = natPublicNwAddr;
        cond.nwDstLength = 32;
        cond.tpDstStart = natPublicTpPort;
        cond.tpDstEnd = natPublicTpPort;
        Rule r = new ForwardNatRule(cond, Action.ACCEPT, chainId, 0,
                true /* dnat */, nats);
        ruleMgr.create(r);
        cond = new Condition();
        cond.outPortIds = new HashSet<UUID>();
        cond.outPortIds.add(uplinkId);
        cond.nwProto = UDP.PROTOCOL_NUMBER;
        cond.nwSrcIp = natPrivateNwAddr;
        cond.nwSrcLength = 32;
        cond.tpSrcStart = natPrivateTpPort;
        cond.tpSrcEnd = natPrivateTpPort;
        chainId = chainMgr.create(new ChainConfig(Router.POST_ROUTING,
                routerIds.get(0)));
        r = new ReverseNatRule(cond, Action.ACCEPT, chainId, 0, true /* dnat */);
        ruleMgr.create(r);

        // Now send a packet into the uplink directed to the natted addr/port.
        byte[] payload = new byte[] { (byte) 0xab, (byte) 0xcd, (byte) 0xef };
        OFPhysicalPort phyPortOut = phyPorts.get(1).get(0);
        int extNwAddr = 0xd2000004; // addr of original sender.
        short extTpPort = 3427; // port of original sender.
        byte[] extDlAddr = Ethernet.toMACAddress("02:aa:bb:cc:dd:01");
        Ethernet eth = TestRouter.makeUDP(extDlAddr, uplinkPhyPort
                .getHardwareAddress(), extNwAddr, natPublicNwAddr, extTpPort,
                natPublicTpPort, payload);
        byte[] data = eth.serialize();
        networkCtrl.onPacketIn(12121, data.length, uplinkPhyPort
                .getPortNumber(), data);
        // The router will have to ARP, so no flows installed yet, but one
        // unbuffered packet should have been emitted.
        Assert.assertEquals(1, controllerStub.sentPackets.size());
        Assert.assertEquals(0, controllerStub.addedFlows.size());
        Assert.assertEquals(0, controllerStub.droppedPktBufIds.size());
        MockControllerStub.Packet pkt = controllerStub.sentPackets.get(0);
        Assert.assertEquals(1, pkt.actions.size());
        OFAction ofAction = new OFActionOutput(phyPortOut.getPortNumber(),
                (short) 0);
        Assert.assertEquals(ofAction, pkt.actions.get(0));
        Assert.assertEquals(ControllerStub.UNBUFFERED_ID, pkt.bufferId);
        byte[] arpData = TestRouter.makeArpRequest(
                phyPortOut.getHardwareAddress(), 0x0a010001, natPrivateNwAddr)
                .serialize();
        Assert.assertArrayEquals(arpData, pkt.data);

        // Now send an ARP reply. The flow should be installed as a result,
        // and since the original packet and the ARP reply are both unbuffered,
        // there should be no additional packets in the sent/dropped queues.
        byte[] mac = Ethernet.toMACAddress("02:dd:33:33:dd:01");
        arpData = TestRouter.makeArpReply(mac, phyPortOut.getHardwareAddress(),
                natPrivateNwAddr, 0x0a010001).serialize();
        networkCtrl.onPacketIn(ControllerStub.UNBUFFERED_ID, arpData.length,
                phyPortOut.getPortNumber(), arpData);
        Assert.assertEquals(1, controllerStub.sentPackets.size());
        Assert.assertEquals(0, controllerStub.droppedPktBufIds.size());
        Assert.assertEquals(1, controllerStub.addedFlows.size());
        MidoMatch match = new MidoMatch();
        match.loadFromPacket(data, uplinkPhyPort.getPortNumber());
        List<OFAction> actions = new ArrayList<OFAction>();
        OFAction tmp = ofAction;
        ofAction = new OFActionDataLayerSource();
        ((OFActionDataLayerSource) ofAction).setDataLayerAddress(phyPortOut
                .getHardwareAddress());
        actions.add(ofAction);
        ofAction = new OFActionDataLayerDestination();
        ((OFActionDataLayerDestination) ofAction).setDataLayerAddress(mac);
        actions.add(ofAction);
        ofAction = new OFActionNetworkLayerDestination();
        ((OFActionNetworkLayerAddress) ofAction)
                .setNetworkAddress(natPrivateNwAddr);
        actions.add(ofAction);
        ofAction = new OFActionTransportLayerDestination();
        ((OFActionTransportLayer) ofAction).setTransportPort(natPrivateTpPort);
        actions.add(ofAction);
        actions.add(tmp); // the Output action goes at the end.
        checkInstalledFlow(controllerStub.addedFlows.get(0), match,
                NetworkController.IDLE_TIMEOUT_SECS, 12121, true, actions);

        // Now create a reply packet from the natted private addr/port.
        eth = TestRouter.makeUDP(mac, phyPortOut.getHardwareAddress(),
                natPrivateNwAddr, extNwAddr, natPrivateTpPort, extTpPort,
                payload);
        data = eth.serialize();
        networkCtrl.onPacketIn(13131, data.length, phyPortOut.getPortNumber(),
                data);
        // The router will have to ARP, so no additional flows installed yet,
        // but another unbuffered packet should have been emitted.
        Assert.assertEquals(2, controllerStub.sentPackets.size());
        Assert.assertEquals(1, controllerStub.addedFlows.size());
        Assert.assertEquals(0, controllerStub.droppedPktBufIds.size());
        pkt = controllerStub.sentPackets.get(1);
        Assert.assertEquals(1, pkt.actions.size());
        ofAction = new OFActionOutput(uplinkPhyPort.getPortNumber(), (short) 0);
        Assert.assertTrue(ofAction.equals(pkt.actions.get(0)));
        Assert.assertEquals(ControllerStub.UNBUFFERED_ID, pkt.bufferId);
        arpData = TestRouter.makeArpRequest(uplinkPhyPort.getHardwareAddress(),
                uplinkPortAddr, uplinkGatewayAddr).serialize();
        Assert.assertArrayEquals(arpData, pkt.data);

        // Now send an ARP reply. The flow should be installed as a result,
        // and since the original packet and the ARP reply are both unbuffered,
        // there should be no additional packets in the sent/dropped queues.
        byte[] uplinkGatewayMac = Ethernet.toMACAddress("02:dd:55:66:dd:01");
        arpData = TestRouter.makeArpReply(uplinkGatewayMac,
                uplinkPhyPort.getHardwareAddress(), uplinkGatewayAddr,
                uplinkPortAddr).serialize();
        networkCtrl.onPacketIn(ControllerStub.UNBUFFERED_ID, arpData.length,
                uplinkPhyPort.getPortNumber(), arpData);
        Assert.assertEquals(2, controllerStub.sentPackets.size());
        Assert.assertEquals(0, controllerStub.droppedPktBufIds.size());
        Assert.assertEquals(2, controllerStub.addedFlows.size());
        match = new MidoMatch();
        // The return packet's ingress is router1's first port.
        match.loadFromPacket(data, phyPortOut.getPortNumber());
        actions.clear();
        tmp = ofAction;
        ofAction = new OFActionDataLayerSource();
        ((OFActionDataLayerSource) ofAction).setDataLayerAddress(uplinkPhyPort
                .getHardwareAddress());
        actions.add(ofAction);
        ofAction = new OFActionDataLayerDestination();
        ((OFActionDataLayerDestination) ofAction)
                .setDataLayerAddress(uplinkGatewayMac);
        actions.add(ofAction);
        ofAction = new OFActionNetworkLayerSource();
        ((OFActionNetworkLayerAddress) ofAction)
                .setNetworkAddress(natPublicNwAddr);
        actions.add(ofAction);
        ofAction = new OFActionTransportLayerSource();
        ((OFActionTransportLayer) ofAction).setTransportPort(natPublicTpPort);
        actions.add(ofAction);
        actions.add(tmp); // the Output action goes at the end.
        checkInstalledFlow(controllerStub.addedFlows.get(1), match,
                NetworkController.IDLE_TIMEOUT_SECS, 13131, true, actions);
    }

    @Test
    public void testSnat() throws IOException, KeeperException,
            InterruptedException, ZkStateSerializationException {
        // First add the uplink to router0.
        addUplink();
        // Now add a snat rule to map source addresses on router2
        // (0x0a020000/16) to public address 0x808e0005 for any packet that
        // is going outside 0x0a000000/8.
        UUID chainId = chainMgr.create(new ChainConfig(Router.POST_ROUTING,
                routerIds.get(0)));
        int natPublicNwAddr = 0x808e0005;
        int natPrivateNwAddr = 0x0a020000;
        Set<NatTarget> nats = new HashSet<NatTarget>();
        NatTarget nat = new NatTarget(natPublicNwAddr, natPublicNwAddr,
                (short) 49152, (short) 65535);
        nats.add(nat);
        Condition cond = new Condition();
        cond.inPortIds = new HashSet<UUID>();
        cond.inPortIds.add(portOn0to2);
        cond.outPortIds = new HashSet<UUID>();
        cond.outPortIds.add(uplinkId);
        cond.nwProto = UDP.PROTOCOL_NUMBER;
        cond.nwSrcIp = natPrivateNwAddr;
        cond.nwDstLength = 16;
        cond.nwDstIp = 0x0a000000;
        cond.nwDstLength = 8;
        cond.nwDstInv = true;
        Rule r = new ForwardNatRule(cond, Action.ACCEPT, chainId, 0,
                false /* snat */, nats);
        ruleMgr.create(r);
        // Make another post-routing rule that drops packets that ingress the
        // uplink and would also egress the uplink.
        cond = new Condition();
        cond.inPortIds = new HashSet<UUID>();
        cond.inPortIds.add(uplinkId);
        cond.outPortIds = new HashSet<UUID>();
        cond.outPortIds.add(uplinkId);
        r = new LiteralRule(cond, Action.DROP, chainId, 1);
        ruleMgr.create(r);

        chainId = chainMgr.create(new ChainConfig(Router.PRE_ROUTING, routerIds
                .get(0)));
        cond = new Condition();
        cond.inPortIds = new HashSet<UUID>();
        cond.inPortIds.add(uplinkId);
        cond.nwProto = UDP.PROTOCOL_NUMBER;
        cond.nwSrcIp = 0x0a000000;
        cond.nwSrcLength = 16;
        cond.nwSrcInv = true;
        cond.nwDstIp = natPublicNwAddr;
        cond.nwDstLength = 32;
        r = new ReverseNatRule(cond, Action.ACCEPT, chainId, 0, false /* snat */);
        ruleMgr.create(r);

        // Send a packet into the uplink directed to the natted addr/port.
        // This packet will be dropped since it won't find any reverse snat
        // mapping.
        byte[] payload = new byte[] { (byte) 0xab, (byte) 0xcd, (byte) 0xef };
        OFPhysicalPort phyPortRtr2 = phyPorts.get(2).get(0); // 0x0a020000/24
        int extNwAddr = 0xd2000004; // addr of a host outside the network.
        short extTpPort = 3427; // port of host outside the network.
        byte[] uplinkGatewayMac = Ethernet.toMACAddress("02:dd:55:66:dd:01");
        Ethernet eth = TestRouter.makeUDP(uplinkGatewayMac, uplinkPhyPort
                .getHardwareAddress(), extNwAddr, natPublicNwAddr, extTpPort,
                (short) 45000, payload);
        byte[] data = eth.serialize();
        networkCtrl.onPacketIn(12121, data.length, uplinkPhyPort
                .getPortNumber(), data);
        // Look for the drop flow.
        Assert.assertEquals(0, controllerStub.sentPackets.size());
        Assert.assertEquals(0, controllerStub.droppedPktBufIds.size());
        Assert.assertEquals(1, controllerStub.addedFlows.size());
        MidoMatch match = new MidoMatch();
        match.loadFromPacket(data, uplinkPhyPort.getPortNumber());
        List<OFAction> actions = new ArrayList<OFAction>();
        checkInstalledFlow(controllerStub.addedFlows.get(0), match,
                NetworkController.IDLE_TIMEOUT_SECS, 12121, true, actions);

        // Send a packet into router2's port directed to the external addr/port.
        byte[] localMac = Ethernet.toMACAddress("02:89:67:45:23:01");
        int localNwAddr = 0x0a020008;
        short localTpPort = (short) 47000;
        eth = TestRouter.makeUDP(localMac, phyPortRtr2.getHardwareAddress(),
                localNwAddr, extNwAddr, localTpPort, extTpPort, payload);
        data = eth.serialize();
        networkCtrl.onPacketIn(13131, data.length, phyPortRtr2.getPortNumber(),
                data);
        // The router will have to ARP, so no new flows installed yet, but one
        // unbuffered packet should have been emitted.
        Assert.assertEquals(1, controllerStub.sentPackets.size());
        Assert.assertEquals(0, controllerStub.droppedPktBufIds.size());
        Assert.assertEquals(1, controllerStub.addedFlows.size());
        MockControllerStub.Packet pkt = controllerStub.sentPackets.get(0);
        Assert.assertEquals(1, pkt.actions.size());
        OFAction ofAction = new OFActionOutput(uplinkPhyPort.getPortNumber(),
                (short) 0);
        Assert.assertEquals(ofAction, pkt.actions.get(0));
        Assert.assertEquals(ControllerStub.UNBUFFERED_ID, pkt.bufferId);
        byte[] arpData = TestRouter.makeArpRequest(
                uplinkPhyPort.getHardwareAddress(), uplinkPortAddr,
                uplinkGatewayAddr).serialize();
        Assert.assertArrayEquals(arpData, pkt.data);

        // Now send an ARP reply. The flow should be installed as a result,
        // and since the original packet and the ARP reply are both unbuffered,
        // there should be no additional packets in the sent/dropped queues.
        arpData = TestRouter.makeArpReply(uplinkGatewayMac,
                uplinkPhyPort.getHardwareAddress(), uplinkGatewayAddr,
                uplinkPortAddr).serialize();
        networkCtrl.onPacketIn(ControllerStub.UNBUFFERED_ID, arpData.length,
                uplinkPhyPort.getPortNumber(), arpData);
        Assert.assertEquals(1, controllerStub.sentPackets.size());
        Assert.assertEquals(0, controllerStub.droppedPktBufIds.size());
        Assert.assertEquals(2, controllerStub.addedFlows.size());
        match = new MidoMatch();
        match.loadFromPacket(data, phyPortRtr2.getPortNumber());
        actions.clear();
        OFAction tmp = ofAction;
        ofAction = new OFActionDataLayerSource();
        ((OFActionDataLayerSource) ofAction).setDataLayerAddress(uplinkPhyPort
                .getHardwareAddress());
        actions.add(ofAction);
        ofAction = new OFActionDataLayerDestination();
        ((OFActionDataLayerDestination) ofAction)
                .setDataLayerAddress(uplinkGatewayMac);
        actions.add(ofAction);
        ofAction = new OFActionNetworkLayerSource();
        ((OFActionNetworkLayerAddress) ofAction)
                .setNetworkAddress(natPublicNwAddr);
        actions.add(ofAction);
        MockControllerStub.Flow flow = controllerStub.addedFlows.get(1);
        Assert.assertEquals(5, flow.actions.size());
        OFActionTransportLayerSource tpSrcAction = OFActionTransportLayerSource.class
                .cast(flow.actions.get(3));
        short natPublicTpPort = tpSrcAction.getTransportPort();
        Assert.assertTrue(nat.tpStart <= natPublicTpPort);
        Assert.assertTrue(natPublicTpPort <= nat.tpEnd);
        // Add this into the list of expected actions.
        actions.add(tpSrcAction);
        actions.add(tmp); // the Output action goes at the end.
        checkInstalledFlow(flow, match, NetworkController.IDLE_TIMEOUT_SECS,
                13131, true, actions);

        // Now create a reply packet from the external addr/port.
        eth = TestRouter.makeUDP(uplinkGatewayMac, uplinkPhyPort
                .getHardwareAddress(), extNwAddr, natPublicNwAddr, extTpPort,
                natPublicTpPort, payload);
        data = eth.serialize();
        networkCtrl.onPacketIn(14141, data.length, uplinkPhyPort
                .getPortNumber(), data);
        // The router will have to ARP, so no additional flows installed yet,
        // but another unbuffered packet should have been emitted.
        Assert.assertEquals(2, controllerStub.sentPackets.size());
        Assert.assertEquals(0, controllerStub.droppedPktBufIds.size());
        Assert.assertEquals(2, controllerStub.addedFlows.size());
        pkt = controllerStub.sentPackets.get(1);
        Assert.assertEquals(1, pkt.actions.size());
        ofAction = new OFActionOutput(phyPortRtr2.getPortNumber(), (short) 0);
        Assert.assertTrue(ofAction.equals(pkt.actions.get(0)));
        Assert.assertEquals(ControllerStub.UNBUFFERED_ID, pkt.bufferId);
        arpData = TestRouter.makeArpRequest(phyPortRtr2.getHardwareAddress(),
                0x0a020001, localNwAddr).serialize();
        Assert.assertArrayEquals(arpData, pkt.data);

        // Now send an ARP reply. The flow should be installed as a result,
        // and since the original packet and the ARP reply are both unbuffered,
        // there should be no additional packets in the sent/dropped queues.
        arpData = TestRouter.makeArpReply(localMac,
                phyPortRtr2.getHardwareAddress(), localNwAddr, 0x0a020001)
                .serialize();
        networkCtrl.onPacketIn(ControllerStub.UNBUFFERED_ID, arpData.length,
                phyPortRtr2.getPortNumber(), arpData);
        Assert.assertEquals(2, controllerStub.sentPackets.size());
        Assert.assertEquals(0, controllerStub.droppedPktBufIds.size());
        Assert.assertEquals(3, controllerStub.addedFlows.size());
        match = new MidoMatch();
        match.loadFromPacket(data, uplinkPhyPort.getPortNumber());
        actions.clear();
        tmp = ofAction;
        ofAction = new OFActionDataLayerSource();
        ((OFActionDataLayerSource) ofAction).setDataLayerAddress(phyPortRtr2
                .getHardwareAddress());
        actions.add(ofAction);
        ofAction = new OFActionDataLayerDestination();
        ((OFActionDataLayerDestination) ofAction).setDataLayerAddress(localMac);
        actions.add(ofAction);
        ofAction = new OFActionNetworkLayerDestination();
        ((OFActionNetworkLayerAddress) ofAction).setNetworkAddress(localNwAddr);
        actions.add(ofAction);
        ofAction = new OFActionTransportLayerDestination();
        ((OFActionTransportLayer) ofAction).setTransportPort(localTpPort);
        actions.add(ofAction);
        actions.add(tmp); // the Output action goes at the end.
        checkInstalledFlow(controllerStub.addedFlows.get(2), match,
                NetworkController.IDLE_TIMEOUT_SECS, 14141, true, actions);
    }

}
