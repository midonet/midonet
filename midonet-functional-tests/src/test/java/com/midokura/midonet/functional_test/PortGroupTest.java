/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test;

import java.io.IOException;
import java.util.UUID;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import com.midokura.midolman.mgmt.data.dto.client.DtoRule;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnectionImpl;
import com.midokura.packets.IntIPv4;
import com.midokura.packets.MAC;
import com.midokura.midonet.functional_test.mocks.MidolmanMgmt;
import com.midokura.midonet.functional_test.mocks.MockMidolmanMgmt;
import com.midokura.midonet.functional_test.topology.Bridge;
import com.midokura.midonet.functional_test.topology.BridgePort;
import com.midokura.midonet.functional_test.topology.LogicalBridgePort;
import com.midokura.midonet.functional_test.topology.LogicalRouterPort;
import com.midokura.midonet.functional_test.topology.OvsBridge;
import com.midokura.midonet.functional_test.topology.PortGroup;
import com.midokura.midonet.functional_test.topology.Router;
import com.midokura.midonet.functional_test.topology.RuleChain;
import com.midokura.midonet.functional_test.topology.TapWrapper;
import com.midokura.midonet.functional_test.topology.Tenant;
import com.midokura.midonet.functional_test.utils.MidolmanLauncher;
import com.midokura.util.lock.LockHelper;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.removeBridge;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.removeTapWrapper;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.removeTenant;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.sleepBecause;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.stopMidolman;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.stopMidolmanMgmt;
import static com.midokura.midonet.functional_test.utils.MidolmanLauncher.ConfigType.Default;

/**
 * This class emulates a MidoNet client implementing SecurityGroups using
 * MidoNet PortGroups to track/match SecurityGroup membership.
 */
