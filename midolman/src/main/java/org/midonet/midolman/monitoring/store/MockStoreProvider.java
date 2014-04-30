/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.midolman.monitoring.store;

import com.google.inject.Inject;
import com.google.inject.Provider;
import org.midonet.cassandra.CassandraClient;

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
