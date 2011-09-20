// Copyright 2011 Midokura Inc.

package com.midokura.midolman;

import java.io.File;
import java.io.FileOutputStream;
import java.net.InetAddress;
import java.util.concurrent.Executors;
import java.util.UUID;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.configuration.HierarchicalINIConfiguration;
import org.apache.commons.configuration.SubnodeConfiguration;
import org.apache.zookeeper.CreateMode;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.action.OFActionOutput;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFPort;
import org.openflow.protocol.OFPhysicalPort;

import com.midokura.midolman.eventloop.Reactor;
import com.midokura.midolman.eventloop.SelectLoop;
import com.midokura.midolman.openflow.MidoMatch;
import com.midokura.midolman.openflow.MockControllerStub;
import com.midokura.midolman.openvswitch.MockOpenvSwitchDatabaseConnection;
import com.midokura.midolman.packets.Ethernet;
import com.midokura.midolman.packets.ICMP;
import com.midokura.midolman.packets.IPv4;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.MacPortMap;
import com.midokura.midolman.state.MockDirectory;
import com.midokura.midolman.state.PortToIntNwAddrMap;
import com.midokura.midolman.util.Net;


public class TestBridgeController {

    private BridgeController controller;

    private Directory portLocDir, macPortDir;
    private PortToIntNwAddrMap portLocMap;
    private MacPortMap macPortMap;
    private MockOpenvSwitchDatabaseConnection ovsdb;
    private InetAddress publicIp;
    int dp_id = 43;
    MockControllerStub controllerStub;

    // MACs:  8 normal addresses, and one multicast.
    // TODO: Make this less ugly.
    byte macList[][] = 
      {{(byte)0x00, (byte)0x22, (byte)0x33, (byte)0xEE, (byte)0xEE, (byte)0x00},
       {(byte)0x00, (byte)0x22, (byte)0x33, (byte)0xEE, (byte)0xEE, (byte)0x01},
       {(byte)0x00, (byte)0x22, (byte)0x33, (byte)0xEE, (byte)0xEE, (byte)0x02},
       {(byte)0x00, (byte)0x22, (byte)0x33, (byte)0xEE, (byte)0xEE, (byte)0x03},
       {(byte)0x00, (byte)0x22, (byte)0x33, (byte)0xEE, (byte)0xEE, (byte)0x04},
       {(byte)0x00, (byte)0x22, (byte)0x33, (byte)0xEE, (byte)0xEE, (byte)0x05},
       {(byte)0x00, (byte)0x22, (byte)0x33, (byte)0xEE, (byte)0xEE, (byte)0x06},
       {(byte)0x00, (byte)0x22, (byte)0x33, (byte)0xEE, (byte)0xEE, (byte)0x07},
       {(byte)0x01, (byte)0xEE, (byte)0xEE, (byte)0xEE, (byte)0xEE, (byte)0xEE}
      };

    // Packets
    Ethernet packet01 = makePacket(macList[0], macList[1]);
    Ethernet packet03 = makePacket(macList[0], macList[3]);
    Ethernet packet04 = makePacket(macList[0], macList[4]);
    Ethernet packet0MC = makePacket(macList[0], macList[8]);
    Ethernet packet10 = makePacket(macList[1], macList[0]);
    Ethernet packet13 = makePacket(macList[1], macList[3]);
    Ethernet packet15 = makePacket(macList[1], macList[5]);
    Ethernet packet20 = makePacket(macList[2], macList[0]);
    Ethernet packet26 = makePacket(macList[2], macList[6]);
    Ethernet packet27 = makePacket(macList[2], macList[7]);
    Ethernet packet70 = makePacket(macList[7], macList[0]);
    Ethernet packetMC0 = makePacket(macList[8], macList[0]);

    // Flow matches
    MidoMatch flowmatch01 = makeFlowMatch(macList[0], macList[1]);
    MidoMatch flowmatch03 = makeFlowMatch(macList[0], macList[3]);
    MidoMatch flowmatch04 = makeFlowMatch(macList[0], macList[4]);
    MidoMatch flowmatch0MC = makeFlowMatch(macList[0], macList[8]);
    MidoMatch flowmatch10 = makeFlowMatch(macList[1], macList[0]);
    MidoMatch flowmatch13 = makeFlowMatch(macList[1], macList[3]);
    MidoMatch flowmatch15 = makeFlowMatch(macList[1], macList[5]);
    MidoMatch flowmatch20 = makeFlowMatch(macList[2], macList[0]);
    MidoMatch flowmatch26 = makeFlowMatch(macList[2], macList[6]);
    MidoMatch flowmatch27 = makeFlowMatch(macList[2], macList[7]);
    MidoMatch flowmatch70 = makeFlowMatch(macList[7], macList[0]);
    MidoMatch flowmatchMC0 = makeFlowMatch(macList[8], macList[0]);


