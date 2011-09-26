/*
 * @(#)ZookeeperService        1.6 11/09/05
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data;

import org.apache.zookeeper.ZooKeeper;

import com.midokura.midolman.state.ZkConnection;

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

    public static synchronized ZooKeeper getZooKeeper(String connStr,
            int timeout) throws Exception {
        return getConnection(connStr, timeout).getZooKeeper();
    }
}
