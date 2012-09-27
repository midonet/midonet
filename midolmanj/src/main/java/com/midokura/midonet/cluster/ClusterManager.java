/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midonet.cluster;

import java.util.UUID;

import com.midokura.midolman.guice.zookeeper.ZKConnectionProvider;
import com.midokura.util.collections.TypedHashMap;
import com.midokura.util.collections.TypedMap;
import com.midokura.util.eventloop.Reactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;

abstract class ClusterManager<T> {
    private static final Logger log = LoggerFactory
            .getLogger(ClusterManager.class);

    /**
     * We inject it because we want to use the same {@link Reactor} as {@link com.midokura.midolman.state.ZkDirectory}
     */
    @Inject
    @Named(ZKConnectionProvider.DIRECTORY_REACTOR_TAG)
    Reactor reactorLoop;

    private TypedMap<UUID, T> builderMap = new TypedHashMap<UUID, T>();

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
                    builderMap.put(id, builder);
                    getConfig(id);
                }
            }
        });
    }
    
    protected T getBuilder(UUID id){
        return builderMap.get(id);
    }
    
    abstract protected void getConfig(UUID id);
}
