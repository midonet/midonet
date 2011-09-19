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
import org.openflow.protocol.OFPhysicalPort;

import com.midokura.midolman.eventloop.Reactor;
import com.midokura.midolman.eventloop.SelectLoop;
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

    // Flowmods
    //XXX


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
	icmpPacket.setEchoReply((short)0, (short)0, "echoechoecho".getBytes());
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

        // Populate ports
        for (int i = 0; i < 8; i++) {
            phyPorts[i].setPortNumber((short)i);
            phyPorts[i].setHardwareAddress(macList[i]);
            // First three ports are local.  The rest are tunneled.
            phyPorts[i].setName(i < 3 ? "port" + Integer.toString(i)
                                      : controller.makeGREPortName(
                                            Net.convertStringAddressToInt(
                                                    peerStrList[i])));
        }

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
            CreateMode.PERSISTENT_SEQUENTIAL);
        Directory midoDir = zkDir.getSubDirectory(midoDirName);
        midoDir.add(midoConfig.getString("bridges_subdirectory", "/bridges"),
                    null, CreateMode.PERSISTENT_SEQUENTIAL);
        midoDir.add(midoConfig.getString("mac_port_subdirectory", "/mac_port"),
                    null, CreateMode.PERSISTENT_SEQUENTIAL);
        midoDir.add(midoConfig.getString("port_locations_subdirectory",
                                         "/port_locs"),
                    null, CreateMode.PERSISTENT_SEQUENTIAL);
        midoDir.add(midoConfig.getString("ports_subdirectory", "/ports"), null,
                    CreateMode.PERSISTENT_SEQUENTIAL);

        portLocDir = zkDir.getSubDirectory(
                midoConfig.getString("port_locations_subdirectory",
                                     "/port_locs"));
        portLocMap = new PortToIntNwAddrMap(portLocDir);
        macPortDir = zkDir.getSubDirectory(
                midoConfig.getString("mac_port_subdirectory", "/mac_port"));
        macPortMap = new MacPortMap(macPortDir);
        
        Reactor reactor = new SelectLoop(Executors.newScheduledThreadPool(1));
        
        // Create controllerManager.
        ControllerTrampoline controllerManager = new ControllerTrampoline(
                hierarchicalConfiguration, ovsdb, zkDir, reactor);

        MockControllerStub controllerStub = new MockControllerStub();
        //      TODO: Create packets, flows.

        UUID bridgeUUID = UUID.randomUUID();

        // TODO: Manager.add_bridge()
        // TODO: Manager.add_bridge_port() for each port.

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

        // TODO: Insert ports 3..8 into portLocMap, macPortMap.
        // TODO: Call controller.addPort on all ports.
    }
}
