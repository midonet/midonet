/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.midolman.state;

import org.junit.Before;
import org.junit.Test;

public class ZkOpListTest extends ZookeeperTest {

    private ZkManager zk;
    private ZkOpList testObj;

    @Before
    public void setup() throws Exception {
        zk = injector.getInstance(ZkManager.class);
        testObj = new ZkOpList(zk);
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
}
