/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.midolman.io;

import java.util.concurrent.ExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.netlink.Callback;
import org.midonet.odp.protos.OvsDatapathConnection;

public class TrivialDatapathConnection implements ManagedDatapathConnection {
    private Logger log = LoggerFactory.getLogger(this.getClass());


    private OvsDatapathConnection conn = null;

    public TrivialDatapathConnection(OvsDatapathConnection conn) {
        this.conn = conn;
    }

    @Override
    public OvsDatapathConnection getConnection() {
        return conn;
    }

    @Override
    public void start() throws InterruptedException, ExecutionException {
        conn.futures.initialize().get();
    }

    @Override
    public void start(Callback<Boolean> cb) {
        conn.initialize(cb);
    }

    @Override
    public void stop() throws Exception {}
}
