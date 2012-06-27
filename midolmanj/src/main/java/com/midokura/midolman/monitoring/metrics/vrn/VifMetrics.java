/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.monitoring.metrics.vrn;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.UUID;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.Counter;
import com.yammer.metrics.core.Metric;
import com.yammer.metrics.core.MetricName;
import com.yammer.metrics.core.MetricPredicate;
import org.openflow.protocol.statistics.OFPortStatisticsReply;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.vrn.VRNController;

/**
 * Class that can add/delete Counters for different virtual ports on demand.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 5/2/12
 */
public class VifMetrics {

    private static final Logger log = LoggerFactory
        .getLogger(VifMetrics.class);

    static Map<UUID, Runnable> map = new HashMap<UUID, Runnable>();

    static ScheduledThreadPoolExecutor executorService =
        new ScheduledThreadPoolExecutor(1);

    public static String groupName() {
        return VifMetrics.class.getPackage().getName();
    }

    public static void enableVirtualPortMetrics(final VRNController controller,
                                                short portNum,
                                                final UUID portId,
                                                String portName) {
        final Counters counters = new Counters();

        counters.rxBytes = makeCounter(portId, "rxBytes");
        counters.txBytes = makeCounter(portId, "txBytes");
        counters.rxPackets = makeCounter(portId, "rxPackets");
        counters.txPackets = makeCounter(portId, "txPackets");

        counters.controller = controller;
        counters.portId = portId;
        counters.portNum = portNum;
        counters.portName = portName;

        Runnable command = new Runnable() {
            @Override
            public void run() {
                try {
                    log.debug("Starting statistics collection for port {}.",
                              counters.portNum);

                    final int xid[] = new int[1];

                    // schedule the request and make sure we wait until it's
                    // executed by the midolman event loop thread.
                    controller.getReactor().submit(new Runnable() {
                        @Override
                        public void run() {
                            VRNController myController = counters.controller;

                            xid[0] = myController.sendPortStatsRequest(
                                counters.portNum);
                        }
                    }).get();

                    // this is blocking the current thread and waits for the
                    // the reply to be posted by the midolman event loop when
                    // it's received from the ovs daemon.
                    List<OFPortStatisticsReply> portStatistics =
                        counters.controller.getPortStatsReply(xid[0]);

                    if (portStatistics == null) {
                        log.error("We got an empty statistics reply");
                        return;
                    }

                    int rxPackets = 0;
                    int txPackets = 0;
                    int rxBytes = 0;
                    int txBytes = 0;

                    for (OFPortStatisticsReply portStatistic : portStatistics) {
                        rxPackets += portStatistic.getReceievePackets();
                        txPackets += portStatistic.getTransmitPackets();
                        rxBytes += portStatistic.getReceiveBytes();
                        txBytes += portStatistic.getTransmitBytes();
                    }

                    updateCounter(counters.rxPackets, rxPackets);
                    updateCounter(counters.txPackets, txPackets);
                    updateCounter(counters.rxBytes, rxBytes);
                    updateCounter(counters.txBytes, txBytes);
                } catch (Throwable tx) {
                    log.error(
                        "Got a Throwable while collecting statistics for port {}",
                        counters.portNum, tx);
                } finally {
                    log.debug("Statistics collection done for port {}.",
                              counters.portNum);
                }
            }
        };

        log.debug("Adding periodic scheduled job to service: {}, {}",
                  executorService);
        executorService.scheduleAtFixedRate(command, 200, 950,
                                            TimeUnit.MILLISECONDS);

        map.put(portId, command);
    }

    private static void updateCounter(Counter counter, int value) {
        counter.inc(value - counter.count());
    }

    private static Counter makeCounter(UUID portId, String metricName) {
        return Metrics.newCounter(
            new MetricName(VifMetrics.class, metricName, portId.toString()));
    }

    public static void disableVirtualPortMetrics(VRNController controller,
                                                 int num, final UUID portId) {
        Runnable runnable = map.get(portId);

        if (runnable != null) {
            executorService.remove(runnable);
        }

        SortedMap<String, SortedMap<MetricName, Metric>> metrics =
            Metrics.defaultRegistry().groupedMetrics(new MetricPredicate() {
                @Override
                public boolean matches(MetricName name, Metric metric) {
                    return name.getScope().equals(portId.toString());
                }
            });

        for (SortedMap<MetricName, Metric> metricsSet : metrics.values()) {
            for (MetricName metricName : metricsSet.keySet()) {
                Metrics.defaultRegistry().removeMetric(metricName);
            }
        }
    }

    static class Counters {
        UUID portId;
        short portNum;
        String portName;
        VRNController controller;

        Counter rxPackets, txPackets;
        Counter rxBytes, txBytes;
    }
}
