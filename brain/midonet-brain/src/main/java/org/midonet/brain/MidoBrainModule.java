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
