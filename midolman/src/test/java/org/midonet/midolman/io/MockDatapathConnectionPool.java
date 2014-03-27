/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.midolman.io;

import java.util.Iterator;
import java.util.LinkedList;

import com.google.inject.Inject;

import org.midonet.midolman.util.mock.MockUpcallDatapathConnectionManager;
import org.midonet.odp.protos.OvsDatapathConnection;


public class MockDatapathConnectionPool implements DatapathConnectionPool {
    @Inject
    private UpcallDatapathConnectionManager upcallConnManager;

    public MockDatapathConnectionPool() {}

    private OvsDatapathConnection conn() {
        return ((MockUpcallDatapathConnectionManager) upcallConnManager).
                conn().getConnection();
    }

    public Iterator<OvsDatapathConnection> getAll() {
        LinkedList<OvsDatapathConnection> li = new LinkedList<>();
        li.add(conn());
        return li.iterator();
    }

    public OvsDatapathConnection get(int hash) {
        return conn();
    }

    public void start() throws Exception {}

    public void stop() throws Exception {}
}
