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


    public String groupName() {
        return VifMetrics.class.getPackage().getName();
    }

    public void enableVirtualPortMetrics(
                                                final UUID portId
                                                ) {
        final Counters counters = new Counters();

        counters.rxBytes = makeCounter(portId, "rxBytes");
        counters.txBytes = makeCounter(portId, "txBytes");
        counters.rxPackets = makeCounter(portId, "rxPackets");
        counters.txPackets = makeCounter(portId, "txPackets");

        counters.portId = portId;

        MidoReporter.notifyNewMetricTypeForTarget(
            new MetricName(VifMetrics.class, "", portId.toString()));

    }


    public void processStatsReply(UUID portID, Port.Stats portStatistics) {
        if (portStatistics == null) {
            log.error("We got an empty statistics reply");
            return;
        }

        Counters counters = countersMap.get(portID);
        if (counters != null) {
            log.debug("Updating counters for a port");
            updateCounter(counters.rxPackets, (int) portStatistics.getRxPackets());
            updateCounter(counters.txPackets, (int)portStatistics.getTxPackets());
            updateCounter(counters.rxBytes, (int)portStatistics.getRxBytes());
            updateCounter(counters.txBytes, (int)portStatistics.getTxBytes());
        }
    }

    public void disableVirtualPortMetrics(final UUID portID) {
       log.debug("Disabling the metrics for port {}", portID);
       SortedMap<String, SortedMap<MetricName, Metric>> metrics =
               Metrics.defaultRegistry().groupedMetrics(new MetricPredicate() {

                 @Override
                 public boolean matches(MetricName name, Metric metric) {
                                        return name.getScope().equals(portID.toString());
                 }
               });

               for (SortedMap<MetricName, Metric> metricsSet : metrics.values()) {
                   for (MetricName metricName : metricsSet.keySet()) {
                       Metrics.defaultRegistry().removeMetric(metricName);
                   }
               }
      }


    private void updateCounter(Counter counter, int value) {
        counter.inc(value - counter.count());
    }

    private Counter makeCounter(UUID portId, String metricName) {
        return Metrics.newCounter(
            new MetricName(VifMetrics.class, metricName, portId.toString()));
    }

    public class Counters {
        UUID portId;

        Counter rxPackets, txPackets;
        Counter rxBytes, txBytes;
    }
}
