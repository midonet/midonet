/*
 * Copyright (c) 2016 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.midonet.cluster.data.util;

import java.util.concurrent.TimeUnit;

import org.apache.commons.lang.time.StopWatch;
import org.apache.curator.framework.recipes.locks.InterProcessSemaphoreMutex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.cluster.ZookeeperLockFactory;

public class ZkOpLock {
    private static final Logger LOGGER =
        LoggerFactory.getLogger(ZkOpLock.class);

    private static final int LOCK_TIMEOUT = 5;
    private static final TimeUnit LOCK_TIMEOUT_UNIT = TimeUnit.SECONDS;

    private final InterProcessSemaphoreMutex lock;
    private final StopWatch timeHeld;
    private final int opNumber;
    private final String name;

    public ZkOpLock(ZookeeperLockFactory lockFactory, int lockOpNumber,
                    String lockName) {
        lock = lockFactory.createShared(lockName);
        opNumber = lockOpNumber;
        timeHeld = new StopWatch();
        name = lockName;
    }

    public void acquire() {
        acquire(LOCK_TIMEOUT, LOCK_TIMEOUT_UNIT);
    }

    public void acquire(long timeout, TimeUnit timeUnit) {
        StopWatch timeToAcquire = new StopWatch();
        timeToAcquire.start();
        try {
            LOGGER.debug("Attempting to acquire lock for operation {}", opNumber);
            if (!lock.acquire(timeout, timeUnit)) {
                throw new RuntimeException("Could not acquire lock in time");
            }
            timeToAcquire.stop();
            timeHeld.start();

            LOGGER.debug("ZK lock {} acquired for operation {} operation took "
                         + "{} milliseconds", name, opNumber,
                         timeToAcquire.getTime());
        } catch (Exception ex){
            throw new RuntimeException(ex);
        }
    }

    public void release() {
        try {
            lock.release();
            timeHeld.stop();
            LOGGER.debug("ZK lock {} operation for {} held the lock for {} "
                         + "milliseconds",name, opNumber, timeHeld.getTime());
        } catch (Exception ex){
            throw new RuntimeException(ex);
        }
    }

}