public class PortGroupTest {
    MidolmanMgmt mgmt;
    MidolmanLauncher midolman1;
    Tenant tenant1;
    LogicalBridgePort bLogPort1;
    LogicalRouterPort rPort1;
    TapWrapper tap1;
    TapWrapper tap2;
    TapWrapper tap3;
    TapWrapper tap4;
    OvsBridge ovsBridge1;

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
    public void setUp() throws IOException, InterruptedException {
        OpenvSwitchDatabaseConnectionImpl ovsdb =
                new OpenvSwitchDatabaseConnectionImpl(
                        "Open_vSwitch", "127.0.0.1", 12344);
        mgmt = new MockMidolmanMgmt(false);
        midolman1 = MidolmanLauncher.start(Default, "PortGroupTest");
        if (ovsdb.hasBridge("smoke-br"))
            ovsdb.delBridge("smoke-br");
        ovsBridge1 = new OvsBridge(ovsdb, "smoke-br");

        tenant1 = new Tenant.Builder(mgmt).setName("tenant-portgroup").build();
        Bridge bridge1 = tenant1.addBridge().setName("br1").build();
        Router rtr = tenant1.addRouter().setName("rtr1").build();
        // Link the Bridge and Router
        rPort1 = rtr.addLinkPort()
                .setNetworkAddress("10.0.0.0")
                .setNetworkLength(24)
                .setPortAddress("10.0.0.1").build();
        bLogPort1 = bridge1.addLinkPort().build();
        rPort1.link(bLogPort1);
        // All sec groups should allow packets from the router (10.0.0.1/32)

        // Sec Group 1 allows receiving packets from nwAddr in 10.1.1.0/24.
        // Create a port group for SecGroup1's membership.
        PortGroup sg1Members =
                tenant1.addPortGroup().setName("Members1").build();
        RuleChain sg1 = tenant1.addChain().setName("SecGroup1").build();
        sg1.addRule().matchNwSrc(IntIPv4.fromString("10.1.1.0"), 24)
                .setSimpleType(DtoRule.Accept).build();
        sg1.addRule().matchNwSrc(IntIPv4.fromString("10.0.0.1"), 32)
                .setSimpleType(DtoRule.Accept).build();
        sg1.addRule().matchDlType((short) 0x0806)
                .setSimpleType(DtoRule.Accept).build();

        // Sec Group 2 allows receiving packets from nwAddr in 10.2.2.0/24,
        // from its own members, and from members of SecGroup1.
        // Use a separate chain to track SG1's membership.
        PortGroup sg2Members =
                tenant1.addPortGroup().setName("Members2").build();
        RuleChain sg2 = tenant1.addChain().setName("SecGroup2").build();
        sg2.addRule().matchPortGroup(sg1Members.getId())
                .setSimpleType(DtoRule.Accept).build();
        sg2.addRule().matchPortGroup(sg2Members.getId())
                .setSimpleType(DtoRule.Accept).build();
        sg2.addRule().matchNwSrc(IntIPv4.fromString("10.2.2.0"), 24)
                .setSimpleType(DtoRule.Accept).build();
        sg2.addRule().matchNwSrc(IntIPv4.fromString("10.0.0.1"), 32)
                .setSimpleType(DtoRule.Accept).build();
        sg2.addRule().matchDlType((short) 0x0806)
                .setSimpleType(DtoRule.Accept).build();

        // port1 will be in security group 1.
        RuleChain portOutChain1 =
                tenant1.addChain().setName("port1_out").build();
        portOutChain1.addRule().setPosition(1).setJump("SecGroup1").build();
        // Add the final drop rule as the default if no Accept rule matches.
        portOutChain1.addRule().setPosition(2)
                .setSimpleType(DtoRule.Drop).build();
        BridgePort bPort1 = bridge1.addPort()
                .setOutboundFilter(portOutChain1.chain.getId())
                .setPortGroups(new UUID[] {sg1Members.getId()}).build();

        // port2 will be in security group 2.
        RuleChain portOutChain2 =
                tenant1.addChain().setName("port2_out").build();
        portOutChain2.addRule().setPosition(1).setJump("SecGroup2").build();
        // Add the final drop rule as the default if no Accept rule matches.
        portOutChain2.addRule().setPosition(2)
                .setSimpleType(DtoRule.Drop).build();
        BridgePort bPort2 = bridge1.addPort()
                .setOutboundFilter(portOutChain2.chain.getId())
                .setPortGroups(new UUID[] {sg2Members.getId()}).build();

        // port3 will be in both security groups 1 and 2.
        RuleChain portOutChain3 =
                tenant1.addChain().setName("port3_out").build();
        portOutChain3.addRule().setPosition(1).setJump("SecGroup1").build();
        portOutChain3.addRule().setPosition(2).setJump("SecGroup2").build();
        // Add the final drop rule as the default if no Accept rule matches.
        portOutChain3.addRule().setPosition(3)
                .setSimpleType(DtoRule.Drop).build();
        BridgePort bPort3 = bridge1.addPort()
                .setOutboundFilter(portOutChain3.chain.getId())
                .setPortGroups(
                        new UUID[] {sg1Members.getId(), sg2Members.getId()})
                .build();

        // port4 will not be in any security group.
        BridgePort bPort4 = bridge1.addPort().build();

        tap1 = new TapWrapper("SecGroupTap1");
        ovsBridge1.addSystemPort(bPort1.getId(), tap1.getName());
        tap2 = new TapWrapper("SecGroupTap2");
        ovsBridge1.addSystemPort(bPort2.getId(), tap2.getName());
        tap3 = new TapWrapper("SecGroupTap3");
        ovsBridge1.addSystemPort(bPort3.getId(), tap3.getName());
        tap4 = new TapWrapper("SecGroupTap4");
        ovsBridge1.addSystemPort(bPort4.getId(), tap4.getName());

        sleepBecause("we need the network to boot up", 10);
    }

    @After
    public void tearDown() {
        removeTapWrapper(tap1);
        removeTapWrapper(tap2);
        removeTapWrapper(tap3);
        removeTapWrapper(tap4);

        removeBridge(ovsBridge1);
        stopMidolman(midolman1);

        if (null != rPort1)
            rPort1.unlink();
        removeTenant(tenant1);
        stopMidolmanMgmt(mgmt);
    }

