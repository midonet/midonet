/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.functional_test.metrics;

import java.lang.management.ManagementFactory;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.management.MBeanServerConnection;
import javax.management.remote.JMXConnector;

import akka.util.Duration;
import org.junit.Test;
import org.midonet.midolman.topology.LocalPortActive;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import org.midonet.midolman.monitoring.metrics.vrn.VifMetrics;
import org.midonet.client.resource.Bridge;
import org.midonet.client.resource.BridgePort;
import org.midonet.functional_test.TestBase;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.MAC;
import org.midonet.functional_test.PacketHelper;
import org.midonet.functional_test.utils.TapWrapper;
import org.midonet.util.jmx.JMXAttributeAccessor;
import org.midonet.util.jmx.JMXHelper;
import static org.midonet.functional_test.FunctionalTestsHelper.assertPacketWasSentOnTap;
import static org.midonet.functional_test.FunctionalTestsHelper.removeTapWrapper;
import org.midonet.midolman.monitoring.MonitoringActor.MetricsUpdated;

/**
 * Functional tests for the metrics and monitoring.
 */
public class MetricsAndMonitoringTest extends TestBase {

    private Bridge bridge;
    private BridgePort bridgePort;
    private PacketHelper helperTap_int;
    private IPv4Addr ipDst;
    private IPv4Addr ipTap;
    private TapWrapper metricsTap;

    @Override
    public void setup() {

        midolman.getActorSystem().eventStream().subscribe(
            probe.ref(), MetricsUpdated.class);
        bridge = apiClient.addBridge().name("bridge-metrics")
                          .tenantId("tenant1")
                          .create();
        ipDst = IPv4Addr.fromString("192.168.231.4");
        MAC macInt = MAC.fromString("02:aa:bb:cc:ee:d1");

        ipTap = IPv4Addr.fromString("192.168.231.4");
        MAC macTap = MAC.fromString("02:aa:bb:cc:ee:d2");
        bridgePort = bridge.addExteriorPort().create();
        metricsTap = new TapWrapper("metricsTap");
        thisHost.addHostInterfacePort()
                .interfaceName(metricsTap.getName())
                .portId(bridgePort.getId()).create();
        LocalPortActive msg = probe.expectMsgClass(
            Duration.create(10, TimeUnit.SECONDS),
            LocalPortActive.class);
        assertThat(msg.portID(), equalTo(bridgePort.getId()));
        assertThat(msg.active(), equalTo(true));
        helperTap_int = new PacketHelper(macTap, ipTap, macInt, ipDst);
    }

    @Override
    public void teardown() {
        removeTapWrapper(metricsTap);
    }

    @Test
    public void testRxMetrics() throws Exception {

        // MM is embedded, retrieve its pid
        String runtimeMXBean = ManagementFactory.getRuntimeMXBean().getName();
        // runtimeMXBean will return something like localhost@pid
        String pid = runtimeMXBean.split("@")[0];
        JMXConnector connector =
            JMXHelper.newJvmJmxConnectorForPid(Integer.parseInt(pid));

        assertThat("The JMX connection should have been non-null.",
                   connector, notNullValue());

        MBeanServerConnection connection = connector.getMBeanServerConnection();

        JMXAttributeAccessor<Long> rxPkts =
            JMXHelper.<Long>newAttributeAccessor(connection, "Count")
                     .withNameDomain(VifMetrics.class)
                     .withNameKey("type", VifMetrics.class)
                     .withNameKey("scope", bridgePort.getId())
                     .withNameKey("name", "rxPackets")
                     .build();

        // record the current metric value
        long previousCount = rxPkts.getValue();

        // send a packet
        assertPacketWasSentOnTap(metricsTap,
                                 helperTap_int.makeIcmpEchoRequest(ipDst));
        fishForMetricsUpdated(bridgePort.getId(), previousCount + 1);

        // send another packet
        assertPacketWasSentOnTap(metricsTap,
                                 helperTap_int.makeIcmpEchoRequest(ipDst));
        fishForMetricsUpdated(bridgePort.getId(), previousCount + 2);

        assertPacketWasSentOnTap(metricsTap,
                                 helperTap_int.makeIcmpEchoRequest(ipDst));

        fishForMetricsUpdated(bridgePort.getId(), previousCount + 3);

        assertPacketWasSentOnTap(metricsTap,
                                 helperTap_int.makeIcmpEchoRequest(ipDst));
        fishForMetricsUpdated(bridgePort.getId(), previousCount + 4);

        connector.close();
    }

    void fishForMetricsUpdated(UUID portID, long expectedRxPacketNumber) {
        long timeOut = 15000L;
        long start = System.currentTimeMillis();
        long now = System.currentTimeMillis();
        MetricsUpdated msg = probe.expectMsgClass(
            Duration.create(10, TimeUnit.SECONDS), MetricsUpdated.class);

        while(msg.portID() != bridgePort.getId()
            && msg.portStatistics().getRxPackets() != expectedRxPacketNumber
            && now <= start+timeOut) {
            msg = probe.expectMsgClass(Duration.create(10, TimeUnit.SECONDS),
                                       MetricsUpdated.class);
            now = System.currentTimeMillis();
        }
        assertThat("we didn't get a metric update for the right port",
                   msg.portID(), is(portID));
        assertThat("the counter didn't increased properly",
                   msg.portStatistics().getRxPackets(), is(expectedRxPacketNumber));
    }
}
