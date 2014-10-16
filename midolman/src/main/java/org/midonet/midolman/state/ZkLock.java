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

package org.midonet.midolman.state;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;

import org.midonet.midolman.Setup;
import org.midonet.util.functors.Callback;

/**
 * A non-reentrant distributed lock based on Zookeeper.
 */
public class ZkLock {
    private final static Logger log =
            LoggerFactory.getLogger(ZkLock.class);

    private final ZkManager zk;
    private final String lockPath;
    private String owner;

    public ZkLock(ZkManager zk, PathBuilder paths, String name)
            throws StateAccessException, KeeperException, InterruptedException {
        this.zk = zk;
        // TODO: remove this when we can ensure ZK has the top level
        //       paths created before the first usage of ZkLock.
        Setup.ensureBasePathExists(zk.getDirectory(), paths.getBasePath());
        zk.addPersistent_safe(paths.getLocksPath(), null);
        lockPath = paths.getLockPath(name);
        zk.addPersistent_safe(lockPath, null);
    }

    public void lock(final Callback<Void, StateAccessException> callback) {
        try {
            final String path = zk.addEphemeralSequential(lockPath, null);
            int seq = ZkUtil.getSequenceNumberFromPath(path);
            Watcher watcher = new Watcher() {
                @Override
                public void process(WatchedEvent event) {
                    acquire(path, callback);
                }
            };
            String prev;
            do {
                Set<String> waiters = zk.getChildren(lockPath, null);
                prev = ZkUtil.getNextLowerSequenceNumberPath(waiters, seq);
                if (prev == null) {
                    acquire(path, callback);
                    return;
                }
            } while (!zk.exists(lockPath + "/" + prev, watcher));
        } catch (StateAccessException e) {
            log.error("Got exception when trying to acquire {}", lockPath, e);
            callback.onError(e);
        }
    }

    public boolean lock(long timeout) throws StateAccessException {
        final CountDownLatch latch = new CountDownLatch(1);
        final StateAccessException[] ex = new StateAccessException[1];
        final AtomicInteger waiting = new AtomicInteger(1);

        lock(new Callback<Void, StateAccessException>() {
            @Override
            public void onSuccess(Void data) {
                if (waiting.compareAndSet(1, 0))
                    latch.countDown();
                else
                    unlock();
            }

            @Override
            public void onTimeout() { }

            @Override
            public void onError(StateAccessException e) {
                ex[0] = e;
                latch.countDown();
            }
        });

        if (!await(timeout, latch))
            return !waiting.compareAndSet(1, 0);

        if (ex[0] != null)
            throw ex[0];

        return true;
    }

    private void acquire(String path, Callback<Void, StateAccessException> callback) {
        log.debug("Acquired lock {}", lockPath);
        owner = path;
        callback.onSuccess(null);
    }

    private boolean await(long timeout, CountDownLatch latch) {
        boolean interrupted = false;
        do {
            try {
                return latch.await(timeout, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                interrupted = true;
            } finally {
                if (interrupted)
                    Thread.currentThread().interrupt();
            }
        } while (true);
    }

    public void unlock() {
        String path = owner;
        owner = null;
        try {
            zk.delete(path);
            log.debug("Unlocked lock {}", lockPath);
        } catch (StateAccessException e) {
            log.error("Got an unexpected exception when trying to unlock {}",
                    lockPath, e);
        }
    }
}
