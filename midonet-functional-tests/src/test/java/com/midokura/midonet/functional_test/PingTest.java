/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test;

import static com.midokura.midonet.functional_test.FunctionalTestsHelper.*;

import com.midokura.midonet.client.resource.Host;
import com.midokura.midonet.client.resource.ResourceCollection;
import com.midokura.midonet.client.resource.Router;
import com.midokura.midolman.mgmt.host.HostInterfacePortMap;
import com.midokura.midolman.state.ZkPathManager;
import com.midokura.midonet.client.MidonetMgmt;
import com.midokura.midonet.client.resource.RouterPort;
import com.midokura.midonet.functional_test.mocks.MockMgmtStarter;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import com.midokura.packets.IntIPv4;
import com.midokura.packets.MAC;
import com.midokura.packets.MalformedPacketException;
import com.midokura.midonet.functional_test.openflow.ServiceController;
import com.midokura.midonet.functional_test.topology.TapWrapper;
import com.midokura.midonet.functional_test.topology.Tenant;
import com.midokura.midonet.functional_test.utils.MidolmanLauncher;
import com.midokura.util.lock.LockHelper;

import java.net.URI;
import java.util.concurrent.TimeUnit;

public class PingTest {

    private final static Logger log = LoggerFactory.getLogger(PingTest.class);

    IntIPv4 rtrIp = IntIPv4.fromString("192.168.231.1");
    IntIPv4 ip1 = IntIPv4.fromString("192.168.231.2");
    IntIPv4 ip3 = IntIPv4.fromString("192.168.231.4");
    String internalPortName = "pingTestInt";
    final String TENANT_NAME = "tenant-ping";

    Tenant tenant1;
    RouterPort p1;
    RouterPort p3;
    TapWrapper tap1;
    PacketHelper helper1;
    MidolmanLauncher midolman;
    MockMgmtStarter apiStarter;
    //MidolmanMgmt api;
    MidonetMgmt apiClient;
    ServiceController svcController;
    ZkPathManager pathManager;


    static LockHelper.Lock lock;
    private static final String TEST_HOST_ID = "910de343-c39b-4933-86c7-540225fb02f9" ;

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

        final String TAPNAME = "pingTestTap1";

        //fixQuaggaFolderPermissions();

        startCassandra();

        midolman = MidolmanLauncher.start("PingTest");

        apiStarter = new MockMgmtStarter(false);

        apiClient = new MidonetMgmt(apiStarter.getURI());


        //log.debug("Building tenant");
        //tenant1 = new Tenant.Builder(api).setName("tenant-ping").build();
        //log.debug("Building router");
        //rtr = tenant1.addRouter().setName("rtr1").build();

        log.debug("Building router");
        Router rtr = apiClient.addRouter().tenantId(TENANT_NAME).name("rtr1").create();
        log.debug("Router done!: " + rtr.getName());
        p1 = rtr.addMaterializedRouterPort().portAddress(ip1.toString());

        // TODO hardcore host id somewhere in the midolmanj starter
        ResourceCollection<Host> hosts = apiClient.getHosts();

        Host host = null;

        for (Host h : hosts) {
            if (h.getId().toString().matches(TEST_HOST_ID)) {
                host = h;
            }
        }

        // check that we've actually found the test host.
        assertNotNull(host);

        tap1 = new TapWrapper("pingTestTap1");

       // HostInterfacePortMap hipMap = new HostInterfacePortMap(host.getId(), TAPNAME, p1.getId());

        //log.debug("Adding the interface port map");
      //  api.addHostInterfacePortMap(host, hipMap);

        //p3 = rtr.addVmPort().setVMAddress(ip3).build();
        //p3 = rtr.addMaterializedRouterPort().portAddress(ip3.toString());

        //ovsBridge.addInternalPort(p3.port.getId(), internalPortName, ip3, 24);

       // helper1 = new PacketHelper(MAC.fromString("02:00:00:aa:aa:01"), ip1, rtrIp);

        log.debug("Waiting for the systems to start properly.");
       // TimeUnit.SECONDS.sleep(10);
    }

    @After
    public void tearDown() throws Exception {
        removeTapWrapper(tap1);

        stopMidolman(midolman);
        stopMidolmanMgmt(apiStarter);
        stopCassandra();
        cleanupZooKeeperServiceData();
    }

    @Test
    public void testArpResolutionAndPortPing()
            throws MalformedPacketException, InterruptedException {
        byte[] request;

        // First arp for router's mac.
     /*   assertThat("The ARP request was sent properly",
                tap1.send(helper1.makeArpRequest()));

        MAC rtrMac = helper1.checkArpReply(tap1.recv());
        helper1.setGwMac(rtrMac);
       */
        /*
        // Ping router's port.
        request = helper1.makeIcmpEchoRequest(rtrIp);
        assertThat(
            format("The tap %s should have sent the packet", tap1.getName()),
            tap1.send(request));

/*        // Note: Midolman's virtual router currently does not ARP before
        // responding to ICMP echo requests addressed to its own port.
        PacketHelper.checkIcmpEchoReply(request, tap1.recv());
        */

        /*    // Ping internal port p3.
        request = helper1.makeIcmpEchoRequest(ip3);
        assertThat("The tap should have sent the packet again",
                tap1.send(request));
        // Note: the virtual router ARPs before delivering the reply packet.
        helper1.checkArpRequest(tap1.recv());
        assertThat("The tap should have sent the packet again",
                tap1.send(helper1.makeArpReply()));
        // Finally, the icmp echo reply from the peer.
        PacketHelper.checkIcmpEchoReply(request, tap1.recv());

        assertNoMorePacketsOnTap(tap1);
        */

        log.debug("============== FINISH TEST ======================");
    }
}