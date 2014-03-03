/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.midolman.io;

import java.util.Iterator;
import java.util.LinkedList;

import com.google.inject.Inject;

import org.midonet.midolman.guice.datapath.DatapathModule;
import org.midonet.odp.protos.OvsDatapathConnection;


public class MockDatapathConnectionPool implements DatapathConnectionPool {
    @Inject
    @DatapathModule.UPCALL_DATAPATH_CONNECTION
    private ManagedDatapathConnection conn;

    public MockDatapathConnectionPool() {}

    public Iterator<OvsDatapathConnection> getAll() {
        LinkedList<OvsDatapathConnection> li = new LinkedList<>();
        li.add(conn.getConnection());
        return li.iterator();
    }

    public OvsDatapathConnection get(int hash) {
        return conn.getConnection();
    }

    public void start() throws Exception {}

    public void stop() throws Exception {}
}
