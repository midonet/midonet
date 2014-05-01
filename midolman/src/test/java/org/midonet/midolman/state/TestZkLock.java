/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.midolman.state;

import org.junit.Before;
import org.junit.Test;
import org.midonet.util.functors.Callback;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

public class TestZkLock {
    ZkLock lock;

    @Before
    public void setup() throws Exception {
        ZkManager zk = new ZkManager(new MockDirectory(), "");
        PathBuilder paths = new PathBuilder("");
        zk.addPersistent(paths.getLocksPath(), null);
        lock = new ZkLock(zk, paths, "fluffy");
    }

    @Test
    public void testAcquiresLockWhenFree() throws StateAccessException {
        assertThat(lock.lock(0), is(true));
        lock.unlock();
    }

    @Test
    public void testAcquiresLockWhenBusy() throws StateAccessException {
        int LOCK_TIMES = 100;
        final int[] gotLock = new int[1];

        assertThat(lock.lock(0), is(true));
        for (int i = 0; i < LOCK_TIMES; ++i) {
            lock.lock(new Callback<Void, StateAccessException>() {
                @Override
                public void onSuccess(Void data) {
                    gotLock[0] += 1;
                    lock.unlock();
                }

                @Override
                public void onTimeout() { }

                @Override
                public void onError(StateAccessException e) { }
            });
        }

        lock.unlock();
        assertThat(gotLock[0], is(LOCK_TIMES));
    }

    @Test
    public void testTimeout() throws StateAccessException {
        assertThat(lock.lock(0), is(true));
        assertThat(lock.lock(0), is(false));
        lock.unlock();
    }
}
