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

package org.midonet.cluster;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Named;

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Watcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.midolman.guice.zookeeper.ZkConnectionProvider;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.DirectoryCallback;
import org.midonet.midolman.state.ZkConnectionAwareWatcher;
import org.midonet.util.eventloop.Reactor;

abstract class ClusterManager<T> {
    private static final Logger log = LoggerFactory
            .getLogger(ClusterManager.class);

    /**
     * We inject it because we want to use the same {@link Reactor} as
     * {@link org.midonet.midolman.state.ZkDirectory}
     */
    @Inject
    @Named(ZkConnectionProvider.DIRECTORY_REACTOR_TAG)
    Reactor reactorLoop;

    @Inject
    ZkConnectionAwareWatcher connectionWatcher;

    private Map<UUID, T> builderMap = new HashMap<>();

    public void registerNewBuilder(final UUID id, final T builder) {
        reactorLoop.submit(new Runnable() {
            @Override
            public void run() {
                if (builderMap.containsKey(id)) {
                    // TODO(pino): inform the builder that it's not registered.
                    // builder.error();
                    log.error("Builder for device "
                            + id.toString() + " already registered");
                } else {
                    log.debug("Registering new builder for device {}", id);
                    builderMap.put(id, builder);
                    getConfig(id);
                    onNewBuilder(id);
                }
            }
        });
    }

    protected void onNewBuilder(final UUID id) {
        // default: do nothing
    }

    protected T getBuilder(UUID id){
        return builderMap.get(id);
    }

    abstract protected void getConfig(UUID id);

    /* The following classes provide base implementations for callbacks/watchers
     * in directory operations in ClusterManager subclasses. There are two
     * flavours:
     *
     *     - Callback only
     *     - Callback + TypedWatcher
     *
     * Their main purpose is centralizing the logic for error handling, as
     * follows:
     *
     *     - Disconnections in watchers are ignored, as ZK guarantees that the
     *       watchers will be called once the connection comes back.
     *     - Timeouts and errors are handed over to the handleError()
     *       methods above. */
    protected abstract class CallableWithRetry {
        protected abstract String describe();

        protected abstract Runnable makeRetry();
    }

    protected abstract class CallbackWithWatcher<U> extends CallableWithRetry
            implements DirectoryCallback<U>, Directory.TypedWatcher {

        // DirectoryCallback overrides
        @Override
        public abstract void onSuccess(U data);

        @Override
        public void onError(KeeperException e) {
            log.error("Error getting cluster data for: " + describe());
            connectionWatcher.handleError(describe(), makeRetry(), e);
        }

        @Override
        public void onTimeout() {
            log.error("Timeout getting cluster data for: " + describe());
            connectionWatcher.handleTimeout(makeRetry());
        }

        // Watcher overrides
        @Override
        public void run() {
            log.error("Should NEVER be called");
        }

        @Override
        public void pathDeleted(String path) {
            log.warn("Path deleted at {}, event not handled", path);
        }

        @Override
        public void pathCreated(String path) {
            log.warn("Path created at {}, event not handled", path);
        }

        @Override
        public void pathChildrenUpdated(String path) {
            log.warn("Children updated at {}, event not handled", path);
        }

        @Override
        public void pathDataChanged(String path) {
            log.warn("Data changed at {}, event not handled", path);
        }

        @Override
        public void connectionStateChanged(Watcher.Event.KeeperState state) {
            // Do nothing. The watcher will be called when the connection
            // comes back
        }
    }

    protected abstract class RetryCallback<T> extends CallableWithRetry
            implements DirectoryCallback<T> {

        @Override
        public abstract void onSuccess(T data);

        @Override
        public void onError(KeeperException e) {
            log.error("Error getting cluster data for: " + describe());
            connectionWatcher.handleError(describe(), makeRetry(), e);
        }

        @Override
        public void onTimeout() {
            log.error("Timeout getting cluster data for: " + describe());
            connectionWatcher.handleTimeout(makeRetry());
        }
    }
}
