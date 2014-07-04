/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.brain;

import com.google.inject.Guice;
import com.google.inject.Injector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.brain.guice.BrainModule;
import org.midonet.brain.services.vxgw.VxLanGatewayService;
import org.midonet.cluster.services.MidostoreSetupService;
import org.midonet.midolman.guice.CacheModule;
import org.midonet.midolman.guice.cluster.ClusterClientModule;
import org.midonet.midolman.guice.config.ConfigProviderModule;
import org.midonet.midolman.guice.serialization.SerializationModule;
import org.midonet.midolman.guice.zookeeper.ZookeeperConnectionModule;
import org.midonet.midolman.version.guice.VersionModule;

/**
 * This is the main application that starts a MidoBrain process running all
 * services configured.
 */
public class MidoBrain {

    private static final Logger log = LoggerFactory.getLogger(MidoBrain.class);

    private Injector injector;

    private MidoBrain() {}

    private void doServicesCleanup() {}

    private void run(String configFile) {
        log.info("Adding shutdownHook");
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                doServicesCleanup();
            }
        });

        injector = Guice.createInjector(
            new ConfigProviderModule(configFile),
            new MidoBrainModule(), // this is the standalone app
            new BrainModule(),     // this is the service
            new ZookeeperConnectionModule(),
            new VersionModule(),
            new CacheModule(),
            new ClusterClientModule(),
            new SerializationModule()
        );

        injector.getInstance(MidostoreSetupService.class).startAndWait();
        injector.getInstance(VxLanGatewayService.class).startAndWait();

        log.info("Midonet Brain initialized");

    }

    private static class MidoBrainHolder {
        private static final MidoBrain instance = new MidoBrain();
    }
    public static MidoBrain getInstance() { return MidoBrainHolder.instance; }
    public static Injector getInjector() { return getInstance().injector; }
    public static void main(String[] args) {
        if (args.length < 1) {
            log.error("No configuration file provided");
            System.exit(-1);
        }
        try {
            MidoBrain brain = getInstance();
            brain.run(args[0]); // TODO: use a default location
        } catch (Exception e) {
            log.error("Failed starting", e);
            System.exit(-1);
        }

    }
}
