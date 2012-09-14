/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.monitoring.store;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.midokura.cassandra.CassandraClient;

/**
 * Providers CassandraStore
 */
public class MockCassandraStoreProvider implements Provider<MockCassandraStore> {


    @Inject
    public MockCassandraStoreProvider(CassandraClient client) {
    }

    @Override
    public MockCassandraStore get() {
        return new MockCassandraStore(null);
    }
}
