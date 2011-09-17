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
    // TODO: Make this less ugly.
    byte macList[][] = 
      {{(byte)0x00, (byte)0x22, (byte)0x33, (byte)0xEE, (byte)0xEE, (byte)0x00},
       {(byte)0x00, (byte)0x22, (byte)0x33, (byte)0xEE, (byte)0xEE, (byte)0x01},
       {(byte)0x00, (byte)0x22, (byte)0x33, (byte)0xEE, (byte)0xEE, (byte)0x02},
       {(byte)0x00, (byte)0x22, (byte)0x33, (byte)0xEE, (byte)0xEE, (byte)0x03},
       {(byte)0x00, (byte)0x22, (byte)0x33, (byte)0xEE, (byte)0xEE, (byte)0x04},
       {(byte)0x00, (byte)0x22, (byte)0x33, (byte)0xEE, (byte)0xEE, (byte)0x05},
       {(byte)0x00, (byte)0x22, (byte)0x33, (byte)0xEE, (byte)0xEE, (byte)0x06},
       {(byte)0x00, (byte)0x22, (byte)0x33, (byte)0xEE, (byte)0xEE, (byte)0x07}
      };

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
            "mac_port_mapping_expire: 40\n" +
            "[openflow]\n" +
            "flow_expire: 300\n" +
            "flow_idle_expire: 60\n" +
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

        //      TODO: Create ports.
        //      TODO: Create mockProtocol / mockControllerStub.
        //      TODO: Create packets, flows.

        // TODO: Manager.add_bridge()
        // TODO: Manager.add_bridge_port() for each port.

        controller = new BridgeController(
                /* datapathId */                dp_id, 
                /* switchUuid */                UUID.randomUUID(),
                /* greKey */                    0xe1234,
                /* port_loc_map */              portLocMap,
                /* mac_port_map */              macPortMap,
                /* flowExpireMinMillis */       260*1000,
                /* flowExpireMaxMillis */       320*1000,
                /* idleFlowExpireMillis */      60*1000,
                /* publicIp */                  publicIp,
                /* macPortTimeoutMillis */      40*1000,
                /* ovsdb */                     ovsdb);
        controller.setControllerStub(new MockControllerStub());

        portLocMap.start();

        // TODO: Insert ports 3..8 into portLocMap, macPortMap.
        // TODO: Call controller.addPort on all ports.
    }
}