    @Test
    public void test() {
        MAC mac1 = MAC.fromString("02:aa:bb:cc:dd:d1");
        MAC mac2 = MAC.fromString("02:aa:bb:cc:dd:d2");
        MAC mac3 = MAC.fromString("02:aa:bb:cc:dd:d3");
        MAC mac4 = MAC.fromString("02:aa:bb:cc:dd:d4");
        IntIPv4 rtrIp = IntIPv4.fromString("10.0.0.1");
        IntIPv4 ip1 = IntIPv4.fromString("10.0.0.2");
        IntIPv4 ip2 = IntIPv4.fromString("10.0.0.3");
        IntIPv4 ip3 = IntIPv4.fromString("10.0.0.4");
        IntIPv4 ip4 = IntIPv4.fromString("10.0.0.5");

        // Send ARPs from each edge port so that the bridge can learn MACs.
        assertThat("We failed to send the ARP request.",
                tap1.send(PacketHelper.makeArpRequest(mac1, ip1, rtrIp)));
        assertThat("We didn't receive the router's ARP reply.",
                tap1.recv(), notNullValue());
        assertThat("We failed to send the ARP request.",
                tap2.send(PacketHelper.makeArpRequest(mac2, ip2, rtrIp)));
        assertThat("We didn't receive the router's ARP reply.",
                tap2.recv(), notNullValue());
        assertThat("We failed to send the ARP request.",
                tap3.send(PacketHelper.makeArpRequest(mac3, ip3, rtrIp)));
        assertThat("We didn't receive the router's ARP reply.",
                tap3.recv(), notNullValue());
        assertThat("We failed to send the ARP request.",
                tap4.send(PacketHelper.makeArpRequest(mac4, ip4, rtrIp)));
        assertThat("We didn't receive the router's ARP reply.",
                tap4.recv(), notNullValue());

        // A packet from 10.3.3.4 and port 4 should fail to arrive at port1
        // because SecGroup1 only accepts packets from 10.1.1.0/24.
        byte[] pkt = PacketHelper.makeIcmpEchoRequest(
                mac4, IntIPv4.fromString("10.3.3.4"), mac1, ip1);
        assertThat("We failed to send the packet from port 4 to 1.",
                tap4.send(pkt));
        assertThat("The packet should not have arrived at port 1.",
                tap1.recv(), nullValue());

        // A packet from 10.2.2.4 and port 4 should fail to arrive at port1
        // because SecGroup1 only accepts packets from 10.1.1.0/24.
        pkt = PacketHelper.makeIcmpEchoRequest(
                mac4, IntIPv4.fromString("10.2.2.4"), mac1, ip1);
        assertThat("We failed to send the packet from port 4 to 1.",
                tap4.send(pkt));
        assertThat("The packet should not have arrived at port 1.",
                tap1.recv(), nullValue());

        // A packet from 10.1.1.4 and port 4 should arrive at port1 because its
        // nwSrc is inside an acceptable prefix.
        pkt = PacketHelper.makeIcmpEchoRequest(
                mac4, IntIPv4.fromString("10.1.1.4"), mac1, ip1);
        assertThat("We failed to send the packet from port 4 to 1.",
                tap4.send(pkt));
        assertThat("The packet should have arrived at port 1.",
                tap1.recv(), allOf(notNullValue(), equalTo(pkt)));

        // A packet from 10.1.1.4 and port 4 should fail to arrive at port2
        // because port 4 isn't in the SecGroups accepted by SecGroup2 nor is
        // the packet's source IP in 10.2.2.0/24.
        pkt = PacketHelper.makeIcmpEchoRequest(
                mac4, IntIPv4.fromString("10.1.1.4"), mac2, ip2);
        assertThat("We failed to send the packet from port 4 to 2.",
                tap4.send(pkt));
        assertThat("The packet should not have arrived at port 2.",
                tap2.recv(), nullValue());

        // A packet from 10.3.3.4 and port 4 should fail to arrive at port2
        // because port 4 isn't in the SecGroups accepted by SecGroup2 nor is
        // the packet's source IP in 10.2.2.0/24.
        pkt = PacketHelper.makeIcmpEchoRequest(
                mac4, IntIPv4.fromString("10.3.3.4"), mac2, ip2);
        assertThat("We failed to send the packet from port 4 to 2.",
                tap4.send(pkt));
        assertThat("The packet should not have arrived at port 2.",
                tap2.recv(), nullValue());

        // A packet from 10.2.2.4 and port 4 should arrive at port2 because its
        // nwSrc is inside an acceptable prefix.
        pkt = PacketHelper.makeIcmpEchoRequest(
                mac4, IntIPv4.fromString("10.2.2.4"), mac2, ip2);
        assertThat("We failed to send the packet from port 4 to 2.",
                tap4.send(pkt));
        assertThat("The packet should have arrived at port 2.",
                tap2.recv(), allOf(notNullValue(), equalTo(pkt)));

        // A packet from 10.3.3.4 and port 4 should fail to arrive at port3
        // because it's not from an accepted SecGroup or IP src prefix.
        pkt = PacketHelper.makeIcmpEchoRequest(
                mac4, IntIPv4.fromString("10.3.3.4"), mac3, ip3);
        assertThat("We failed to send the packet from port 4 to 3.",
                tap4.send(pkt));
        assertThat("The packet should not have arrived at port 3.",
                tap3.recv(), nullValue());

        // A packet from 10.1.1.4 and port 4 should arrive at port3 because its
        // nwSrc is inside an acceptable prefix.
        pkt = PacketHelper.makeIcmpEchoRequest(
                mac4, IntIPv4.fromString("10.1.1.4"), mac3, ip3);
        assertThat("We failed to send the packet from port 4 to 3.",
                tap4.send(pkt));
        assertThat("The packet should have arrived at port 3.",
                tap3.recv(), allOf(notNullValue(), equalTo(pkt)));

        // A packet from 10.2.2.4 and port 4 should arrive at port3 because its
        // nwSrc is inside an acceptable prefix.
        pkt = PacketHelper.makeIcmpEchoRequest(
                mac4, IntIPv4.fromString("10.2.2.4"), mac3, ip3);
        assertThat("We failed to send the packet from port 4 to 3.",
                tap4.send(pkt));
        assertThat("The packet should have arrived at port 3.",
                tap3.recv(), allOf(notNullValue(), equalTo(pkt)));

        // A packet from 10.3.3.3 and port 3 should fail to arrive at port1
        // even though they are in the same security group. SecGroup1 does not
        // necessarily accept packets from members of its own group.
        pkt = PacketHelper.makeIcmpEchoRequest(
                mac3, IntIPv4.fromString("10.3.3.3"), mac1, ip1);
        assertThat("We failed to send the packet from port 3 to 1.",
                tap3.send(pkt));
        assertThat("The packet should not have arrived at port 1.",
                tap1.recv(), nullValue());

        // A packet from 10.3.3.3 and port 3 should arrive at port2 because
        // they're both in SecGroup2, which accepts packets from its own
        // members.
        pkt = PacketHelper.makeIcmpEchoRequest(
                mac3, IntIPv4.fromString("10.3.3.3"), mac2, ip2);
        assertThat("We failed to send the packet from port 3 to 2.",
                tap3.send(pkt));
        assertThat("The packet should have arrived at port 2.",
                tap2.recv(), allOf(notNullValue(), equalTo(pkt)));

        // A packet from 10.3.3.1 and port 1 should arrive at port2 because
        // port2 is in SecGroup2 which accepts packets from SecGroup1.
        pkt = PacketHelper.makeIcmpEchoRequest(
                mac1, IntIPv4.fromString("10.3.3.1"), mac2, ip2);
        assertThat("We failed to send the packet from port 1 to 2.",
                tap1.send(pkt));
        assertThat("The packet should have arrived at port 2.",
                tap2.recv(), allOf(notNullValue(), equalTo(pkt)));

        // A packet from 10.3.3.2 and port 2 should arrive at port3 because
        // they're both in SecGroup2, which accepts packets from its own
        // members.
        pkt = PacketHelper.makeIcmpEchoRequest(
                mac2, IntIPv4.fromString("10.3.3.2"), mac3, ip3);
        assertThat("We failed to send the packet from port 2 to 3.",
                tap2.send(pkt));
        assertThat("The packet should have arrived at port 3.",
                tap3.recv(), allOf(notNullValue(), equalTo(pkt)));

        // A packet from 10.3.3.1 and port 1 should arrive at port3 because
        // port3 is in SecGroup2 which accepts packets from SecGroup1.
        pkt = PacketHelper.makeIcmpEchoRequest(
                mac1, IntIPv4.fromString("10.3.3.1"), mac3, ip3);
        assertThat("We failed to send the packet from port 1 to 3.",
                tap1.send(pkt));
        assertThat("The packet should have arrived at port 3.",
                tap3.recv(), allOf(notNullValue(), equalTo(pkt)));

        // A packet from 10.3.3.1 and port 1 should arrive at port4 because
        // port4 isn't in any Security Group.
        pkt = PacketHelper.makeIcmpEchoRequest(
                mac1, IntIPv4.fromString("10.3.3.1"), mac4, ip4);
        assertThat("We failed to send the packet from port 1 to 4.",
                tap1.send(pkt));
        assertThat("The packet should have arrived at port 4.",
                tap4.recv(), allOf(notNullValue(), equalTo(pkt)));

    }

    public void testFoo() {}
}
