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

import com.google.inject.PrivateModule;
import com.typesafe.config.Config;

import org.midonet.conf.MidoNodeConfigurator;
import org.midonet.conf.HostIdGenerator;
import org.midonet.midolman.config.MidolmanConfig;

public class MidolmanConfigModule extends PrivateModule {

    private final MidolmanConfig config;

    public MidolmanConfigModule(MidoNodeConfigurator c) {
        try {
            this.config = new MidolmanConfig(c.runtimeConfig(HostIdGenerator.getHostId()));
        } catch (HostIdGenerator.PropertiesFileNotWritableException e) {
            throw new RuntimeException(e);
        }
    }

    public MidolmanConfigModule(Config configuration) {
        this.config = new MidolmanConfig(configuration);
    }

    @Override
    protected void configure() {
        bind(MidolmanConfig.class).toInstance(config);
        expose(MidolmanConfig.class);
    }
}
