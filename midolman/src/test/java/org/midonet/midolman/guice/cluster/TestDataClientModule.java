/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.midolman.guice.cluster;

import org.apache.curator.framework.recipes.locks.InterProcessSemaphoreMutex;

import org.midonet.cluster.ZookeeperLockFactory;

import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Test Guice module class used for tests.
 */
public class TestDataClientModule extends DataClientModule {

    /**
     * Override to mock the lock factory class
     */
    @Override
    protected void bindZookeeperLockFactory() {
        ZookeeperLockFactory lockFactory = mock(ZookeeperLockFactory.class);
        InterProcessSemaphoreMutex lock = mock(
            InterProcessSemaphoreMutex.class);
        when(lockFactory.createShared(anyString())).thenReturn(lock);
        try {
            doNothing().when(lock).acquire();
            doNothing().when(lock).release();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        bind(ZookeeperLockFactory.class).toInstance(lockFactory);
    }
}
