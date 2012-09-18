/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midonet.client.MidonetMgmt;
import com.midokura.midonet.client.dto.DtoInterface;
import com.midokura.midonet.client.resource.Bridge;
import com.midokura.midonet.client.resource.BridgePort;
import com.midokura.midonet.client.resource.Host;
import com.midokura.midonet.client.resource.ResourceCollection;
import com.midokura.midonet.functional_test.mocks.MockMgmtStarter;
import com.midokura.midonet.functional_test.utils.MidolmanLauncher;
import com.midokura.midonet.functional_test.utils.TapWrapper;
import com.midokura.packets.IntIPv4;
import com.midokura.packets.MAC;
import com.midokura.tools.timed.Timed;
import com.midokura.util.lock.LockHelper;


import static com.midokura.midonet.functional_test.FunctionalTestsHelper.*;
import static com.midokura.util.Waiters.sleepBecause;
import static com.midokura.util.Waiters.waitFor;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;


public class BridgeTestOneDatapath {

    private final static Logger log = LoggerFactory.getLogger(BridgeTestOneDatapath.class);

    final String TENANT_NAME = "tenant-br-one-dp";
    IntIPv4 ip1, ip2, ip3;
    MAC mac1, mac2, mac3;
    PacketHelper helper1_2;
    PacketHelper helper2_1;
    PacketHelper helper1_3;
    PacketHelper helper3_1;

    MockMgmtStarter apiStarter;
    MidonetMgmt apiClient;
    MidolmanLauncher midolman;

    BridgePort port1, port2, port3;
    Bridge bridge1;
    TapWrapper tap1, tap2, tap3;
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

        startCassandra();
        midolman = MidolmanLauncher.start("BridgeTestOneDatapath");
        apiStarter = new MockMgmtStarter(false);
        apiClient = new MidonetMgmt(apiStarter.getURI());

        Bridge bridge = apiClient.addBridge().tenantId(TENANT_NAME)
            .name("br1").create();

        // Create 3 virtual bridge ports. Internally, keep track of the
        // IP/MAC we want to use behind each port. Use IP addresses from the
        // testing range 198.18.0.0/15.
        ip1 = IntIPv4.fromString("198.18.231.2");
        mac1 = MAC.fromString("02:aa:bb:cc:dd:d1");
        port1 = bridge.addMaterializedPort().create();
        portStatus.put(port1.getId(), false);

        ip2 = IntIPv4.fromString("198.18.231.3");
        mac2 = MAC.fromString("02:aa:bb:cc:dd:d2");
        port2 = bridge.addMaterializedPort().create();
        portStatus.put(port2.getId(), false);

        ip3 = IntIPv4.fromString("198.18.231.4");
        mac3 = MAC.fromString("02:aa:bb:cc:dd:d3");
        port3 = bridge.addMaterializedPort().create();
        portStatus.put(port3.getId(), false);

        ResourceCollection<Host> hosts = apiClient.getHosts();
        Host host = null;
        for (Host h : hosts) {
            if (h.getId().toString().matches(TEST_HOST_ID)) {
                host = h;
            }
        }
        // check that we've actually found the test host.
        assertNotNull(host);

        // Now associate the virtual bridge ports with local interfaces.
        tap1 = new TapWrapper("tapBridge1");
        host.addHostInterfacePort()
            .interfaceName(tap1.getName())
            .portId(port1.getId());

        tap2 = new TapWrapper("tapBridge2");
        host.addHostInterfacePort()
            .interfaceName(tap2.getName())
            .portId(port2.getId());

        tap3 = new TapWrapper("tapBridge3");
        host.addHostInterfacePort()
            .interfaceName(tap3.getName())
            .portId(port3.getId());

        helper1_2 = new PacketHelper(mac1, ip1, mac2, ip2);
        helper2_1 = new PacketHelper(mac2, ip2, mac1, ip1);
        helper1_3 = new PacketHelper(mac1, ip1, mac3, ip3);
        helper3_1 = new PacketHelper(mac3, ip3, mac1, ip1);

        MidolmanEvents.startObserver();
        MidolmanEvents.setObserverCallback(new MidolmanEvents.EventCallback() {
            @Override
            public void portStatus(UUID portID, boolean up) {
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
        );
        sleepBecause("we need the network to boot up", 10);
    }

    @After
    public void tearDown() throws Exception {
        removeTapWrapper(tap1);
        removeTapWrapper(tap2);
        removeTapWrapper(tap3);
        stopMidolman(midolman);
        stopMidolmanMgmt(apiStarter);
        stopCassandra();
        cleanupZooKeeperServiceData();
    }

    @Test
    public void testPing() {
        byte[] pkt1to2 = helper1_2.makeIcmpEchoRequest(ip2);
        byte[] pkt2to1 = helper2_1.makeIcmpEchoRequest(ip1);

        // First send pkt1to2 from tap1 so mac1 is learned.
        assertPacketWasSentOnTap(tap1, pkt1to2);

        // Since mac2 hasn't been learnt, the packet will be
        // delivered to all the ports except the one that sent it.
        assertArrayEquals(pkt1to2, tap3.recv());
        assertArrayEquals(pkt1to2, tap2.recv());
        assertNull("The packet should not be flooded to the inPort.",
                tap1.recv());

        // Now send pkt2to1 from tap2.
        assertPacketWasSentOnTap(tap2, pkt2to1);
        // Mac1 was learnt, so the message will be sent only to tap1.
        assertArrayEquals(pkt2to1, tap1.recv());
        assertNull(tap2.recv());
        assertNull(tap3.recv());
        // Now re-send pkt1to2 from tap1 - it should arrive only at tap2
        assertPacketWasSentOnTap(tap1, pkt1to2);
        assertArrayEquals(pkt1to2, tap2.recv());
        assertNull(tap3.recv());
        assertNull(tap1.recv());

        // Simulate mac2 moving to tap3 by sending pkt2to1 from there.
        assertPacketWasSentOnTap(tap3, pkt2to1);
        assertArrayEquals("The packet should still arrive only at tap1.",
                pkt2to1, tap1.recv());
        assertNull(tap2.recv());
        assertNull(tap3.recv());

        // Now if we send pkt1to2 from tap1, it's forwarded only to tap3.
        assertPacketWasSentOnTap(tap1, pkt1to2);
        assertArrayEquals("The packet should arrive only at tap3.",
                pkt1to2, tap3.recv());
        assertNull(tap2.recv());
        assertNull(tap1.recv());
    }
}

