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
