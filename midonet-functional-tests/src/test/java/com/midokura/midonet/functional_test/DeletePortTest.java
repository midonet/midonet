/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static java.lang.String.format;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.Assert.assertEquals;

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
import com.midokura.midonet.functional_test.topology.Router;
import com.midokura.midonet.functional_test.topology.TapWrapper;
import com.midokura.midonet.functional_test.topology.Tenant;
import com.midokura.midonet.functional_test.utils.MidolmanLauncher;

public class DeletePortTest extends AbstractSmokeTest {

    private final static Logger log = LoggerFactory.getLogger(
        DeletePortTest.class);
    public static final String INT_PORT_NAME = "intDeletePort";

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
        midolman = MidolmanLauncher.start("DeletePortTest");

        if (ovsdb.hasBridge("smoke-br"))
            ovsdb.delBridge("smoke-br");

        ovsBridge = new OvsBridge(ovsdb, "smoke-br", OvsBridge.L3UUID);
        // Add a service controller to OVS bridge 1.
        ovsBridge.addServiceController(6640);
        svcController = new ServiceController(6640);
        waitForBridgeToConnect(svcController);

        log.debug("Building tenant");
        tenant1 = new Tenant.Builder(api).setName("tenant-port-delete").build();
        rtr = tenant1.addRouter().setName("rtr1").build();
        log.debug("Done building tenant");

        p1 = rtr.addVmPort().setVMAddress(ip1).build();
        tap1 = new TapWrapper("tapDelPort1");
        ovsBridge.addSystemPort(p1.port.getId(), tap1.getName());

        p2 = rtr.addVmPort().setVMAddress(ip2).build();
        tap2 = new TapWrapper("tapDelPort2");
        ovsBridge.addSystemPort(p2.port.getId(), tap2.getName());

        p3 = rtr.addVmPort().setVMAddress(ip3).build();
        ovsBridge.addInternalPort(p3.port.getId(), INT_PORT_NAME, ip3, 24);

        helper1 = new PacketHelper(MAC.fromString("02:00:00:aa:aa:01"), ip1,
                                   tap1.getHwAddr(), rtrIp);
        helper2 = new PacketHelper(MAC.fromString("02:00:00:aa:aa:02"), ip2,
                                   tap2.getHwAddr(), rtrIp);

