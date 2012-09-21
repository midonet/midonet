/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.monitoring.metrics.vrn;

import java.util.*;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.midokura.sdn.dp.Port;
import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.Counter;
import com.yammer.metrics.core.Metric;
import com.yammer.metrics.core.MetricName;
import com.yammer.metrics.core.MetricPredicate;
import org.openflow.protocol.statistics.OFPortStatisticsReply;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.monitoring.MidoReporter;
import com.midokura.midolman.openflow.SuccessHandler;
import com.midokura.midolman.openflow.TimeoutHandler;
import com.midokura.midolman.vrn.VRNController;

/**
 * Class that can add/delete Counters for different virtual ports on demand.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 5/2/12
 */
public class VifMetrics  {

    Map<UUID, Counters> countersMap;

    public VifMetrics() {
        countersMap = new HashMap<UUID, Counters>();
    }

    private static final Logger log = LoggerFactory
        .getLogger(VifMetrics.class);


    public void enableVirtualPortMetrics(
                                                final UUID portId
                                                ) {

        countersMap.put(portId, new Counters(portId));

        MidoReporter.notifyNewMetricTypeForTarget(
            new MetricName(VifMetrics.class, "", portId.toString()));

    }


    public void updateStats(UUID portID, Port.Stats portStatistics) {
        if (portStatistics == null) {
            log.error("We got an empty statistics reply");
            return;
        }

        Counters counters = countersMap.get(portID);
        if (counters != null) {
            log.debug("Updating counters for a port");
            counters.update(
                    (int) portStatistics.getRxPackets(),
                    (int) portStatistics.getTxPackets(),
                    (int) portStatistics.getRxBytes(),
                    (int) portStatistics.getTxBytes()
            );
        }
    }

    public void disableVirtualPortMetrics(final UUID portID) {
        if (countersMap.containsKey(portID)) {
            countersMap.get(portID).disable();
        }
      }


    public class Counters {
        private Counter rxPackets, txPackets;
        private Counter rxBytes, txBytes;

        private UUID portId;

        public Counters(UUID portId) {
            this.portId = portId;

            rxBytes = makeCounter(portId, "rxBytes");
            txBytes = makeCounter(portId, "txBytes");
            rxPackets = makeCounter(portId, "rxPackets");
            txPackets = makeCounter(portId, "txPackets");
        }

        public void update(int rxPackets, int txPackets, int rxBytes, int txBytes) {
            updateCounter(this.rxPackets, rxPackets);
            updateCounter(this.rxBytes, rxBytes);
            updateCounter(this.txPackets, txPackets);
            updateCounter(this.txBytes, txBytes);
        }

        private Counter makeCounter(UUID portId, String metricName) {
            return Metrics.newCounter(
                    new MetricName(VifMetrics.class, metricName, portId.toString()));
        }

        private void updateCounter(Counter counter, int value) {
            counter.inc(value - counter.count());
        }

        private void disable() {
            Metrics.defaultRegistry().removeMetric(new MetricName(VifMetrics.class, "rxBytes", portId.toString()));
            Metrics.defaultRegistry().removeMetric(new MetricName(VifMetrics.class, "txBytes", portId.toString()));
            Metrics.defaultRegistry().removeMetric(new MetricName(VifMetrics.class, "rxPackets", portId.toString()));
            Metrics.defaultRegistry().removeMetric(new MetricName(VifMetrics.class, "txPackets", portId.toString()));
        }

    }
}
