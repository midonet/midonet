/*
 * Copyright 2011 Midokura Europe SARL
 */

package org.midonet.functional_test;

import java.util.concurrent.TimeUnit;

import akka.util.Duration;
import org.junit.Test;

import org.midonet.midolman.topology.LocalPortActive;
import org.midonet.client.resource.Bridge;
import org.midonet.client.resource.BridgePort;
import org.midonet.functional_test.utils.TapWrapper;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.MAC;

import static org.midonet.functional_test.FunctionalTestsHelper
    .removeTapWrapper;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

public class BridgePortDeleteTest extends TestBase {

    //Tenant tenant1;
    IPv4Addr ip1 = IPv4Addr.fromString("192.168.231.2");
    IPv4Addr ip2 = IPv4Addr.fromString("192.168.231.3");
    IPv4Addr ip3 = IPv4Addr.fromString("192.168.231.4");

    BridgePort bPort1;
    BridgePort bPort2;
    BridgePort bPort3;
    Bridge bridge1;
    TapWrapper tap1;
    TapWrapper tap2;
    TapWrapper tap3;

    @Override
    public void setup() {
        bridge1 = apiClient.addBridge()
            .tenantId("del-br-port-test").name("br1").create();
        bPort1 = bridge1.addExteriorPort().create();
        tap1 = new TapWrapper("tapBridgeDel1");
        thisHost.addHostInterfacePort()
            .interfaceName(tap1.getName())
            .portId(bPort1.getId()).create();

        LocalPortActive msg = probe.expectMsgClass(
            Duration.create(10, TimeUnit.SECONDS),
            LocalPortActive.class);
        assertThat(msg.portID(), equalTo(bPort1.getId()));
        assertThat(msg.active(), equalTo(true));

        bPort2 = bridge1.addExteriorPort().create();
        tap2 = new TapWrapper("tapBridgeDel2");
        thisHost.addHostInterfacePort()
            .interfaceName(tap2.getName())
            .portId(bPort2.getId()).create();

        msg = probe.expectMsgClass(
            Duration.create(10, TimeUnit.SECONDS),
            LocalPortActive.class);
        assertThat(msg.portID(), equalTo(bPort2.getId()));
        assertThat(msg.active(), equalTo(true));

        bPort3 = bridge1.addExteriorPort().create();
        tap3 = new TapWrapper("tapBridgeDel3");
        thisHost.addHostInterfacePort()
            .interfaceName(tap3.getName())
            .portId(bPort3.getId()).create();

        msg = probe.expectMsgClass(
            Duration.create(10, TimeUnit.SECONDS),
            LocalPortActive.class);
        assertThat(msg.portID(), equalTo(bPort3.getId()));
        assertThat(msg.active(), equalTo(true));
    }

    @Override
    protected void teardown() {
        removeTapWrapper(tap1);
        removeTapWrapper(tap2);
        removeTapWrapper(tap3);
    }

    private void sendPacket(byte[] pkt, TapWrapper fromTap,
                            TapWrapper[] toTaps) {
        assertThat("The ARP packet was sent properly.", fromTap.send(pkt));
        for (TapWrapper dstTap : toTaps)
            assertThat("The received packet is the same as the one sent",
                    dstTap.recv(), equalTo(pkt));
    }

    @Test
    public void testPortDelete() throws InterruptedException {
        // Use different MAC addrs from other tests (unlearned MACs).
        MAC mac1 = MAC.fromString("02:00:00:00:aa:01");
        MAC mac2 = MAC.fromString("02:00:00:00:aa:02");
        MAC mac3 = MAC.fromString("02:00:00:00:aa:03");

        log.info("Send broadcast from Mac1/port1.");
        byte[] pkt = PacketHelper.makeArpRequest(mac1, ip1, ip2);
        sendPacket(pkt, tap1, new TapWrapper[] {tap2, tap3});

        log.info("Send unicast from Mac2/port2 to mac1.");
        pkt = PacketHelper.makeIcmpEchoRequest(mac2, ip2, mac1, ip1);
        assertThat(
            String.format(
                "The ICMP echo packet was properly sent via %s", tap2.getName()),
            tap2.send(pkt));

        assertThat(
            String.format("We received the same packet on %s", tap1.getName()),
            tap1.recv(), equalTo(pkt));

        // The last packet caused the bridge to learn the mapping Mac2->port2.
        // That also triggered invalidation of flooded flows: flow1.
        log.info("Resend the ARP to re-install the flooded flow.");
        pkt = PacketHelper.makeArpRequest(mac1, ip1, ip2);
        sendPacket(pkt, tap1, new TapWrapper[] {tap2, tap3});

        // Delete port1. It is the destination of flow2 and
        // the origin of flow1 - so expect both flows to be removed.
        log.info("Removing tap1 and expect port1 to be set inactive");
        tap1.remove();
        LocalPortActive msg = probe.expectMsgClass(
            Duration.create(10, TimeUnit.SECONDS),
            LocalPortActive.class);
        assertThat(msg.portID(), equalTo(bPort1.getId()));
        assertThat(msg.active(), equalTo(false));
    }
}
