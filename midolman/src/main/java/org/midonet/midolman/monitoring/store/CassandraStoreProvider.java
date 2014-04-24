/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.midolman.monitoring.store;

import com.google.inject.Inject;
import com.google.inject.Provider;

import org.midonet.cassandra.CassandraClient;
import org.midonet.config.ConfigProvider;
import org.midonet.midolman.guice.CacheModule;
import org.midonet.midolman.monitoring.config.MonitoringConfiguration;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkLock;
import org.midonet.midolman.state.ZkManager;
import org.midonet.util.eventloop.Reactor;

/**
 * Providers CassandraStore
 */
public class CassandraStoreProvider implements Provider<CassandraStore> {

    @Inject
    ConfigProvider configProvider;

    @Inject
    ZkManager zk;

    @Inject
    PathBuilder paths;

    @Inject @CacheModule.CACHE_REACTOR
    Reactor reactor;

    @Override
    public CassandraStore get() {
        MonitoringConfiguration config = configProvider.getConfig(MonitoringConfiguration.class);
        try {
            return new CassandraStore(new CassandraClient(
                config.getCassandraServers(),
                config.getCassandraMaxActiveConnections(),
                config.getCassandraCluster(),
                config.getMonitoringCassandraKeyspace(),
                config.getMonitoringCassandraColumnFamily(),
                config.getCassandraReplicationFactor(),
                config.getMonitoringCassandraExpirationTimeout(),
                config.getCassandraThriftSocketTimeout(),
                config.getCassandraHostTimeoutTracker(),
                config.getCassandraHostTimeoutCounter(),
                config.getCassandraHostTimeoutWindow(),
                reactor,
                new ZkLock(zk, paths, "cassandra-monitoring")));
        } catch (StateAccessException e) {
            throw new RuntimeException("Exception trying to create CassandraStore", e);
        }
    }
}
