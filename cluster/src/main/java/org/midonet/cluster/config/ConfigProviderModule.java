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
package org.midonet.cluster.config;

import java.util.Map;
import java.util.TreeMap;

import com.google.inject.PrivateModule;
import org.apache.commons.configuration.HierarchicalConfiguration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.config.ConfigProvider;

/**
 * This Guice module will expose a {@link ConfigProvider} instance that everyone
 * can use as the source of their configuration.
 */
@Deprecated
public class ConfigProviderModule extends PrivateModule {
    static final Logger log = LoggerFactory.getLogger(
            ConfigProviderModule.class);

    private final ConfigProvider provider;

    public ConfigProviderModule(HierarchicalConfiguration configuration) {
        this.provider = ConfigProvider.providerForIniConfig(configuration);
    }

    @Override
    protected void configure() {
        bind(ConfigProvider.class).toInstance(provider);
        expose(ConfigProvider.class);

        ZookeeperConfig zkCfg = provider.getConfig(ZookeeperConfig.class);
        bind(ZookeeperConfig.class).toInstance(zkCfg);
        expose(ZookeeperConfig.class);

        log.info("config start -----------------------");
        Map<String, Object> allConf = new TreeMap(provider.getAll());
        for (Map.Entry<String, Object> confValue : allConf.entrySet()) {
            log.info(" {} = {}", confValue.getKey(), confValue.getValue());
        }
        log.info("config end --------------------------");
    }
}
