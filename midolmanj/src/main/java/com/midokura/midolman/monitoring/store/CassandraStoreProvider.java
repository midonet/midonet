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
public class CassandraStoreProvider implements Provider<CassandraStore> {

    private final CassandraClient client;

    @Inject
    public CassandraStoreProvider(CassandraClient client) {
        this.client = client;
    }

    @Override
    public CassandraStore get() {
        return new CassandraStore(client);
    }
}
