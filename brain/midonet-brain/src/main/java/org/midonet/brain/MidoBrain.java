/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.brain;

import com.google.inject.Guice;
import com.google.inject.Injector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.brain.guice.BrainModule;
import org.midonet.brain.services.vxgw.VxLanGatewayService;
import org.midonet.cluster.services.StorageService;
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
            new ClusterClientModule(),
            new SerializationModule()
        );

        injector.getInstance(StorageService.class)
            .startAsync()
            .awaitRunning();
        injector.getInstance(VxLanGatewayService.class)
            .startAsync()
            .awaitRunning();

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
