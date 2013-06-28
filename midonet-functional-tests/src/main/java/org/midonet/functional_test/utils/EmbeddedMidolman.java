/*
 * Copyright 2011 Midokura Europe SARL
 */

package org.midonet.functional_test.utils;

import akka.actor.ActorRef;
import com.google.common.base.Service;
import com.google.inject.Guice;
import com.google.inject.Injector;

import org.midonet.midolman.guice.serialization.SerializationModule;
import org.midonet.midolman.version.guice.VersionModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import akka.actor.ActorSystem;

import org.midonet.midolman.guice.MidolmanActorsModule;
import org.midonet.midolman.guice.CacheModule;
import org.midonet.midolman.guice.InterfaceScannerModule;
import org.midonet.midolman.guice.MidolmanModule;
import org.midonet.midolman.guice.MonitoringStoreModule;
import org.midonet.midolman.guice.cluster.ClusterClientModule;
import org.midonet.midolman.guice.config.ConfigProviderModule;
import org.midonet.midolman.guice.datapath.DatapathModule;
import org.midonet.midolman.guice.reactor.ReactorModule;
import org.midonet.midolman.guice.zookeeper.ZookeeperConnectionModule;
import org.midonet.midolman.host.guice.HostModule;
import org.midonet.midolman.monitoring.MonitoringAgent;
import org.midonet.midolman.services.MidolmanActorsService;
import org.midonet.midolman.services.MidolmanService;
import org.midonet.midolman.FlowController;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.services.MidostoreSetupService;
import org.midonet.remote.RemoteHost;

public class EmbeddedMidolman {


    private final static Logger log = LoggerFactory
    .getLogger(EmbeddedMidolman.class);

    private Injector injector;
    private MonitoringAgent monitoringAgent;

    public void startMidolman(String configFilePath) throws Exception {


        RemoteHost.getSpecification();

        log.info("Getting configuration file from: {}", configFilePath);
        injector = Guice.createInjector(
                new VersionModule(),
                new SerializationModule(),
                new ZookeeperConnectionModule(),
                new ReactorModule(),
                new HostModule(),
                new ConfigProviderModule(configFilePath),
                new DatapathModule(),
                new MonitoringStoreModule(),
                new ClusterClientModule(),
                new CacheModule(),
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

    public ActorRef getFlowController() {
        return FlowController.getRef(this.getActorSystem());
    }
}
