/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.brain;

import com.google.inject.Inject;
import com.google.inject.PrivateModule;
import com.google.inject.Provider;
import org.midonet.brain.configuration.MidoBrainConfig;
import org.midonet.config.ConfigProvider;
import org.midonet.midolman.config.MidolmanConfig;

public class MidoBrainModule extends PrivateModule {

    @Override
    protected void configure() {

        binder().requireExplicitBindings();

        requireBinding(ConfigProvider.class);

        // TODO: needed to start the storage service, but it should be decoupled
        // from Midolman so it can run elsewhere
        bind(MidolmanConfig.class)
            .toProvider(MidoBrainModule.MidolmanConfigProvider.class)
            .asEagerSingleton();
        expose(MidolmanConfig.class);

        bind(MidoBrainConfig.class)
            .toProvider(MidoBrainModule.MidoBrainConfigProvider.class)
            .asEagerSingleton();
        expose(MidoBrainConfig.class);

    }

    public static class MidolmanConfigProvider
                  implements Provider<MidolmanConfig> {
        @Inject
        ConfigProvider configProvider;

        @Override
        public MidolmanConfig get() {
            return configProvider.getConfig(MidolmanConfig.class);
        }
    }

    public static class MidoBrainConfigProvider
        implements Provider<MidoBrainConfig> {
        @Inject
        ConfigProvider configProvider;

        @Override
        public MidoBrainConfig get() {
            return configProvider.getConfig(MidoBrainConfig.class);
        }
    }
}
