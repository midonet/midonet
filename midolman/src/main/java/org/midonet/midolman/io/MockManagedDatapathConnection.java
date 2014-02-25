/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.midolman.io;

import org.midonet.odp.protos.OvsDatapathConnection;
import org.midonet.util.eventloop.Reactor;

public class MockManagedDatapathConnection implements ManagedDatapathConnection {
    private OvsDatapathConnection conn = null;

    public MockManagedDatapathConnection() {}

    public OvsDatapathConnection getConnection() {
        if (conn == null) {
            try {
                start();
            } catch (Exception e)  {
                throw new RuntimeException(e);
            }
        }
        return conn;
    }

    public void start() throws Exception {
        this.conn = OvsDatapathConnection.createMock();
    }

    public void stop() throws Exception {}
}
