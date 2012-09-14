/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.monitoring;

import akka.actor.Actor;
import akka.testkit.TestActorRef;
import akka.testkit.TestKit;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.midokura.midolman.AbstractMidolmanTestCase;
import com.midokura.midolman.MidolmanTestCase;
import com.midokura.midolman.config.MidolmanConfig;
import com.midokura.midolman.guice.MidolmanModule;
import com.midokura.midolman.guice.actors.TestableMidolmanActorsModule;
import com.midokura.midolman.guice.cluster.MockClusterClientModule;
import com.midokura.midolman.guice.config.MockConfigProviderModule;
import com.midokura.midolman.guice.datapath.MockDatapathModule;
import com.midokura.midolman.guice.reactor.ReactorModule;
import com.midokura.midolman.guice.zookeeper.MockZookeeperConnectionModule;
import com.midokura.midolman.host.services.HostService;
import com.midokura.midolman.services.HostIdProviderService;
import com.midokura.midolman.services.MidolmanService;
import com.midokura.midolman.state.PortDirectory;
import com.midokura.midolman.vrn.AbstractVrnControllerTest;
import com.midokura.midonet.cluster.services.MidostoreSetupService;
import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.Metric;
import com.yammer.metrics.core.MetricName;
import com.yammer.metrics.core.MetricsRegistry;
import org.apache.commons.configuration.HierarchicalConfiguration;
import org.junit.Before;
import org.openflow.protocol.OFPhysicalPort;
import org.openflow.protocol.OFPortStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static java.lang.String.format;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class MonitoringTest extends AbstractMidolmanTestCase {

    private static final Logger log = LoggerFactory
            .getLogger(MonitoringTest.class);

    MonitoringAgent monitoringAgent;
    MetricsRegistry registry;


    @Test
    public void testVMMonitoring() {
        HierarchicalConfiguration config = fillConfig(new HierarchicalConfiguration());
        Injector injector = Guice.createInjector(getModulesAsJavaIterable(config));

        registry = Metrics.defaultRegistry();

        injector.getInstance(MidostoreSetupService.class).startAndWait();
        injector.getInstance(MidolmanService.class).startAndWait();
        injector.getInstance(MonitoringAgent.class).startMonitoring();
        Map<MetricName, Metric> allMetrics = registry.allMetrics();
        log.debug("Le ble");
        for (MetricName metricName: allMetrics.keySet()) {
            log.debug("METRIC --> {}", metricName);
        }
    }


   /* private List<Module> getModules() {
        List<Module> modulesList = new ArrayList<Module>() {{
                this.add(new MockConfigProviderModule(new HierarchicalConfiguration()));
                this.add(new MockDatapathModule());
                this.add(new MockZookeeperConnectionModule());
                this.add(new AbstractModule() {
                    @Override
                    protected void configure() {
                        bind(HostIdProviderService.class).toInstance(new HostIdProviderService() {
                            @Override
                            public UUID getHostId() {
                                return UUID.randomUUID();
                            }
                        });
                    }
                });
                 this.add(new ReactorModule());
                 this.add(new MockClusterClientModule());
                 scala.collection.mutable.Map<String,TestKit> probesByName = new scala.collection.mutable.HashMap<String,TestKit>();
                 scala.collection.mutable.Map<String,TestActorRef<Actor>> actorsByName = new scala.collection.mutable.HashMap<String, TestActorRef<Actor>>();
                 this.add(new TestableMidolmanActorsModule(probesByName, actorsByName));
                 this.add(new MidolmanModule());
            }
        };


        return modulesList;
    }

        */
}

