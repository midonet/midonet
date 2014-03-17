/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.midolman.io;

import java.util.Iterator;

import org.midonet.odp.protos.OvsDatapathConnection;

/*
 * A managed pool of DatapathConnections. Clients may iterate through
 * each of them or ask the pool to choose a connection for them based
 * on a hashCode, so that channel choice can be consistent across requests
 * that are related to the same object. */
public interface DatapathConnectionPool {
    /*
     * Returns an iterator over all the connections in the pool. Which will
     * be empty if the pool hasn't been started. */
    Iterator<OvsDatapathConnection> getAll();

    /*
     * Fetch an OvsDatapathConnection based on a hash code (or any other
     * integer the caller may want to supply). The hash argument is meant
     * to achieve consistent choice of channels across datapath requests
     * that operate on the same object. */
    OvsDatapathConnection get(int hash);

    /*
     * Starts this pool. After calling this method, calls to get() will
     * return connected, valid, channels. */
    public void start() throws Exception;

    /*
     * Stops this pool. */
    public void stop() throws Exception;
}