    OFPhysicalPort[] phyPorts = {
        new OFPhysicalPort(), new OFPhysicalPort(), new OFPhysicalPort(),
        new OFPhysicalPort(), new OFPhysicalPort(), new OFPhysicalPort(),
        new OFPhysicalPort(), new OFPhysicalPort() };

    String[] peerStrList = { "192.168.1.50",    // local
                             "192.168.1.50",    // local
                             "192.168.1.50",    // local
                             "192.168.1.53",
                             "192.168.1.54",
                             "192.168.1.55",
                             "192.168.1.55",
                             "192.168.1.55" };

    static Ethernet makePacket(byte[] srcMac, byte[] dstMac) {
        ICMP icmpPacket = new ICMP();
        icmpPacket.setEchoRequest((short)0, (short)0,
                                  "echoechoecho".getBytes());
        IPv4 ipPacket = new IPv4();
        ipPacket.setPayload(icmpPacket);
        ipPacket.setProtocol(ICMP.PROTOCOL_NUMBER);
        ipPacket.setSourceAddress(0x11111111);
        ipPacket.setDestinationAddress(0x21212121);
        Ethernet packet = new Ethernet();
        packet.setPayload(ipPacket);
        packet.setDestinationMACAddress(dstMac);
        packet.setSourceMACAddress(srcMac);
        packet.setEtherType(IPv4.ETHERTYPE);
        return packet;
    }

    static MidoMatch makeFlowMatch(byte[] srcMac, byte[] dstMac) {
        MidoMatch match = new MidoMatch();
        match.setDataLayerDestination(dstMac);
        match.setDataLayerSource(srcMac);
        match.setDataLayerType(IPv4.ETHERTYPE);
        match.setInputPort((short)1);
        match.setNetworkDestination(0x21212121);
        match.setNetworkSource(0x11111111);
        match.setNetworkProtocol(ICMP.PROTOCOL_NUMBER);
        match.setTransportSource((short)ICMP.TYPE_ECHO_REQUEST);
        match.setTransportDestination((short)0);
        match.setDataLayerVirtualLan((short)0);    
        // Python sets dl_vlan=0xFFFF, but packets.Ethernet.vlanID is 0 
        // for no VLAN in use.
        // TODO:  Which is really proper, 0 or 0xFFFF ?
        match.setDataLayerVirtualLanPriorityCodePoint((byte)0);
        match.setNetworkTypeOfService((byte)0);
        return match;
    }

