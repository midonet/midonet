/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test.utils;

import com.google.common.base.Service;
import com.google.inject.Guice;
import com.google.inject.Injector;

import com.midokura.midolman.guice.FlowStateCacheModule;
import com.midokura.midolman.guice.InterfaceScannerModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import akka.actor.ActorSystem;

import com.midokura.midolman.guice.MidolmanActorsModule;
import com.midokura.midolman.guice.MidolmanModule;
import com.midokura.midolman.guice.MonitoringStoreModule;
import com.midokura.midolman.guice.cluster.ClusterClientModule;
import com.midokura.midolman.guice.config.ConfigProviderModule;
import com.midokura.midolman.guice.datapath.DatapathModule;
import com.midokura.midolman.guice.reactor.ReactorModule;
import com.midokura.midolman.guice.zookeeper.ZookeeperConnectionModule;
import com.midokura.midolman.host.guice.HostModule;
import com.midokura.midolman.monitoring.MonitoringAgent;
import com.midokura.midolman.services.MidolmanActorsService;
import com.midokura.midolman.services.MidolmanService;
import com.midokura.midonet.cluster.DataClient;
import com.midokura.midonet.cluster.services.MidostoreSetupService;
import com.midokura.remote.RemoteHost;

public class EmbeddedMidolman {


    private final static Logger log = LoggerFactory
    .getLogger(EmbeddedMidolman.class);

    private Injector injector;
    private MonitoringAgent monitoringAgent;

    public void startMidolman(String configFilePath) throws Exception {


        RemoteHost.getSpecification();

        log.info("Getting configuration file from: {}", configFilePath);
        injector = Guice.createInjector(
                new ZookeeperConnectionModule(),
                new ReactorModule(),
                new HostModule(),
                new ConfigProviderModule(configFilePath),
                new DatapathModule(),
                new MonitoringStoreModule(),
                new ClusterClientModule(),
                new FlowStateCacheModule(),
                new MidolmanActorsModule(),
                new MidolmanModule(),
                new InterfaceScannerModule()
        );

        // start the services
        injector.getInstance(MidostoreSetupService.class).startAndWait();
        injector.getInstance(MidolmanService.class).startAndWait();

        // fire the initialize message to an actor
        injector.getInstance(MidolmanActorsService.class).initProcessing();
        monitoringAgent = injector.getInstance(MonitoringAgent.class);
        monitoringAgent.startMonitoringIfEnabled();

        log.info("{} was initialized", MidolmanActorsService.class);

    }

    public void stopMidolman() {
        if ( injector == null )
            return;

        MidolmanService instance =
                injector.getInstance(MidolmanService.class);

        if (monitoringAgent != null)
          monitoringAgent.stop();

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

    public ActorSystem getActorSystem() {
        return injector.getInstance(MidolmanActorsService.class).system();
    }

    public DataClient getDataClient() {
        return injector.getInstance(DataClient.class);
    }
}
