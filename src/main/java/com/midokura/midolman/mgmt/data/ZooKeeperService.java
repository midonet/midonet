/*
 * @(#)ZookeeperService        1.6 11/09/05
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data;

import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.ZkConnection;
import com.midokura.midolman.state.ZkDirectory;

/**
 * Class implementing a singleton Zookeeper connection.
 * 
 * @version 1.6 05 Sept 2011
 * @author Ryu Ishimoto
 */
public class ZooKeeperService {
    /*
     * Singleton implementation for ZooKeeper connection.
     */

    private static ZkConnection conn = null;

    private ZooKeeperService() {
    }

    /**
     * Get the Zookeeper connection.
     */
    public static synchronized ZkConnection getConnection(String connStr,
            int timeout) throws Exception {
        if (null == conn) {
            conn = new ZkConnection(connStr, timeout, null);
            conn.open();
        }
        return conn;
    }

    public static synchronized Directory getZooKeeper(String connStr,
            int timeout) throws Exception {
        return new ZkDirectory(getConnection(connStr, timeout).getZooKeeper(),
                "", null);
    }
}