    @Before
    public void setUp() throws java.lang.Exception {
        ovsdb = new MockOpenvSwitchDatabaseConnection();
        publicIp = InetAddress.getByAddress(
                       new byte[] { (byte)192, (byte)168, (byte)1, (byte)50 });

        // 'util_setup_controller_test':
        // Create portUuids:
        //      Seven random UUIDs, and an eighth being a dup of the seventh.
        UUID portUuids[] = { UUID.randomUUID(), UUID.randomUUID(),
                             UUID.randomUUID(), UUID.randomUUID(),
                             UUID.randomUUID(), UUID.randomUUID(),
                             UUID.randomUUID(), UUID.randomUUID() };
        portUuids[7] = portUuids[6];

        // Register local ports (ports 0, 1, 2) into datapath in ovsdb.
        ovsdb.setPortExternalId(dp_id, 0, "midonet", portUuids[0].toString());
        ovsdb.setPortExternalId(dp_id, 1, "midonet", portUuids[1].toString());
        ovsdb.setPortExternalId(dp_id, 2, "midonet", portUuids[2].toString());

        // Configuration for the Manager.
        String configString = 
            "[midolman]\n" +
            "midolman_root_key: /midolman\n" +
            "[bridge]\n" +
            "mac_port_mapping_expire_millis: 40000\n" +
            "[openflow]\n" +
            "flow_expire_millis: 300000\n" +
            "flow_idle_expire_millis: 60000\n" +
            "public_ip_address: 192.168.1.50\n" +
            "use_flow_wildcards: true\n" +
            "[openvswitch]\n" +
            "openvswitchdb_url: some://ovsdb.url/\n";
        // Populate hierarchicalConfiguration with configString.
        // To do this, we write it to a File, then pass that as the
        // constructor's argument.
        File confFile = File.createTempFile("bridge_test", "conf");
        confFile.deleteOnExit();
        FileOutputStream fos = new FileOutputStream(confFile);
        fos.write(configString.getBytes());
        fos.close();
        HierarchicalConfiguration hierarchicalConfiguration = 
                new HierarchicalINIConfiguration(confFile);
        SubnodeConfiguration midoConfig = 
            hierarchicalConfiguration.configurationAt("midolman");

        // Set up the (mock) ZooKeeper directories.
        MockDirectory zkDir = new MockDirectory();
        String midoDirName = zkDir.add(
            midoConfig.getString("midolman_root_key", "/zk_root"), null,
            CreateMode.PERSISTENT);
        Directory midoDir = zkDir.getSubDirectory(midoDirName);
        midoDir.add(midoConfig.getString("bridges_subdirectory", "/bridges"),
                    null, CreateMode.PERSISTENT);
        midoDir.add(midoConfig.getString("mac_port_subdirectory", "/mac_port"),
                    null, CreateMode.PERSISTENT);
        midoDir.add(midoConfig.getString("port_locations_subdirectory",
                                         "/port_locs"),
                    null, CreateMode.PERSISTENT);
        midoDir.add(midoConfig.getString("ports_subdirectory", "/ports"), null,
                    CreateMode.PERSISTENT);

        portLocDir = midoDir.getSubDirectory(
                midoConfig.getString("port_locations_subdirectory",
                                     "/port_locs"));
        portLocMap = new PortToIntNwAddrMap(portLocDir);
        macPortDir = midoDir.getSubDirectory(
                midoConfig.getString("mac_port_subdirectory", "/mac_port"));
        macPortMap = new MacPortMap(macPortDir);
        
        Reactor reactor = new SelectLoop(Executors.newScheduledThreadPool(1));
        
        // At this point in the python, we would create the controllerManager.
        // But we're not using a controllerManager in the Java tests.
        // ControllerTrampoline controllerManager = new ControllerTrampoline(
        //         hierarchicalConfiguration, ovsdb, zkDir, reactor);

        controllerStub = new MockControllerStub();

        UUID bridgeUUID = UUID.randomUUID();

        // The Python had at this point Manager.add_bridge() and
        // Manager.add_bridge_port() for each port, but we're not using
        // the controllerManager.

        controller = new BridgeController(
                /* datapathId */                dp_id, 
                /* switchUuid */                bridgeUUID,
                /* greKey */                    0xe1234,
                /* port_loc_map */              portLocMap,
                /* mac_port_map */              macPortMap,
                /* flowExpireMillis */          300*1000,
                /* idleFlowExpireMillis */      60*1000,
                /* publicIp */                  publicIp,
                /* macPortTimeoutMillis */      40*1000,
                /* ovsdb */                     ovsdb,
                /* reactor */                   reactor,
                /* externalIdKey */             "midolman-vnet");
        controller.setControllerStub(controllerStub);

        portLocMap.start();
        macPortMap.start();

        // Insert ports 3..8 into portLocMap and macPortMap.
        for (int i = 3; i < 8; i++) {
            portLocMap.put(portUuids[i], 
                           Net.convertStringAddressToInt(peerStrList[i]));
            macPortMap.put(macList[i], portUuids[i]);
        }

        // Populate phyPorts and add to controller.
        for (int i = 0; i < 8; i++) {
            phyPorts[i].setPortNumber((short)i);
            phyPorts[i].setHardwareAddress(macList[i]);
            // First three ports are local.  The rest are tunneled.
            phyPorts[i].setName(i < 3 ? "port" + Integer.toString(i)
                                      : controller.makeGREPortName(
                                            Net.convertStringAddressToInt(
                                                    peerStrList[i])));
            controller.addPort(phyPorts[i], (short)i);
        }
    }

    void checkInstalledFlow(OFMatch expectedMatch, int idleTimeout,
                            int hardTimeoutMin, int hardTimeoutMax,
                            int priority, OFAction[] actions) {
        assertEquals(1, controllerStub.addedFlows.size());
        MockControllerStub.Flow flow = controllerStub.addedFlows.get(0);
        assertEquals(expectedMatch, flow.match);
        assertEquals(idleTimeout, flow.idleTimeoutSecs);
        //assertTrue(hardTimeoutMin <= flow.hardTimeoutSecs);  
        //assertTrue(hardTimeoutMax >= flow.hardTimeoutSecs);  
        // No member of Flow for hardTimeout.
        assertEquals(priority, flow.priority);
        assertArrayEquals(actions, flow.actions.toArray());
    }

    void checkSentPacket(int bufferId, short inPort, OFAction[] actions,
                         byte[] data) {
        assertEquals(1, controllerStub.sentPackets.size());
        MockControllerStub.Packet sentPacket = controllerStub.sentPackets.get(0);
        assertEquals(bufferId, sentPacket.bufferId);
        assertEquals(inPort, sentPacket.inPort);
        assertArrayEquals(actions, sentPacket.actions.toArray());
        assertArrayEquals(data, sentPacket.data);
    }

    @Test
    public void testPacketInWithNovelMac() {
        MidoMatch expectedMatch = flowmatch01.clone();
        short inputPort = 0;
        expectedMatch.setInputPort(inputPort);
        OFAction expectedActions[] = { 
                new OFActionOutput(OFPort.OFPP_ALL.getValue(), (short)0) };
        controller.onPacketIn(14, 13, inputPort, packet01.serialize());
        checkInstalledFlow(expectedMatch, 60, 300, 300, 1000, expectedActions);
        checkSentPacket(14, inputPort, expectedActions, new byte[] {});
    }
}
