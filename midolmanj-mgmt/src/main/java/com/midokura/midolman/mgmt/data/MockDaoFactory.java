/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data;

import com.midokura.midolman.mgmt.config.AppConfig;
import com.midokura.midolman.mgmt.data.zookeeper.ZooKeeperDaoFactory;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.MockDirectory;

// This test code is included under src/main/java because it can be useful
// for in-memory testing in other projects that depend on midolmanj-mgmt.
// Code under src/test/java is not included in the midolmanj-mgmt jar.
public class MockDaoFactory extends ZooKeeperDaoFactory {

    public MockDaoFactory(AppConfig config) throws DaoInitializationException {
        super(config);
    }

    @Override
    synchronized public Directory getDirectory() {
        if (directory == null) {
            directory = new MockDirectory();
        }
        return directory;
    }
}
