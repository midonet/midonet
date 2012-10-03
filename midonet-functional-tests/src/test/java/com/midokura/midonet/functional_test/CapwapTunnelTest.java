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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midonet.client.MidonetMgmt;
import com.midokura.midonet.functional_test.mocks.MockMgmtStarter;
import com.midokura.midonet.functional_test.utils.TapWrapper;
import com.midokura.packets.IntIPv4;
import com.midokura.packets.MAC;
import com.midokura.util.lock.LockHelper;

import static com.midokura.midonet.functional_test.FunctionalTestsHelper.*;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.assertPacketWasSentOnTap;
import static com.midokura.util.Waiters.sleepBecause;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;


public class CapwapTunnelTest {

    private final static Logger log = LoggerFactory.getLogger(CapwapTunnelTest.class);

    final String TENANT_NAME = "tenant-capwap";
    final String testConfigurationPath =
            "midolmanj_runtime_configurations/midolman-default.conf";

    // The two VMs that will send traffic across the bridge
    final IntIPv4 localVmIp = IntIPv4.fromString("192.168.231.1", 24);
    final IntIPv4 remoteVmIp = IntIPv4.fromString("192.168.231.2", 24);
    final MAC localVmMac = MAC.fromString("22:22:22:11:11:11");
    final MAC remoteVmMac = MAC.fromString("33:33:33:44:44:44");
    // The physical network
    final IntIPv4 physTapLocalIp = IntIPv4.fromString("10.245.245.1", 24);
    final IntIPv4 physTapRemoteIp = IntIPv4.fromString("10.245.245.2");
    final MAC physTapRemoteMac = MAC.fromString("aa:aa:aa:cc:cc:cc");

    TapWrapper vmTap, physTap;
    BridgePort localPort, remotePort;
    Bridge bridge;
    Host thisHost, remoteHost;

    MockMgmtStarter apiStarter;
    MidonetMgmt apiClient;
    EmbeddedMidolman midolman;

    PacketHelper helper1_2;
    PacketHelper helper2_1;
    Map<UUID, Boolean> portStatus = new HashMap<UUID, Boolean>();

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
        DataClient dataClient = midolman.getDataClient();

        TestProbe probe = new TestProbe(midolman.getActorSystem());
        midolman.getActorSystem().eventStream().subscribe(
                probe.ref(), LocalPortActive.class);
        // FIXME
        Thread.sleep(10000);

        // Create a bridge with two ports
        bridge = apiClient.addBridge().tenantId(TENANT_NAME).name("br1").create();
        localPort = bridge.addMaterializedPort().create();
        remotePort = bridge.addMaterializedPort().create();

        ResourceCollection<Host> hosts = apiClient.getHosts();
        thisHost = null;
        for (Host h : hosts) {
            if (h.getId().toString().matches(TEST_HOST_ID)) {
                thisHost = h;
            }
        }
        // check that we've actually found the test host.
        assertNotNull(thisHost);

        // create the tap for the vm and bind it to this host
        vmTap = new TapWrapper("vmTap");
        thisHost.addHostInterfacePort()
                .interfaceName(vmTap.getName())
                .portId(localPort.getId()).create();

        // Create a capwap tunnel zone
        TunnelZone tunnelZone =
                apiClient.addCapwapTunnelZone().name("CapwapZone").create();
        // add this host to the capwap zone
        tunnelZone.addTunnelZoneHost().
                hostId(thisHost.getId()).
                ipAddress(physTapLocalIp.toUnicastString()).
                create();
        // bind the virtual localPort to the local vmTap
        thisHost.addHostInterfacePort()
                .interfaceName(vmTap.getName())
                .portId(localPort.getId()).create();

        // create a tap for the physical network and populate the neighbour
        // table with the remote host's ip address, to avoid ARPing
        physTap = new TapWrapper("physTap");
        physTap.setIpAddress(physTapLocalIp);
        physTap.addNeighbour(physTapRemoteIp, physTapRemoteMac);

        // through the data client:
        // create host.
        // add host to capwap zone (may be done through api)
        // hostsAddVrnPortMapping()
        UUID remoteHostId = UUID.randomUUID();
        com.midokura.midonet.cluster.data.host.Host remoteHost;
        remoteHost = new com.midokura.midonet.cluster.data.host.Host();
        InetAddress[] tmp = {InetAddress.getByName(
                physTapRemoteIp.toUnicastString())};
        remoteHost.setName("remoteHost").
                setId(remoteHostId).
                setIsAlive(true).
                setAddresses(tmp);

        dataClient.hostsCreate(remoteHostId, remoteHost);
        tunnelZone.addTunnelZoneHost().
                hostId(remoteHostId).
                ipAddress(physTapRemoteIp.toUnicastString()).
                create();
        dataClient.hostsAddVrnPortMapping(
                remoteHostId, remotePort.getId(), "nonExistent");

        // Now associate the virtual bridge ports with local interfaces.
        /*
        helper1_2 = new PacketHelper(mac1, ip1, mac2, ip2);
        helper2_1 = new PacketHelper(mac2, ip2, mac1, ip1);
        */

        /*MidolmanEvents.startObserver();
        MidolmanEvents.setObserverCallback(new MidolmanEvents.EventCallback() {
            @Override
            public void portStatus(UUID portID, boolean up) {
                log.info("Observer callback: {} {}", portID, up);
                if (!up || !portStatus.containsKey(portID)) return;
                portStatus.put(portID, true);
            }
        });
        waitFor("The Midolman daemon should bring up the ports.",
                new Timed.Execution<DtoInterface>() {
                    @Override
                    protected void _runOnce() throws Exception {
                        for (Map.Entry<UUID, Boolean> portStat :
                                portStatus.entrySet()) {
                            if (!portStat.getValue()) {
                                setCompleted(false);
                                return;
                            }
                        }
                        setCompleted(true);
                    }
                }
        );*/
        sleepBecause("we need the network to boot up", 20);
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

    public void sendAndExpectPacket(byte[] pkt, int iterations,
            TapWrapper fromTap, TapWrapper[] expectArrives,
            TapWrapper[] expectDoesNotArrive) {
        for (int i=0; i<iterations; i++) {
            // Send the packet
            assertPacketWasSentOnTap(fromTap, pkt);
            // Read it on each of the taps where it's expected to arrive.
            for (TapWrapper t : expectArrives) {
                byte[] received = t.recv();
                assertNotNull(
                    String.format("Needed bytes from %s on iteration %d",
                        t.getName(),
                    received));
                assertArrayEquals(
                    String.format("Bytes from %s differ on iteration %d",
                        t.getName(), i),
                    pkt, received);
            }
            // Confirm it does not arrive on any tap where it's not expected.
            for (TapWrapper t : expectDoesNotArrive)
                assertNull(
                    String.format("Should not get byts from %s on iteration %d",
                        t.getName(), i),
                    t.recv());
        }
    }


}

