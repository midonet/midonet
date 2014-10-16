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
package org.midonet.midolman.guice.cluster;

import java.util.concurrent.TimeUnit;

import org.apache.curator.framework.recipes.locks.InterProcessSemaphoreMutex;

import org.midonet.cluster.ZookeeperLockFactory;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
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
            doReturn(true).when(lock).acquire(anyLong(), any(TimeUnit.class));
            doNothing().when(lock).release();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        bind(ZookeeperLockFactory.class).toInstance(lockFactory);
    }
}
