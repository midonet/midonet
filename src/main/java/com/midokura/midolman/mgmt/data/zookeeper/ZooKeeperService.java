/*
 * @(#)ZookeeperService        1.6 11/09/05
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.zookeeper;

import com.midokura.midolman.mgmt.config.AppConfig;
import com.midokura.midolman.state.Directory;
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

    private static ZooKeeperService service = null;
    private ZkConnection conn = null;

    private ZooKeeperService(ZkConnection conn) {
        this.conn = conn;
    }

    /**
     * Get the Zookeeper connection.
     *
     * @throws Exception
     * @throws
     */
    synchronized public static ZooKeeperService getService() throws Exception {
        if (null == service) {
            AppConfig c = AppConfig.getConfig();
            ZkConnection conn = new ZkConnection(c.getZkConnectionString(),
                    c.getZkTimeout(), null);
            conn.open();
            service = new ZooKeeperService(conn);
        }
        return service;
    }

    public Directory getZooKeeper() {
        return this.conn.getRootDirectory();
    }
}
