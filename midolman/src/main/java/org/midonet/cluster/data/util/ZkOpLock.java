/*
 * Copyright (c) 2014 Midokura SARL
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

import org.midonet.cluster.ZookeeperLockFactory;

import org.apache.commons.lang.time.StopWatch;
import org.apache.curator.framework.recipes.locks.InterProcessSemaphoreMutex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class ZkOpLock {
    private InterProcessSemaphoreMutex lock;
    private StopWatch timeHeld;
    private int opNumber;

    private static final Logger LOGGER =
        LoggerFactory.getLogger(ZkOpLock.class);

    public static final int LOCK_WAIT_SEC = 5;

    String name;

    public ZkOpLock(ZookeeperLockFactory lockFactory, int lockOpNumber,
                    String lockName) {
        lock = lockFactory.createShared(lockName);
        opNumber = lockOpNumber;
        timeHeld = new StopWatch();
        name = lockName;
    }

    public void acquire() {
        StopWatch timeToAcquire = new StopWatch();
        timeToAcquire.start();
        try {
            LOGGER.debug("Attempting to acquire lock for operation " +
                         opNumber);
            if (!lock.acquire(LOCK_WAIT_SEC, TimeUnit.SECONDS)) {
                throw new RuntimeException("Could not acquire lock in time");
            }
            timeToAcquire.stop();
            timeHeld.start();

            LOGGER.debug(name + "ZK lock acquired for operation " +
                         opNumber + ". Operation took " +
                         timeToAcquire.getTime() +
                         " milliseconds.");
        } catch (Exception ex){
            throw new RuntimeException(ex);
        }
    }

    public void release() {
        try {
            lock.release();
            timeHeld.stop();
            LOGGER.debug(name + " ZK lock operation for " + opNumber +
                         " held the lock for " + timeHeld.getTime() +
                         " milliseconds.");
        } catch (Exception ex){
            throw new RuntimeException(ex);
        }
    }

}
