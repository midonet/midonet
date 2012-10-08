/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test;

import java.io.File;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import akka.testkit.TestProbe;
import com.midokura.midolman.topology.LocalPortActive;
import com.midokura.midonet.client.resource.*;
import com.midokura.midonet.cluster.DataClient;
import com.midokura.midonet.functional_test.utils.EmbeddedMidolman;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midonet.client.MidonetMgmt;
import com.midokura.midonet.functional_test.mocks.MockMgmtStarter;
import com.midokura.midonet.functional_test.utils.TapWrapper;
import com.midokura.packets.IntIPv4;
import com.midokura.packets.MAC;
import com.midokura.packets.Ethernet;
import com.midokura.packets.IPv4;
import com.midokura.packets.UDP;
import com.midokura.packets.IPacket;
import com.midokura.packets.MalformedPacketException;
import com.midokura.util.lock.LockHelper;

import static com.midokura.midonet.functional_test.FunctionalTestsHelper.*;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.assertPacketWasSentOnTap;
import static com.midokura.util.Waiters.sleepBecause;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;


public abstract class BaseTunnelTest {

    private final static Logger log = LoggerFactory.getLogger(BaseTunnelTest.class);

    final String TENANT_NAME = "tenant-tunnel-test";
    final String testConfigurationPath =
            "midolmanj_runtime_configurations/midolman-default.conf";

    // The two VMs that will send traffic across the bridge
    final IntIPv4 localVmIp = IntIPv4.fromString("192.168.231.1", 24);
    final IntIPv4 remoteVmIp = IntIPv4.fromString("192.168.231.2", 24);
    final MAC localVmMac = MAC.fromString("22:22:22:11:11:11");
    final MAC remoteVmMac = MAC.fromString("33:33:33:44:44:44");
    // The physical network
    final IntIPv4 physTapLocalIp = IntIPv4.fromString("10.245.215.1", 24);
    final IntIPv4 physTapRemoteIp = IntIPv4.fromString("10.245.215.2");
    final MAC physTapRemoteMac = MAC.fromString("aa:aa:aa:cc:cc:cc");

    TapWrapper vmTap, physTap;
    BridgePort localPort, remotePort;
    Bridge bridge;
    Host thisHost, remoteHost;
    UUID thisHostId, remoteHostId;

    MockMgmtStarter apiStarter;
    MidonetMgmt apiClient;
    EmbeddedMidolman midolman;

    DataClient dataClient;

    static LockHelper.Lock lock;
    private static final String TEST_HOST_ID = "910de343-c39b-4933-86c7-540225fb02f9";

    @BeforeClass
    public static void checkLock() {
        lock = LockHelper.lock(FunctionalTestsHelper.LOCK_NAME);
    }

    @AfterClass
    public static void releaseLock() {
        lock.release();
    }

    protected abstract void setUpTunnelZone() throws Exception;

