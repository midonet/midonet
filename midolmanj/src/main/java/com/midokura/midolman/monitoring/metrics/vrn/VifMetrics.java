/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.monitoring.metrics.vrn;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.UUID;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

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
public class VifMetrics {

    private static final Logger log = LoggerFactory
        .getLogger(VifMetrics.class);

    static final long MIN_STAT_REQUESTS_RATE = SECONDS.toMillis(1);
    static final long MAX_STAT_REPLY_TIMEOUT = MILLISECONDS.toMillis(300);

    static Set<UUID> watchedPorts = new HashSet<UUID>();

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

        watchedPorts.add(portId);

        MidoReporter.notifyNewMetricTypeForTarget(
            new MetricName(VifMetrics.class, "", portId.toString()));


        schedulePortStatsRequest(controller, counters, portId, System.currentTimeMillis());
    }

    public static void disableVirtualPortMetrics(VRNController controller,
                                                 int num, final UUID portId) {

        watchedPorts.remove(portId);

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
    private static void schedulePortStatsRequest(final VRNController reactor,
                                                 final Counters counters,
                                                 final UUID portId,
                                                 final long lastScheduledMillis) {
        if ( ! watchedPorts.contains(portId) )
            return;

        long remainingTimeout =
            Math.max(0, MIN_STAT_REQUESTS_RATE - (System.currentTimeMillis() - lastScheduledMillis));

        reactor.getReactor().schedule(new Runnable() {
            @Override
            public void run() {
                sendPortStatsRequest(reactor, counters, portId, MAX_STAT_REPLY_TIMEOUT);
            }
        }, remainingTimeout, MILLISECONDS);
    }

    private static void sendPortStatsRequest(final VRNController reactor,
                                             final Counters counters,
                                             final UUID portId,
                                             final long timeout) {

        final long lastSendTime = System.currentTimeMillis();

        reactor.sendPortStatsRequest(
            counters.portNum,
            new SuccessHandler<List<OFPortStatisticsReply>>() {
                @Override
                public void onSuccess(List<OFPortStatisticsReply> data) {
                    try {
                        processStatsReply(data, counters);
                    } finally {
                        schedulePortStatsRequest(reactor, counters,
                                                 portId, lastSendTime);
                    }
                }
            },
            timeout,
            new TimeoutHandler() {
                @Override
                public void onTimeout() {
                    schedulePortStatsRequest(reactor, counters, portId,
                                             lastSendTime);
                }
            }
        );
    }

    private static void processStatsReply(List<OFPortStatisticsReply> portStatistics,
                                          Counters counters) {
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
    }

    private static void updateCounter(Counter counter, int value) {
        counter.inc(value - counter.count());
    }

    private static Counter makeCounter(UUID portId, String metricName) {
        return Metrics.newCounter(
            new MetricName(VifMetrics.class, metricName, portId.toString()));
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
