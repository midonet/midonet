/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.midolman.io;

import org.midonet.odp.protos.OvsDatapathConnection;

public interface ManagedDatapathConnection {
    OvsDatapathConnection getConnection();

    void start() throws Exception;

    void stop() throws Exception;
}