    @Before
    public void setUp() throws Exception {
        File testConfigFile = new File(testConfigurationPath);
        log.info("Starting embedded zookeper");
        int zkPort = startEmbeddedZookeeper(testConfigurationPath);
        log.info("Starting cassandra");
        startCassandra();
        log.info("Starting REST API");
        apiStarter = new MockMgmtStarter(zkPort);
        apiClient = new MidonetMgmt(apiStarter.getURI());
        log.info("Starting midolman");
        midolman = startEmbeddedMidolman(testConfigFile.getAbsolutePath());
        dataClient = midolman.getDataClient();

        TestProbe probe = new TestProbe(midolman.getActorSystem());
        midolman.getActorSystem().eventStream().subscribe(
                probe.ref(), LocalPortActive.class);
        // FIXME
        Thread.sleep(10000);

        // Create a bridge with two ports
        log.info("Creating bridge and two ports.");
        bridge = apiClient.addBridge().tenantId(TENANT_NAME).name("br1").create();
        localPort = bridge.addMaterializedPort().create();
        remotePort = bridge.addMaterializedPort().create();

        ResourceCollection<Host> hosts = apiClient.getHosts();
        thisHost = null;
        for (Host h : hosts) {
            if (h.getId().toString().matches(TEST_HOST_ID)) {
                thisHost = h;
                thisHostId = h.getId();
            }
        }
        // check that we've actually found the test host.
        assertNotNull(thisHost);

        // create the tap for the vm and bind it to this host
        log.info("Creating tap for local vm and binding port to it");
        vmTap = new TapWrapper("vmTap");
        thisHost.addHostInterfacePort()
                .interfaceName(vmTap.getName())
                .portId(localPort.getId()).create();

        // create a tap for the physical network and populate the neighbour
        // table with the remote host's ip address, to avoid ARPing
        log.info("Creating tap for the physical network");
        physTap = new TapWrapper("physTap");
        physTap.setIpAddress(physTapLocalIp);
        physTap.addNeighbour(physTapRemoteIp, physTapRemoteMac);

        // through the data client:
        log.info("Creating remote host");
        remoteHostId = UUID.randomUUID();
        com.midokura.midonet.cluster.data.host.Host remoteHost;
        remoteHost = new com.midokura.midonet.cluster.data.host.Host();
        InetAddress[] tmp =
            { InetAddress.getByName(physTapRemoteIp.toUnicastString()) };
        remoteHost.setName("remoteHost").
                setId(remoteHostId).
                setIsAlive(true).
                setAddresses(tmp);
        dataClient.hostsCreate(remoteHostId, remoteHost);

        log.info("binding remote port to remote host");
        dataClient.hostsAddVrnPortMapping(
                remoteHostId, remotePort.getId(), "nonExistent");

        log.info("adding remote host to portSet");
        dataClient.portSetsAddHost(bridge.getId(), remoteHostId);

        setUpTunnelZone();

        // TODO
        sleepBecause("we need the network to boot up", 10);
    }

    @After
    public void tearDown() throws Exception {
        removeTapWrapper(vmTap);
        removeTapWrapper(physTap);
        stopEmbeddedMidolman();
        stopMidolmanMgmt(apiStarter);
        stopCassandra();
        stopEmbeddedZookeeper();
    }

    @Test
    public void testEncapsulation() throws MalformedPacketException {
        // inject a packet sent from the local vm, expect it encapsulated on the
        // physicalTap.

        /* XXX(guillermo) it seems that ovswitch decapsulates the packet before
         * it shows up on physTap, however tcpdump does see the encapsulated
         * packet on the 'wire'. We need  a way to prevent ovswitch from
         * decapsulating the packet or to get a hold of it before it does.
         */
        byte[] pkt = PacketHelper.makeUDPPacket(localVmMac, localVmIp,
                                                remoteVmMac, remoteVmIp,
                                                (short) 2345, (short) 9876,
                                                "The Payload".getBytes());
        assertPacketWasSentOnTap(vmTap, pkt);
        byte[] received = physTap.recv();
        assertNotNull(String.format("Expected packet on %s", physTap.getName()),
                      received);

        Ethernet eth = Ethernet.deserialize(pkt);
        log.info("got packet on physical network: " + eth.toString());

        assertEquals("source ethernet address",
            localVmMac, eth.getSourceMACAddress());
        assertEquals("destination ethernet address",
            remoteVmMac, eth.getDestinationMACAddress());
        assertEquals("ethertype", IPv4.ETHERTYPE, eth.getEtherType());

        assertTrue("payload is IPv4", eth.getPayload() instanceof IPv4);
        IPv4 ipPkt = (IPv4) eth.getPayload();
        assertEquals("source ipv4 address",
            localVmIp.addressAsInt(), ipPkt.getSourceAddress());
        assertEquals("destination ipv4 address",
            remoteVmIp.addressAsInt(), ipPkt.getDestinationAddress());

        assertTrue("payload is UDP", ipPkt.getPayload() instanceof UDP);
        UDP udpPkt = (UDP) ipPkt.getPayload();
        assertEquals("udp source port",
            (short) 2345, udpPkt.getSourcePort());
        assertEquals("udp destination port",
            (short) 9876, udpPkt.getDestinationPort());
    }

    protected abstract byte[] buildEncapsulatedPacket();

    @Test
    public void testDecapsulation() throws MalformedPacketException {
        assertPacketWasSentOnTap(physTap, buildEncapsulatedPacket());

        byte[] received = vmTap.recv();
        assertNotNull(String.format("Expected packet on %s", vmTap.getName()),
                      received);

        Ethernet eth = Ethernet.deserialize(received);
        log.info("got packet on physical network: " + eth.toString());
    }
}
