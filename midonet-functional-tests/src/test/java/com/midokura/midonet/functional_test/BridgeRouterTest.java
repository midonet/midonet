/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test;

import java.nio.ByteBuffer;

import static com.midokura.util.Waiters.sleepBecause;
import static java.lang.String.format;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.notNullValue;

import com.midokura.packets.Ethernet;
import com.midokura.packets.IntIPv4;
import com.midokura.packets.MAC;
import com.midokura.packets.MalformedPacketException;
import com.midokura.midonet.functional_test.mocks.MidolmanMgmt;
import com.midokura.midonet.functional_test.mocks.MockMidolmanMgmt;
import com.midokura.midonet.functional_test.topology.Bridge;
import com.midokura.midonet.functional_test.topology.BridgePort;
import com.midokura.midonet.functional_test.topology.LogicalBridgePort;
import com.midokura.midonet.functional_test.topology.LogicalRouterPort;
import com.midokura.midonet.functional_test.topology.MaterializedRouterPort;
import com.midokura.midonet.functional_test.topology.Router;
import com.midokura.midonet.functional_test.utils.TapWrapper;
import com.midokura.midonet.functional_test.topology.Tenant;
import com.midokura.midonet.functional_test.utils.MidolmanLauncher;
import com.midokura.util.lock.LockHelper;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.assertNoMorePacketsOnTap;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.fixQuaggaFolderPermissions;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.removeTapWrapper;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.removeTenant;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.stopMidolman;

@Ignore
public class BridgeRouterTest {

    private final static Logger log = LoggerFactory.getLogger(BridgeRouterTest.class);

    IntIPv4 rtrIp1 = IntIPv4.fromString("192.168.231.1");
    IntIPv4 rtrIp2 = IntIPv4.fromString("192.168.240.1");
    IntIPv4 ip1 = IntIPv4.fromString("192.168.231.2");
    IntIPv4 ip2 = IntIPv4.fromString("192.168.240.2");
    IntIPv4 subnetAddr1 = IntIPv4.fromString("192.168.231.0", 24);
    IntIPv4 subnetAddr2 = IntIPv4.fromString("192.168.240.0", 24);

    Router rtr;
    LogicalRouterPort routerPort1;
    LogicalRouterPort routerPort2;
    LogicalBridgePort bridge1Port;
    LogicalBridgePort bridge2Port;
    Tenant tenant1;
    MaterializedRouterPort p1;
    TapWrapper tap1;
    TapWrapper tap2;
    MidolmanMgmt api;
    MidolmanLauncher midolman;

    static LockHelper.Lock lock;

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
        fixQuaggaFolderPermissions();

        api = new MockMidolmanMgmt(false);
        midolman = MidolmanLauncher.start(this.getClass().getSimpleName());

        log.debug("Building tenant");
        tenant1 = new Tenant.Builder(api).setName("tenant-ping").build();
        rtr = tenant1.addRouter().setName("rtr1").build();

        // Create a virtual L2 bridge with one tap port.
        Bridge bridge1 = tenant1.addBridge().setName("br1").build();
        BridgePort bPort1 = bridge1.addPort().build();
        tap1 = new TapWrapper("tapBridge1");
        //ovsBridge.addSystemPort(bPort1.getId(), tap1.getName());

        // Link the Bridge and Router
        routerPort1 = rtr.addLinkPort()
                .setNetworkAddress(subnetAddr1.toUnicastString())
                .setNetworkLength(subnetAddr1.getMaskLength())
                .setPortAddress(
                        new IntIPv4(subnetAddr1.addressAsInt() + 1)
                            .toUnicastString()).build();
        bridge1Port = bridge1.addLinkPort().build();
        routerPort1.link(bridge1Port);

        // Create another virtual L2 bridge with one tap port.
        Bridge bridge2 = tenant1.addBridge().setName("br2").build();
        BridgePort bPort2 = bridge2.addPort().build();
        tap2 = new TapWrapper("tapBridge2");
        //ovsBridge.addSystemPort(bPort2.getId(), tap2.getName());

