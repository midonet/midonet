package com.midokura.midolman.layer3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.zookeeper.CreateMode;
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
import org.openflow.protocol.action.OFActionOutput;

import scala.actors.threadpool.Arrays;

import com.midokura.midolman.eventloop.MockReactor;
import com.midokura.midolman.layer3.Route.NextHop;
import com.midokura.midolman.openflow.ControllerStub;
import com.midokura.midolman.openflow.MidoMatch;
import com.midokura.midolman.openflow.MockControllerStub;
import com.midokura.midolman.openvswitch.MockOpenvSwitchDatabaseConnection;
import com.midokura.midolman.packets.Data;
import com.midokura.midolman.packets.Ethernet;
import com.midokura.midolman.packets.ICMP;
import com.midokura.midolman.packets.IPv4;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.MockDirectory;
import com.midokura.midolman.state.PortDirectory;
import com.midokura.midolman.state.PortLocationMap;
import com.midokura.midolman.state.PortToIntNwAddrMap;
import com.midokura.midolman.state.RouterDirectory;
import com.midokura.midolman.state.PortDirectory.LogicalRouterPortConfig;
import com.midokura.midolman.state.PortDirectory.MaterializedRouterPortConfig;
import com.midokura.midolman.state.RouterDirectory.RouterConfig;

public class TestNetworkController {

    private NetworkController networkCtrl;
    private List<OFPhysicalPort> phyPorts;
    private List<UUID> routerIds;
    private MockReactor reactor;
    private MockControllerStub controllerStub;
    PortToIntNwAddrMap portLocMap;

