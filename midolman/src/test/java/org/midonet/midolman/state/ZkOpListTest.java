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

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import org.junit.Assert;

import rx.subjects.AsyncSubject;
import org.midonet.midolman.cluster.ZookeeperTest;

public class ZkOpListTest extends ZookeeperTest {

    // Zookeeper configurations
    private ZkManager zk;
    private ZkOpList testObj;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        zk = injector.getInstance(ZkManager.class);
        testObj = new ZkOpList(zk);
    }

    @After // overriding so we can annotate it
    public void tearDown() throws Exception {
        super.tearDown();
    }

    @BeforeClass
    public static void startZkServer() throws Exception {
        initZkServer();
    }

    @AfterClass
    public static void stopZkServer() throws Exception {
        shutdownZkServer();
    }

    @Test
    public void testDeleteNodeNotEmpty() throws StateAccessException {

        zk.addPersistent(getPath("/foo"), null);

        testObj.add(zk.getDeleteOp(getPath("/foo")));

        zk.addPersistent(getPath("/foo/bar"), null);
        zk.addPersistent(getPath("/foo/bar/baz"), null);

        testObj.commit();
    }

    @Test(expected=NodeNotEmptyStateException.class)
    public void testOverDelRetryLimit() throws StateAccessException {

        zk.addPersistent(getPath("/foo"), null);

        testObj.add(zk.getDeleteOp(getPath("/foo")));

        for (int i = 0 ; i < ZkOpList.DEL_RETRIES + 1; i++) {

            String path = getPath("/foo/bar" + i);
            zk.addPersistent(path, null);
            zk.addPersistent(path + "/baz", null);
            testObj.add(zk.getDeleteOp(path));
        }

        testObj.commit();
    }

    @Test
    public void testDeleteNoNode() throws StateAccessException {

        zk.addPersistent(getPath("/foo"), null);
        zk.addPersistent(getPath("/foo/bar"), null);

        testObj.add(zk.getDeleteOp(getPath("/foo")));
        testObj.add(zk.getDeleteOp(getPath("/foo/bar")));

        zk.delete(getPath("/foo/bar"));

        testObj.commit();
    }

    @Test
    public void testDeleteAfterCreateUpdateNoNode()
        throws StateAccessException {

        zk.addPersistent(getPath("/foo"), null);
        zk.addPersistent(getPath("/foo/bar"), null);

        // Create and update a node and delete its sub path.
        // This needs to be tested because ZkOpList does delete ops first.
        testObj.add(zk.getPersistentCreateOp("/foo/bar/baz", null));
        testObj.add(zk.getSetDataOp("/foo/bar/baz", null));
        testObj.add(zk.getDeleteOp("/foo/bar"));

        testObj.commit();
    }

    @Test(timeout=10000)
    public void testAsyncExists() throws Exception {
        zk.addPersistent(getPath("/foo"), null);

        AsyncSubject<Boolean> s = AsyncSubject.create();
        zk.asyncExists(getPath("/foo"), DirectoryCallbackFactory.wrap(s));
        Assert.assertTrue(s.toBlocking().single());

        AsyncSubject<Boolean> s2 = AsyncSubject.create();
        zk.asyncExists(getPath("/bar"), DirectoryCallbackFactory.wrap(s2));
        Assert.assertFalse(s2.toBlocking().single());
    }
}
