/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.midolman.io;

import org.midonet.netlink.Callback;
import org.midonet.odp.protos.OvsDatapathConnection;

import java.io.IOException;
import java.util.concurrent.ExecutionException;

public interface ManagedDatapathConnection {
    OvsDatapathConnection getConnection();

    void start() throws IOException, InterruptedException, ExecutionException;

    void start(Callback<Boolean> cb);

    void stop() throws Exception;
}
