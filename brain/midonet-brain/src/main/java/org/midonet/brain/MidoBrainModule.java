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

import java.util.UUID;

import com.google.inject.Inject;
import com.google.inject.PrivateModule;
import com.google.inject.Provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.brain.configuration.EmbeddedClusterNodeConfig;
import org.midonet.config.ConfigProvider;
import org.midonet.config.HostIdGenerator;
import org.midonet.config.HostIdGenerator.PropertiesFileNotWritableException;
import org.midonet.midolman.config.MidolmanConfig;

public class MidoBrainModule extends PrivateModule {

    private static final Logger
        log = LoggerFactory.getLogger(MidoBrainModule.class);

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

        bind(ClusterNode.Context.class).toProvider(
            new Provider<ClusterNode.Context>() {
                @Inject
                ConfigProvider configProvider;
                @Override public ClusterNode.Context get() {
                    EmbeddedClusterNodeConfig cfg = configProvider.getConfig(
                        EmbeddedClusterNodeConfig.class);
                    try {
                        UUID clusterNodeId = HostIdGenerator.getHostId();
                        boolean embeddingEnabled = cfg.isEmbeddingEnabled();
                        return new ClusterNode.Context(
                            clusterNodeId,
                            embeddingEnabled
                        );
                    } catch (PropertiesFileNotWritableException e) {
                        log.error("Could not register cluster node host id", e);
                        throw new RuntimeException(e);
                    }
                }
            }
        );
        expose(ClusterNode.Context.class);
    }

    public static class MidolmanConfigProvider implements Provider<MidolmanConfig> {
        @Inject
        ConfigProvider configProvider;

        @Override
        public MidolmanConfig get() {
            return configProvider.getConfig(MidolmanConfig.class);
        }
    }

}
