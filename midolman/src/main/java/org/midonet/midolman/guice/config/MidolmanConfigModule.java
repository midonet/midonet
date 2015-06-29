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
package org.midonet.midolman.guice.config;

import com.google.common.annotations.VisibleForTesting;
import com.google.inject.PrivateModule;
import com.typesafe.config.Config;

import com.typesafe.config.ConfigFactory;

import org.midonet.cluster.storage.MidonetBackendConfig;
import org.midonet.conf.MidoNodeConfigurator;
import org.midonet.conf.HostIdGenerator;
import org.midonet.midolman.config.MidolmanConfig;

public class MidolmanConfigModule extends PrivateModule {

    private final MidolmanConfig config;

    public MidolmanConfigModule(MidolmanConfig config) {
        this.config = config;
    }

    @VisibleForTesting
    public MidolmanConfigModule(Config configuration) {
        this.config = createConfig(configuration);
    }

    @Override
    protected void configure() {
        bind(MidolmanConfig.class).toInstance(config);
        bind(MidonetBackendConfig.class).toInstance(config.zookeeper());
        expose(MidolmanConfig.class);
        expose(MidonetBackendConfig.class);
    }

    public static MidolmanConfig createConfig(MidoNodeConfigurator configurator) {
        try {
            return new MidolmanConfig(
                configurator.runtimeConfig(HostIdGenerator.getHostId()),
                configurator.mergedSchemas());
        } catch (HostIdGenerator.PropertiesFileNotWritableException e) {
            throw new RuntimeException(e);
        }
    }

    @VisibleForTesting
    public static MidolmanConfig createConfig(Config configuration) {
        return new MidolmanConfig(configuration, ConfigFactory.empty());
    }
}