    @Before
    public void setUp() throws Exception {
        phyPorts = new ArrayList<OFPhysicalPort>();
        routerIds = new ArrayList<UUID>();
        reactor = new MockReactor();
        controllerStub = new MockControllerStub();

        Directory dir = new MockDirectory();
        dir.add("/midonet", null, CreateMode.PERSISTENT);
        dir.add("/midonet/ports", null, CreateMode.PERSISTENT);
        Directory portsSubdir = dir.getSubDirectory("/midonet/ports");
        PortDirectory portDir = new PortDirectory(portsSubdir);

        dir.add("/midonet/routers", null, CreateMode.PERSISTENT);
        Directory routersSubdir = dir.getSubDirectory("/midonet/routers");
        RouterDirectory routerDir = new RouterDirectory(routersSubdir);

        // Now build the Port to Location Map.
        UUID networkId = new UUID(1, 1);
        StringBuilder strBuilder = new StringBuilder("/midonet/networks");
        dir.add(strBuilder.toString(), null, CreateMode.PERSISTENT);
        strBuilder.append('/').append(networkId.toString());
        dir.add(strBuilder.toString(), null, CreateMode.PERSISTENT);
        strBuilder.append("/portLocation");
        dir.add(strBuilder.toString(), null, CreateMode.PERSISTENT);
        Directory portLocSubdir = dir.getSubDirectory(strBuilder.toString());
        portLocMap = new PortToIntNwAddrMap(portLocSubdir);

        // Now create the Open vSwitch database connection
        MockOpenvSwitchDatabaseConnection ovsdb = 
            new MockOpenvSwitchDatabaseConnection();

        // Now we can create the NetworkController itself.
        int localNwAddr = 0xc0a80104;
        int datapathId = 43;
        networkCtrl = new NetworkController(datapathId, networkId,
                5 /* greKey */, null, 60 * 1000, localNwAddr, routerDir,
                portDir, ovsdb, reactor, portLocMap);
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
        MaterializedRouterPortConfig portConfig;
        List<ReplicatedRoutingTable> rTables = new ArrayList<ReplicatedRoutingTable>();
        for (int i = 0; i < 3; i++) {
            UUID rtrId = new UUID(1234, i);
            UUID tenantId = new UUID(5678, i);
            routerIds.add(rtrId);
            RouterConfig routerConfig = new RouterConfig("Test Router " + i,
                    tenantId);
            routerDir.addRouter(rtrId, routerConfig);
            Directory tableDir = routerDir.getRoutingTableDirectory(rtrId);
            ReplicatedRoutingTable rTable = new ReplicatedRoutingTable(rtrId,
                    tableDir, CreateMode.PERSISTENT);
            rTables.add(rTable);

            // This router handles all traffic to 10.<i>.0.0/16
            int routerNw = 0x0a000000 + (i << 16);
            // With low weight, reject anything that is in this router's NW.
            // Routes associated with ports can override this.
            rt = new Route(0, 0, routerNw, 16, NextHop.REJECT, null, 0, 100,
                    null, null);
            routerDir.addRoute(rtrId, rt);
            // Manually add this route to the replicated routing table since
            // it's not associated with any port.
            rTable.addRoute(rt);

            // Add two ports to the router. Port-j should route to subnet
            // 10.<i>.<j>.0/24.
            for (int j = 0; j < 2; j++) {
                int portNw = routerNw + (j << 8);
                int portAddr = portNw + 1;
                short portNum = (short) (i * 10 + j);
                UUID portId = PortDirectory.intTo32BitUUID(portNum);
                routes.clear();
                rt = new Route(0, 0, portNw, 24, NextHop.PORT, portId, 0, 2,
                        null, null);
                routes.add(rt);
                portConfig = new MaterializedRouterPortConfig(rtrId, portNw,
                        24, portAddr, routes, portNw, 24, null);
                portDir.addPort(portId, portConfig);

                // Add even-numbered materialized ports to the local controller.
                if (0 == portNum % 2) {
                    ovsdb.setPortExternalId(datapathId, portNum, "midonet",
                            portId.toString());
                    OFPhysicalPort phyPort = new OFPhysicalPort();
                    phyPort.setPortNumber(portNum);
                    phyPort.setHardwareAddress(new byte[] { (byte) 0x02,
                            (byte) 0xee, (byte) 0xdd, (byte) 0xcc, (byte) 0xff,
                            (byte) portNum });
                    networkCtrl.onPortStatusTEMP(phyPort,
                            OFPortStatus.OFPortReason.OFPPR_ADD);
                    phyPorts.add(phyPort);
                } else {
                    // Odd-numbered ports are remote.
                    // Place port num x at 192.168.1.x
                    int underlayIp = 0xc0a80100 + portNum;
                    portLocMap.put(portId, underlayIp);
                    networkCtrl.peerIpToPortNum.put(underlayIp, portNum);
                    networkCtrl.tunnelPortNums.add(portNum);
                    // Manually add the remote port's route
                    rTable.addRoute(rt);
                }
            }
        }
        // Now add the logical links between router 0 and 1.
        UUID portOn0to1 = PortDirectory.intTo32BitUUID(331);
        UUID portOn1to0 = PortDirectory.intTo32BitUUID(332);
        // First from 0 to 1
        rt = new Route(0, 0, 0x0a010000, 16, NextHop.PORT, portOn0to1, 0, 2,
                null, null);
        routes.clear();
        routes.add(rt);
        LogicalRouterPortConfig logPortConfig = new LogicalRouterPortConfig(
                routerIds.get(0), 0xc0a80100, 30, 0xc0a80101, routes,
                portOn1to0);
        portDir.addPort(portOn0to1, logPortConfig);
        // Manually add this route since it no local controller owns it.
        rTables.get(0).addRoute(rt);
        // Now from 1 to 0. Note that this is router1's uplink.
        rt = new Route(0, 0, 0, 0, NextHop.PORT, portOn1to0, 0, 10, null,
        		null);
        routes.clear();
        routes.add(rt);
        logPortConfig = new LogicalRouterPortConfig(routerIds.get(1),
                0xc0a80100, 30, 0xc0a80102, routes, portOn0to1);
        portDir.addPort(portOn1to0, logPortConfig);
        // Manually add this route since it no local controller owns it.
        rTables.get(1).addRoute(rt);
        // Now add the logical links between router 0 and 2.
        UUID portOn0to2 = PortDirectory.intTo32BitUUID(333);
        UUID portOn2to0 = PortDirectory.intTo32BitUUID(334);
        // First from 0 to 2
        rt = new Route(0, 0, 0x0a020000, 16, NextHop.PORT, portOn0to2, 0, 2,
                null, null);
        routes.clear();
        routes.add(rt);
        logPortConfig = new LogicalRouterPortConfig(routerIds.get(0),
                0xc0a80100, 30, 0xc0a80101, routes, portOn2to0);
        portDir.addPort(portOn0to2, logPortConfig);
        // Manually add this route since it no local controller owns it.
        rTables.get(0).addRoute(rt);
        // Now from 2 to 0. Note that this is router2's uplink.
        rt = new Route(0, 0, 0, 0, NextHop.PORT, portOn2to0, 0, 10, null,
        		null);
        routes.clear();
        routes.add(rt);
        logPortConfig = new LogicalRouterPortConfig(routerIds.get(2),
                0xc0a80100, 30, 0xc0a80102, routes, portOn0to2);
        portDir.addPort(portOn2to0, logPortConfig);
        // Manually add this route since it no local controller owns it.
        rTables.get(2).addRoute(rt);

        // Finally, instead of giving router0 an uplink. Add a route that
        // drops anything that isn't going to router0's local or logical ports.
        rt = new Route(0, 0, 0x0a000000, 8, NextHop.BLACKHOLE, null, 0, 2, null,
        		null);
        routerDir.addRoute(routerIds.get(0), rt);
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
        OFPhysicalPort phyPort = phyPorts.get(0);
        Ethernet eth = TestRouter.makeUDP(
                Ethernet.toMACAddress("02:00:11:22:00:01"),
                phyPort.getHardwareAddress(), 0x0a000005, 0x0a040005,
                (short) 101, (short) 212, payload);
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
        OFPhysicalPort phyPort = phyPorts.get(0);
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
        OFPhysicalPort phyPort = phyPorts.get(0);
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
        OFPhysicalPort phyPort = phyPorts.get(0);
        byte[] mac = Ethernet.toMACAddress("02:00:11:22:00:01");
        Ethernet eth = TestRouter.makeUDP(mac, phyPort
                .getHardwareAddress(), 0x0a000005, 0x0b000005, (short) 101,
                (short) 212, payload);
        byte[] data = eth.serialize();
        networkCtrl.onPacketIn(565656, data.length, phyPort.getPortNumber(),
                data);
        // This time along with the 'drop' flow, we expect an ICMP N addressed
        // to the source of the UDP.
        Assert.assertEquals(1, controllerStub.sentPackets.size());
        MockControllerStub.Packet pkt = controllerStub.sentPackets.get(0);
        Assert.assertEquals(1, pkt.actions.size());
        OFAction ofAction = new OFActionOutput(phyPort.getPortNumber(), (short) 0);
        Assert.assertTrue(ofAction.equals(pkt.actions.get(0)));
        Assert.assertEquals(ControllerStub.UNBUFFERED_ID, pkt.bufferId);
        Assert.assertEquals(OFPort.OFPP_CONTROLLER.getValue(), pkt.inPort);
        checkICMP(ICMP.TYPE_UNREACH, ICMP.UNREACH_CODE.UNREACH_NET.toChar(),
                IPv4.class.cast(eth.getPayload()), phyPort.getHardwareAddress(),
                mac, 0x0a000001, 0x0a000005, pkt.data);

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
        OFPhysicalPort phyPort = phyPorts.get(1);
        byte[] mac = Ethernet
                .toMACAddress("02:00:11:22:00:01");
        Ethernet eth = TestRouter.makeUDP(mac, phyPort
                .getHardwareAddress(), 0x0a010005, 0x0a010305, (short) 101,
                (short) 212, payload);
        byte[] data = eth.serialize();
        networkCtrl.onPacketIn(11111, data.length, phyPort.getPortNumber(),
                data);
        // Along with the 'drop' flow, we expect an ICMP X.
        Assert.assertEquals(1, controllerStub.sentPackets.size());
        MockControllerStub.Packet pkt = controllerStub.sentPackets.get(0);
        Assert.assertEquals(1, pkt.actions.size());
        OFAction ofAction = new OFActionOutput(phyPort.getPortNumber(), (short) 0);
        Assert.assertTrue(ofAction.equals(pkt.actions.get(0)));
        Assert.assertEquals(ControllerStub.UNBUFFERED_ID, pkt.bufferId);
        Assert.assertEquals(OFPort.OFPP_CONTROLLER.getValue(), pkt.inPort);
        checkICMP(ICMP.TYPE_UNREACH, ICMP.UNREACH_CODE.UNREACH_FILTER_PROHIB.toChar(),
                IPv4.class.cast(eth.getPayload()), phyPort.getHardwareAddress(),
                mac, 0x0a010001, 0x0a010005, pkt.data);

        Assert.assertEquals(1, controllerStub.addedFlows.size());
        Assert.assertEquals(0, controllerStub.droppedPktBufIds.size());
        MidoMatch match = new MidoMatch();
        match.loadFromPacket(data, phyPort.getPortNumber());
        List<OFAction> actions = new ArrayList<OFAction>();
        checkInstalledFlow(controllerStub.addedFlows.get(0), match,
                NetworkController.IDLE_TIMEOUT_SECS, 11111, true, actions);
    }

