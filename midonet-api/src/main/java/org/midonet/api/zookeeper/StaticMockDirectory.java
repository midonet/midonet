/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.zookeeper;

import org.midonet.midolman.state.MockDirectory;

/**
 * This is a static MockDirectory in order to allow an integration test
 * access to a Directory implementation that is shared between the test and
 * the midonet-api Jersey application.
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
        if (_directoryInstance == null) {
            new StaticMockDirectory();
        }
        return _directoryInstance;
    }

    public static void clearDirectoryInstance() {
        _directoryInstance = null;
    }
}
