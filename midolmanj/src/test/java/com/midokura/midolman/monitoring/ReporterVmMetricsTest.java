/*
 * Copyright (c) 2012 Midokura Pte. Ltd
 */

package com.midokura.midolman.monitoring;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.midokura.cassandra.CassandraClient;
import com.midokura.midolman.host.services.HostService;
import com.midokura.midolman.monitoring.metrics.VMMetricsCollection;
import com.midokura.midolman.monitoring.store.CassandraStore;
import com.midokura.midolman.monitoring.store.MockStore;
import com.midokura.midolman.monitoring.store.Store;
import com.midokura.midolman.services.HostIdProviderService;
import org.apache.cassandra.config.ConfigurationException;
import org.apache.thrift.transport.TTransportException;
import org.cassandraunit.utils.EmbeddedCassandraServerHelper;
import org.junit.*;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;

public class ReporterVmMetricsTest extends AbstractModule {

    private final static org.slf4j.Logger log =
            LoggerFactory.getLogger(ReporterVmMetricsTest.class);

    String metricThreadCount = "ThreadCount";

    long startTime, endTime;
    Store store;

   @Before
   public void setUp() {
       store = new MockStore();
       store.initialize();
   }

    @Test
    public void reporterTest() throws InterruptedException, UnknownHostException {

        Injector injector = Guice.createInjector(new ReporterVmMetricsTest());
        VMMetricsCollection vmMetrics = injector.getInstance(VMMetricsCollection.class);
        vmMetrics.registerMetrics();

        startTime = System.currentTimeMillis();
        MidoReporter reporter = new MidoReporter(store, "MidonetMonitoring_test");
        reporter.start(1000, TimeUnit.MILLISECONDS);

        Thread.sleep(10000);
        List<String> metrics = store.getMetricsForType(VMMetricsCollection.class.getSimpleName());
        assertThat("We saved all the metrics in VMMetricsCollection", metrics.size(), is(vmMetrics.getMetricsCount()));

        String hostName = InetAddress.getLocalHost().getHostName();

        List<String> types = store.getMetricsTypeForTarget(hostName);
        assertThat("We saved the type VMMetricsCollection for this target", types.size(), is(1));
        assertThat("We save the right type", types.get(0), is(VMMetricsCollection.class.getSimpleName()));
        endTime = System.currentTimeMillis();

        Map<String, Long> res = store.getTSPoints(VMMetricsCollection.class.getSimpleName(), hostName, metricThreadCount, startTime, endTime);
        assertThat("We collected some TS point", res.size(), greaterThan(0));

    }

    @Override
    protected void configure() {
        final HostService hostService = new HostService();

        HostIdProviderService service = new HostIdProviderService() {
            @Override
            public UUID getHostId() {
                return hostService.getHostId();
            }
        };

        bind(HostIdProviderService.class).toInstance(service);
    }
}
