/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test.utils;

import com.google.common.base.Service;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.midokura.midolman.guice.MidolmanActorsModule;
import com.midokura.midolman.guice.MidolmanModule;
import com.midokura.midolman.guice.cluster.ClusterClientModule;
import com.midokura.midolman.guice.config.ConfigProviderModule;
import com.midokura.midolman.guice.datapath.DatapathModule;
import com.midokura.midolman.guice.reactor.ReactorModule;
import com.midokura.midolman.guice.zookeeper.ZookeeperConnectionModule;
import com.midokura.midolman.host.guice.HostModule;
import com.midokura.midolman.monitoring.MonitoringAgent;
import com.midokura.midolman.services.MidolmanActorsService;
import com.midokura.midolman.services.MidolmanService;
import com.midokura.midonet.cluster.services.MidostoreSetupService;
import com.midokura.util.process.DrainTargets;
import com.midokura.util.process.ProcessHelper;
import org.apache.commons.lang.StringUtils;
import org.cassandraunit.utils.EmbeddedCassandraServerHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static java.lang.String.format;

public class EmbeddedMidolmanLauncher {

    private final static Logger log = LoggerFactory
            .getLogger(EmbeddedMidolmanLauncher.class);

    public static final String MIDONET_PROJECT_LOCATION =
            "midonet.functional.tests.current_midonet_project";

    private Injector injector;

    public void startMidolman(String configFilePath) throws Exception {
        log.info("Getting configuration file from: {}", configFilePath);
        injector = Guice.createInjector(
                new ZookeeperConnectionModule(),
                new ReactorModule(),
                new HostModule(),
                new ConfigProviderModule(configFilePath),
                new DatapathModule(),
                new ClusterClientModule(),
                new MidolmanActorsModule(),
                new MidolmanModule()
        );

        // start the services
        injector.getInstance(MidostoreSetupService.class).startAndWait();
        injector.getInstance(MidolmanService.class).startAndWait();

        // fire the initialize message to an actor
        injector.getInstance(MidolmanActorsService.class).initProcessing();
        injector.getInstance(MonitoringAgent.class).startMonitoringIfEnabled();

        log.info("{} was initialized", MidolmanActorsService.class);

    }

    public void stopMidolman() {
        if ( injector == null )
            return;

        MidolmanService instance =
                injector.getInstance(MidolmanService.class);

        if (instance.state() == Service.State.TERMINATED)
            return;

        try {
            instance.stopAndWait();
        } catch (Exception e) {
            log.error("Exception ", e);
        } finally {
            log.info("Exiting. BYE (signal)!");
        }
    }


}
