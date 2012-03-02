/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.http.impl.conn.tsccm.RouteSpecificPool;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.openflow.MidoMatch;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnectionImpl;
import com.midokura.midolman.packets.ICMP;
import com.midokura.midolman.packets.IntIPv4;
import com.midokura.midolman.packets.MAC;
import com.midokura.midonet.functional_test.mocks.MidolmanMgmt;
import com.midokura.midonet.functional_test.mocks.MockMidolmanMgmt;
import com.midokura.midonet.functional_test.openflow.FlowStats;
import com.midokura.midonet.functional_test.openflow.ServiceController;
import com.midokura.midonet.functional_test.topology.MidoPort;
import com.midokura.midonet.functional_test.topology.OvsBridge;
import com.midokura.midonet.functional_test.topology.Route;
import com.midokura.midonet.functional_test.topology.Router;
import com.midokura.midonet.functional_test.topology.TapWrapper;
import com.midokura.midonet.functional_test.topology.Tenant;
import com.midokura.midonet.functional_test.utils.MidolmanLauncher;
import com.midokura.tools.timed.Timed;

public class PingTest extends AbstractSmokeTest {

    private final static Logger log = LoggerFactory.getLogger(PingTest.class);

    IntIPv4 rtrIp = IntIPv4.fromString("192.168.231.1");
    IntIPv4 ip1 = IntIPv4.fromString("192.168.231.2");
    IntIPv4 ip2 = IntIPv4.fromString("192.168.231.3");
    IntIPv4 ip3 = IntIPv4.fromString("192.168.231.4");

    Router rtr;
    Tenant tenant1;
    MidoPort p1;
    MidoPort p2;
    MidoPort p3;
    TapWrapper tap1;
    TapWrapper tap2;
    OpenvSwitchDatabaseConnectionImpl ovsdb;
    PacketHelper helper1;
    PacketHelper helper2;
    MidolmanMgmt api;
    MidolmanLauncher midolman;
    OvsBridge ovsBridge;
    ServiceController svcController;

    @Before
    public void setUp() throws Exception {
        ovsdb = new OpenvSwitchDatabaseConnectionImpl(
            "Open_vSwitch", "127.0.0.1", 12344);

        api = new MockMidolmanMgmt(false);
        midolman = MidolmanLauncher.start();

        if (ovsdb.hasBridge("smoke-br"))
            ovsdb.delBridge("smoke-br");

        ovsBridge = new OvsBridge(ovsdb, "smoke-br", OvsBridge.L3UUID);
        // Add a service controller to OVS bridge 1.
        ovsBridge.addServiceController(6640);
        svcController = new ServiceController(6640);
        waitForBridgeToConnect(svcController);

        log.debug("Building tenant");
        tenant1 = new Tenant.Builder(api).setName("tenant-ping").build();
        rtr = tenant1.addRouter().setName("rtr1").build();
        log.debug("Done building tenant");

        p1 = rtr.addVmPort().setVMAddress(ip1).build();
        tap1 = new TapWrapper("pingTestTap1");
        ovsBridge.addSystemPort(p1.port.getId(), tap1.getName());

        p2 = rtr.addVmPort().setVMAddress(ip2).build();
        tap2 = new TapWrapper("pingTestTap2");
        ovsBridge.addSystemPort(p2.port.getId(), tap2.getName());

        p3 = rtr.addVmPort().setVMAddress(ip3).build();
        ovsBridge.addInternalPort(p3.port.getId(), "pingTestInt", ip3, 24);

        helper1 = new PacketHelper(MAC.fromString("02:00:00:aa:aa:01"), ip1,
                tap1.getHwAddr(), rtrIp);
        helper2 = new PacketHelper(MAC.fromString("02:00:00:aa:aa:02"), ip2,
                tap2.getHwAddr(), rtrIp);

        Thread.sleep(TimeUnit.SECONDS.toMillis(2));
    }

    @After
    public void tearDown() throws Exception {
        removeTapWrapper(tap2);
        removeTapWrapper(tap1);
        ovsBridge.deletePort("pingTestInt");
        removeBridge(ovsBridge);
        stopMidolman(midolman);
        removeTenant(tenant1);
        stopMidolmanMgmt(api);
    }

    @Test
    public void testArpResolutionAndPortPing() {
        byte[] request;

        // First arp for router's mac.
        assertThat("The ARP request was sent properly",
                   tap1.send(helper1.makeArpRequest()));

        helper1.checkArpReply(tap1.recv());

        // Ping router's port.
        request = helper1.makeIcmpEchoRequest(rtrIp);
        assertThat("The tap should have sent the packet", tap1.send(request));
        // Note: Midolman's virtual router currently does not ARP before
        // responding to ICMP echo requests addressed to its own port.
        PacketHelper.checkIcmpEchoReply(request, tap1.recv());

        // Ping internal port p3.
        request = helper1.makeIcmpEchoRequest(ip3);
        assertThat("The tap should have sent the packet again",
                tap1.send(request));
        // Note: the virtual router ARPs before delivering the reply packet.
        helper1.checkArpRequest(tap1.recv());
        assertThat("The tap should have sent the packet again",
                tap1.send(helper1.makeArpReply()));
        // Finally, the icmp echo reply from the peer.
        PacketHelper.checkIcmpEchoReply(request, tap1.recv());

        // No other packets arrive at the port.
        assertThat("Not other packet has arrived on the port",
                   tap1.recv(), nullValue());
    }
}
