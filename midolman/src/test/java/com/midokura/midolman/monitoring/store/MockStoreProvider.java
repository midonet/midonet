/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.monitoring.store;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.midokura.cassandra.CassandraClient;

/**
 * Provides a Mock (memory based) Store.
 */
public class MockStoreProvider implements Provider<MockStore> {


    @Inject
    public MockStoreProvider() {
    }

    @Override
    public MockStore get() {
        return new MockStore();
    }
}
