/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.monitoring.vrn;

import java.util.UUID;
import static java.lang.String.format;

import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.MetricsRegistry;
import org.openflow.protocol.OFPhysicalPort;
import org.openflow.protocol.OFPortStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.Matchers.hasSize;

import com.midokura.midolman.monitoring.metrics.vrn.VifMetrics;
import com.midokura.midolman.state.PortDirectory;
import com.midokura.midolman.vrn.AbstractVrnControllerTest;

/**
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 5/7/12
 */
public class MonitoringVrnObserverTest extends AbstractVrnControllerTest {

    private static final Logger log = LoggerFactory
            .getLogger(MonitoringVrnObserverTest.class);

    OFPhysicalPort port1;
    OFPhysicalPort port2;
    MetricsRegistry registry;

    @Override
    @BeforeMethod
    public void setUp() throws Exception {
        super.setUp();

        // get the target metrics registry
        registry = Metrics.defaultRegistry();

        UUID routerId = createNewRouter();

        int portNumber = 36;

        PortDirectory.MaterializedRouterPortConfig portConfig =
            newMaterializedPort(routerId, portNumber,
                                new byte[]{10, 12, 13, 14, 15, 37});

        port1 = toOFPhysicalPort(portNumber, "port1", portConfig);

        portNumber = 37;
        portConfig =
            newMaterializedPort(routerId, portNumber,
                                new byte[]{10, 12, 13, 14, 15, 39});

        port2 = toOFPhysicalPort(portNumber, "port2", portConfig);
    }

    @Override
    @AfterMethod
    public void tearDown() throws Exception {
        super.tearDown();
    }

    @Test
    public void testAddRemovePort() throws Exception {
        String port1uuid = getPortExternalId(port1).toString();

        assertMetricsCount("The metrics registry should be empty initially", 0);

        // add a port
        getVrnController().onPortStatus(port1,
                                        OFPortStatus.OFPortReason.OFPPR_ADD);

        assertMetricsCount(
            "The metric registry should have the new metrics visible", 4);

        assertMetricExistsForPort(registry, port1uuid, "rxBytes");
        assertMetricExistsForPort(registry, port1uuid, "txBytes");
        assertMetricExistsForPort(registry, port1uuid, "rxPackets");
        assertMetricExistsForPort(registry, port1uuid, "txPackets");

        // remove the port
        getVrnController().onPortStatus(port1,
                                        OFPortStatus.OFPortReason.OFPPR_DELETE);

        assertMetricsCount(
            "The metrics registry should not show the old port metrics", 0);
    }

    @Test
    public void testTwoPorts() throws Exception {
        String port1uuid = getPortExternalId(port1).toString();
        String port2uuid = getPortExternalId(port2).toString();

        assertMetricsCount("The metrics registry should be empty initially", 0);

        // add a port
        getVrnController().onPortStatus(port1,
                                        OFPortStatus.OFPortReason.OFPPR_ADD);

        assertMetricsCount(
            "The metric registry should have the new metrics visible", 4);

        assertMetricExistsForPort(registry, port1uuid, "rxBytes");
        assertMetricExistsForPort(registry, port1uuid, "txBytes");
        assertMetricExistsForPort(registry, port1uuid, "rxPackets");
        assertMetricExistsForPort(registry, port1uuid, "txPackets");

        // add the second port
        getVrnController().onPortStatus(port2,
                                        OFPortStatus.OFPortReason.OFPPR_ADD);

        assertMetricsCount(
            "The metric registry should have the new metrics visible", 8);

        assertMetricExistsForPort(registry, port1uuid, "rxBytes");
        assertMetricExistsForPort(registry, port1uuid, "txBytes");
        assertMetricExistsForPort(registry, port1uuid, "rxPackets");
        assertMetricExistsForPort(registry, port1uuid, "txPackets");
        assertMetricExistsForPort(registry, port2uuid, "rxBytes");
        assertMetricExistsForPort(registry, port2uuid, "txBytes");
        assertMetricExistsForPort(registry, port2uuid, "rxPackets");
        assertMetricExistsForPort(registry, port2uuid, "txPackets");

        // remove the port
        getVrnController().onPortStatus(port1,
                                        OFPortStatus.OFPortReason.OFPPR_DELETE);

        assertMetricsCount(
            "The metrics registry should not show the old port metrics", 4);

        assertMetricExistsForPort(registry, port2uuid, "rxBytes");
        assertMetricExistsForPort(registry, port2uuid, "txBytes");
        assertMetricExistsForPort(registry, port2uuid, "rxPackets");
        assertMetricExistsForPort(registry, port2uuid, "txPackets");

        // remove the port
        getVrnController().onPortStatus(port2,
                                        OFPortStatus.OFPortReason.OFPPR_DELETE);

        assertMetricsCount(
            "The metrics registry should not show the old port metrics", 0);
    }

    private void assertMetricExistsForPort(MetricsRegistry registry,
                                           String port2uuid,
                                           String metricName) {
        assertThat(
            format("the metrics registry doesn't contain metric %s for port %s",
                   metricName, port2uuid),
            registry.allMetrics(),
            hasKey(
                allOf(
                    hasProperty("scope", is(port2uuid)),
                    //hasProperty("group", is(VifMetrics.groupName())),
                    hasProperty("name", is(metricName))
                )));
    }

    protected void assertMetricsCount(int count) {
        assertMetricsCount(
            format("The metrics registry should have only %d metrics", count),
            count);
    }

    protected void assertMetricsCount(String message, int count) {
        assertThat(message, registry.allMetrics().entrySet(), hasSize(count));
    }
}
