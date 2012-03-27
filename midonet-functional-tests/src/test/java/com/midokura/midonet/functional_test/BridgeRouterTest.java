/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnectionImpl;
import com.midokura.midolman.packets.IntIPv4;
import com.midokura.midolman.packets.MAC;
import com.midokura.midolman.packets.MalformedPacketException;
import com.midokura.midonet.functional_test.mocks.MidolmanMgmt;
import com.midokura.midonet.functional_test.mocks.MockMidolmanMgmt;
import com.midokura.midonet.functional_test.openflow.ServiceController;
import com.midokura.midonet.functional_test.topology.Bridge;
import com.midokura.midonet.functional_test.topology.BridgePort;
import com.midokura.midonet.functional_test.topology.BridgeRouterLink;
import com.midokura.midonet.functional_test.topology.RouterPort;
import com.midokura.midonet.functional_test.topology.OvsBridge;
import com.midokura.midonet.functional_test.topology.Router;
import com.midokura.midonet.functional_test.topology.TapWrapper;
import com.midokura.midonet.functional_test.topology.Tenant;
import com.midokura.midonet.functional_test.utils.MidolmanLauncher;


import static com.midokura.midonet.functional_test.FunctionalTestsHelper.*;
import static java.lang.String.format;
import static org.hamcrest.MatcherAssert.assertThat;

public class BridgeRouterTest {

    private final static Logger log = LoggerFactory.getLogger(BridgeRouterTest.class);

    IntIPv4 rtrIp = IntIPv4.fromString("192.168.231.1");
    IntIPv4 ip1 = IntIPv4.fromString("192.168.231.2");
    IntIPv4 ip2 = IntIPv4.fromString("192.168.231.3");
    IntIPv4 ip3 = IntIPv4.fromString("192.168.231.4");
    String internalPortName = "pingTestInt";

    Router rtr;
    Tenant tenant1;
    RouterPort p1;
    RouterPort p2;
    RouterPort p3;
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
        fixQuaggaFolderPermissions();

        ovsdb = new OpenvSwitchDatabaseConnectionImpl(
            "Open_vSwitch", "127.0.0.1", 12344);

        api = new MockMidolmanMgmt(false);
        midolman = MidolmanLauncher.start("PingTest");

        if (ovsdb.hasBridge("smoke-br"))
            ovsdb.delBridge("smoke-br");

        ovsBridge = new OvsBridge(ovsdb, "smoke-br");
        // Add a service controller to OVS bridge 1.
        ovsBridge.addServiceController(6640);
        svcController = new ServiceController(6640);
        waitForBridgeToConnect(svcController);

        log.debug("Building tenant");
        tenant1 = new Tenant.Builder(api).setName("tenant-ping").build();
        rtr = tenant1.addRouter().setName("rtr1").build();

        Bridge bridge = tenant1.addBridge().setName("br1").build();
        ip1 = IntIPv4.fromString("192.168.14.2");
        BridgePort bPort1 = bridge.addPort();
        tap1 = new TapWrapper("tapBridge1");
        ovsBridge.addSystemPort(bPort1.getId(), tap1.getName());

        // Link the Bridge and Router
        BridgeRouterLink link = rtr.addBridgeRouterLink(
                bridge, IntIPv4.fromString("192.168.14.0"));

        sleepBecause("we wait for the network configuration to bootup", 5);
    }

    @After
    public void tearDown() throws Exception {
        removeTapWrapper(tap2);
        removeTapWrapper(tap1);
        if (ovsBridge != null) {
            ovsBridge.deletePort(internalPortName);
        }
        removeBridge(ovsBridge);
        stopMidolman(midolman);
        removeTenant(tenant1);
        stopMidolmanMgmt(api);
    }

    @Test
    public void testArpResolutionAndPortPing()
            throws MalformedPacketException {
        byte[] request;

        // First arp for router's mac.
        assertThat("The ARP request was sent properly",
                   tap1.send(helper1.makeArpRequest()));

        helper1.checkArpReply(tap1.recv());

        // Ping router's port.
        request = helper1.makeIcmpEchoRequest(rtrIp);
        assertThat(
            format("The tap %s should have sent the packet", tap1.getName()),
            tap1.send(request));

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

        assertNoMorePacketsOnTap(tap1);
    }
}