    @Test
    public void testThreeRoutersLocalOutput() {
        // Send a packet to router1's first port to an address on router2's
        // first port.
        byte[] payload = new byte[] { (byte) 0xab, (byte) 0xcd, (byte) 0xef };
        OFPhysicalPort phyPortIn = phyPorts.get(1);
        OFPhysicalPort phyPortOut = phyPorts.get(2);
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
    }

    @Test
    public void testOneRouterOutputRemote() {
        // Send a packet to router2's first port to an address on router2's
        // second port.
        byte[] payload = new byte[] { (byte) 0xab, (byte) 0xcd, (byte) 0xef };
        OFPhysicalPort phyPortIn = phyPorts.get(2);
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
        UUID outPortId = PortDirectory.intTo32BitUUID(21);
        MidoMatch match = new MidoMatch();
        match.loadFromPacket(data, phyPortIn.getPortNumber());
        byte[] dlSrc = new byte[6];
        byte[] dlDst = new byte[6];
        NetworkController.setDlHeadersForTunnel(dlSrc, dlDst, 20, 21,
                0x0a020145);
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
    public void testPacketFromTunnel() {
        // Send a packet into the tunnel port corresponding to router2's
        // second port and destined for router2's first port.
        byte[] dlSrc = new byte[6];
        byte[] dlDst = new byte[6];
        short inPortNum = 21;
        short outPortNum = 20;
        NetworkController.setDlHeadersForTunnel(dlSrc, dlDst, inPortNum,
                outPortNum, 0x0a020011);
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
        OFPhysicalPort phyPortOut = phyPorts.get(2);
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
            byte[] dlSrc, byte[] dlDst, int nwSrc, int nwDst, byte[] icmpData){
        Ethernet eth = new Ethernet();
        eth.deserialize(icmpData,  0, icmpData.length);
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
        int length = triggerIPPkt.getHeaderLength()*4 + 8;
        if (length < data.length)
            data = Arrays.copyOf(data, length);
        Assert.assertTrue(Arrays.equals(data, icmp.getData()));
    }

    @Test
    public void testPacketFromTunnelArpTimeout() {
        // Send a packet into the tunnel port corresponding to router2's
        // second port and destined for router2's first port.
        byte[] dlSrc = new byte[6];
        byte[] dlDst = new byte[6];
        short inPortNum = 21;
        short outPortNum = 20;
        NetworkController.setDlHeadersForTunnel(dlSrc, dlDst, inPortNum,
                outPortNum, 0x0a020011);
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
        OFPhysicalPort phyPortOut = phyPorts.get(2);
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
                NetworkController.ICMP_TUNNEL, 21, 0x0a020133);
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
    public void testLocalPacketArpTimeout() {
        // Send a packet to router1's first port to an address on router2's
        // first port. Note that we traverse 3 routers.
        byte[] payload = new byte[] { (byte) 0xab, (byte) 0xcd, (byte) 0xef };
        OFPhysicalPort phyPortIn = phyPorts.get(1);
        OFPhysicalPort phyPortOut = phyPorts.get(2);
        byte[] mac = Ethernet
                .toMACAddress("02:00:11:22:00:01");
        Ethernet eth = TestRouter.makeUDP(mac, phyPortIn
                .getHardwareAddress(), 0x0a010005, 0x0a020008, (short) 101,
                (short) 212, payload);
        byte[] data = eth.serialize();
        networkCtrl.onPacketIn(123456, data.length,
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
                IPv4.class.cast(eth.getPayload()), phyPortIn.getHardwareAddress(),
                mac, 0xc0a80102, 0x0a010005, pkt.data);
        // Now check the Drop Flow.
        MidoMatch match = new MidoMatch();
        match.loadFromPacket(data, phyPortIn.getPortNumber());
        checkInstalledFlow(controllerStub.addedFlows.get(0), match,
                NetworkController.IDLE_TIMEOUT_SECS, 123456, true,
                new ArrayList<OFAction>());

    }

}
