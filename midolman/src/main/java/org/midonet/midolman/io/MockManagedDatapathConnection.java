/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.midolman.io;

import org.midonet.netlink.Callback;
import org.midonet.odp.protos.OvsDatapathConnection;

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


    @Override
    public void start(Callback<Boolean> cb) {
        start();
        cb.onSuccess(true);
    }

    @Override
    public void start() {
        this.conn = OvsDatapathConnection.createMock();
    }

    public void stop() throws Exception {}
}
