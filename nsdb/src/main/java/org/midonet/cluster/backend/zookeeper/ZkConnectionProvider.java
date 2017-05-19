/*
 * Copyright 2015 Midokura SARL
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
package org.midonet.cluster.backend.zookeeper;

import com.google.inject.BindingAnnotation;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.name.Named;
import org.slf4j.Logger;

import org.midonet.cluster.storage.MidonetBackendConfig;
import org.midonet.util.eventloop.Reactor;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import static org.slf4j.LoggerFactory.getLogger;

/**
 * A ZKConnection provider which is instantiating a ZKConnection while
 * optionally using a reconnect watcher
 */
public class ZkConnectionProvider implements Provider<ZkConnection> {

    private final static Logger log = getLogger(ZkConnectionProvider.class);

    // WARN: should this string change, also replace it in HostService
    public static final String DIRECTORY_REACTOR_TAG = "directoryReactor";

    @BindingAnnotation
    @Retention(RetentionPolicy.RUNTIME)
    public @interface BGP_ZK_INFRA { }

    @Inject
    MidonetBackendConfig config;

    @Inject(optional = true)
    @Named(DIRECTORY_REACTOR_TAG)
    Reactor reactorLoop;

    @Inject(optional = true)
    ZkConnectionAwareWatcher watcher;

    @Override
    public ZkConnection get() {
        if (watcher == null) {
            log.info("ZK connection provider will not use a conn. watcher");
        }
        return get(watcher, reactorLoop);
    }

    public ZkConnection get(ZkConnectionAwareWatcher watcher,
                            Reactor reactor) {
        try {
            ZkConnection zkConnection = new ZkConnection(
                    config.hosts(), config.sessionTimeout(), watcher, reactor);
            if (watcher != null) {
                watcher.setZkConnection(zkConnection);
            }
            zkConnection.open();
            return zkConnection;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