        // Link the Bridge and Router
        routerPort2 = rtr.addLinkPort()
                .setNetworkAddress(subnetAddr2.toUnicastString())
                .setNetworkLength(subnetAddr2.getMaskLength())
                .setPortAddress(
                        new IntIPv4(subnetAddr2.addressAsInt() + 1)
                            .toUnicastString()).build();
        bridge2Port = bridge2.addLinkPort().build();
        routerPort2.link(bridge2Port);

        sleepBecause("we wait for the network configuration to bootup", 5);
    }

    @After
    public void tearDown() throws Exception {
        removeTapWrapper(tap1);
        removeTapWrapper(tap2);
        stopMidolman(midolman);
        if (null != routerPort1)
            routerPort1.unlink();
        if (null != routerPort2)
            routerPort2.unlink();
        removeTenant(tenant1);
       // stopMidolmanMgmt(api);
    }

    @Test
    public void testArpResolutionAndPortPing()
            throws MalformedPacketException {
        byte[] request;

        // Arp for router's mac on first bridge.
        MAC vmMac1 = MAC.fromString("02:00:00:00:00:c1");
        assertThat("The ARP request was sent properly",
                tap1.send(PacketHelper.makeArpRequest(vmMac1, ip1, rtrIp1)));

        byte[] received = tap1.recv();
        assertThat("We expected a package that we didn't get.",
                received, notNullValue());
        Ethernet eth = new Ethernet();
        eth.deserialize(ByteBuffer.wrap(received, 0, received.length));
        // TODO(pino): verify this is an ARP reply from rtrIp1
        // Now that we know the router's MAC we can create the packet helper.
        PacketHelper helper1 = new PacketHelper(vmMac1, ip1,
                eth.getSourceMACAddress(), rtrIp1);
        helper1.checkArpReply(received);

        // Ping router's port.
        request = helper1.makeIcmpEchoRequest(rtrIp1);
        assertThat(
                format("The tap %s should have sent the packet", tap1.getName()),
                tap1.send(request));

        // Note: Midolman's virtual router currently does not ARP before
        // responding to ICMP echo requests addressed to its own port.
        PacketHelper.checkIcmpEchoReply(request, tap1.recv());

        assertNoMorePacketsOnTap(tap1);

        // Arp for router's mac on second bridge.
        MAC vmMac2 = MAC.fromString("02:00:00:00:00:c2");
        assertThat("The ARP request was sent properly",
                tap2.send(PacketHelper.makeArpRequest(vmMac2, ip2, rtrIp2)));

        received = tap2.recv();
        assertThat("We expected a package that we didn't get.",
                received, notNullValue());
        eth = new Ethernet();
        eth.deserialize(ByteBuffer.wrap(received, 0, received.length));
        // Now that we know the router's MAC we can create the packet helper.
        PacketHelper helper2 = new PacketHelper(vmMac2, ip2,
                eth.getSourceMACAddress(), rtrIp2);
        helper2.checkArpReply(received);

        // Send a ping request from tap1 to tap2.
        request = helper1.makeIcmpEchoRequest(ip2);
        assertThat(
                format("The tap %s should have sent the packet",
                        tap1.getName()), tap1.send(request));
        received = tap2.recv();
        assertThat("We expected a package that we didn't get.",
                received, notNullValue());
        // The router ARP before delivering the packet.
        helper2.checkArpRequest(received);
        // Send the ARP reply.
        assertThat(
                format("The tap %s should have sent the packet",
                        tap2.getName()), tap2.send(helper2.makeArpReply()));
        // Now tap2 should receive the ICMP that was sent from tap1.
        received = tap2.recv();
        assertThat("We expected a package that we didn't get.",
                received, notNullValue());
        helper2.checkIcmpEchoRequest(request, received);
    }
}