        waitForNetworkConfigurationToEnd();
    }

    private void waitForNetworkConfigurationToEnd() {
        // just wait 2 seconds.
        // normally we would need a way to pool the configuration until we know
        // it's settled
        TimeUnit.SECONDS.toMillis(2);
    }

    @After
    public void tearDown() throws Exception {
        removeTapWrapper(tap1);
        removeTapWrapper(tap2);
        removeBridge(ovsBridge);
        stopMidolman(midolman);
        Thread.sleep(1000);
        removeTenant(tenant1);
        Thread.sleep(1000);
        stopMidolmanMgmt(api);
        Thread.sleep(10 * 1000);
    }

    @Test
    public void testPortDelete() throws InterruptedException {
        short num1 = ovsdb.getPortNumByUUID(ovsdb.getPortUUID(tap1.getName()));
        short num2 = ovsdb.getPortNumByUUID(ovsdb.getPortUUID(tap2.getName()));
        short num3 = ovsdb.getPortNumByUUID(ovsdb.getPortUUID(INT_PORT_NAME));
        log.debug(
            "The OF ports have numbers: {}, {}, and {}",
            new Object[]{num1, num2, num3});

        // Remove/re-add the two tap ports to remove all flows.
        ovsBridge.deletePort(tap1.getName());
        ovsBridge.deletePort(tap2.getName());
        // Sleep to let OVS complete the port requests.
        Thread.sleep(1000);

        ovsBridge.addSystemPort(p1.port.getId(), tap1.getName());
        ovsBridge.addSystemPort(p2.port.getId(), tap2.getName());
        // Sleep to let OVS complete the port requests.
        Thread.sleep(1000);

        num1 = ovsdb.getPortNumByUUID(ovsdb.getPortUUID(tap1.getName()));
        num2 = ovsdb.getPortNumByUUID(ovsdb.getPortUUID(tap2.getName()));
        num3 = ovsdb.getPortNumByUUID(ovsdb.getPortUUID(INT_PORT_NAME));
        log.debug("The OF ports have numbers: {}, {}, and {}",
                  new Object[]{num1, num2, num3});

        // Verify that there are no ICMP flows.
        MidoMatch icmpMatch = new MidoMatch()
            .setNetworkProtocol(ICMP.PROTOCOL_NUMBER);
        List<FlowStats> fstats = svcController.getFlowStats(icmpMatch);
        assertThat("There are no stats for ICMP yet",
                   fstats, hasSize(0));

        // Send ICMPs from p1 to internal port p3.
        byte[] ping1_3 = helper1.makeIcmpEchoRequest(ip3);

        assertThat("The tap should have sent the packet",
                   tap1.send(ping1_3));

        // Note: the virtual router ARPs before delivering the reply packet.
        helper1.checkArpRequest(tap1.recv());
        assertThat("The tap should have sent the ARP reply",
                   tap1.send(helper1.makeArpReply()));

        // Finally, the icmp echo reply from the peer.
        PacketHelper.checkIcmpEchoReply(ping1_3, tap1.recv());

        // Send ICMPs from p2 to internal port p3.
        byte[] ping2_3 = helper2.makeIcmpEchoRequest(ip3);
        assertThat("The tap should have sent the packet", tap2.send(ping2_3));

        // Note: the virtual router ARPs before delivering the reply packet.
        helper2.checkArpRequest(tap2.recv());
        assertThat("The tap should have sent the ARP reply",
                   tap2.send(helper2.makeArpReply()));
        // Finally, the icmp echo reply from the peer.
        PacketHelper.checkIcmpEchoReply(ping2_3, tap2.recv());

        // Sleep to let OVS update its stats.
        Thread.sleep(1000);

        // Now there should be 4 ICMP flows
        fstats = svcController.getFlowStats(icmpMatch);
        assertEquals(4, fstats.size());

        // Verify that there are flows: 1->3, 3->1, 2->3, 3->2.
        fstats =
            svcController.getFlowStats(
                new MidoMatch().setNetworkSource(ip1.address)
                               .setNetworkDestination(ip3.address));

        assertThat("Only one FlowStats object should be visible",
                   fstats, hasSize(1));

        FlowStats flow1_3 = fstats.get(0);
        flow1_3.expectCount(1).expectOutputAction(num3);

        fstats = svcController.getFlowStats(
            new MidoMatch().setNetworkSource(ip3.address)
                           .setNetworkDestination(ip1.address));
        assertThat("Only one FlowStats object should be visible.",
                   fstats, hasSize(1));
        FlowStats flow3_1 = fstats.get(0);
        flow3_1.expectCount(1).expectOutputAction(num1);

        fstats = svcController.getFlowStats(
            new MidoMatch().setNetworkSource(ip2.address)
                           .setNetworkDestination(ip3.address));
        assertThat("One one FlowStats object should be visible.",
                   fstats, hasSize(1));
        FlowStats flow2_3 = fstats.get(0);
        flow2_3.expectCount(1).expectOutputAction(num3);

        fstats = svcController.getFlowStats(
            new MidoMatch().setNetworkSource(ip3.address)
                           .setNetworkDestination(ip2.address));
        assertThat("Only one FlowStats object should be visible.",
                   fstats, hasSize(1));
        FlowStats flow3_2 = fstats.get(0);
        flow3_2.expectCount(1).expectOutputAction(num2);

        // Repeat the pings and verify that the packet counts increase.
        assertThat(
            format("The tap %s should have sent the packet", tap1.getName()),
            tap1.send(ping1_3));

        assertThat(
            format("The tap %s should have sent the packet", tap2.getName()),
            tap2.send(ping2_3));

        // Sleep to let OVS update its stats.
        Thread.sleep(1000);

        // Verify that the flow counts have increased as expected.
        flow1_3.findSameInList(svcController.getFlowStats(flow1_3.getMatch()))
               .expectCount(2).expectOutputAction(num3);
        flow3_1.findSameInList(svcController.getFlowStats(flow3_1.getMatch()))
               .expectCount(2).expectOutputAction(num1);
        flow2_3.findSameInList(svcController.getFlowStats(flow2_3.getMatch()))
               .expectCount(2).expectOutputAction(num3);
        flow3_2.findSameInList(svcController.getFlowStats(flow3_2.getMatch()))
               .expectCount(2).expectOutputAction(num2);

        // Now remove p3 and verify that all flows are removed since they
        // are ingress or egress at p3.
        ovsBridge.deletePort("pingTestInt");
        Thread.sleep(2000);
        fstats = svcController.getFlowStats(icmpMatch);
        assertThat("The list of FlowStats should be empty", fstats, hasSize(0));
    }
}
