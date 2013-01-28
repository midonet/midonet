/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midonet.api.zookeeper;

import com.midokura.midolman.state.MockDirectory;

/**
 * This is a static MockDirectory in order to allow an integration test
 * access to a Directory implementation that is shared between the test and
 * the Midolman-mgmt Jersey application.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date 1/31/12
 */
public class StaticMockDirectory {

    private static MockDirectory _directoryInstance;

    public StaticMockDirectory()  {
        _directoryInstance = _initializeWrappedInstance();
    }

    private static synchronized MockDirectory _initializeWrappedInstance() {
        if (_directoryInstance == null) {
            _directoryInstance = new MockDirectory();
        }

        return _directoryInstance;
    }

    public static MockDirectory getDirectoryInstance() {
        return _directoryInstance;
    }

    public static void clearDirectoryInstance() {
        _directoryInstance = null;
    }


}
