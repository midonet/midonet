/*
 * @(#)DataAccessor        1.6 11/09/07
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dao;

import org.apache.zookeeper.ZooKeeper;

/**
 * Base data access class.
 * 
 * @version 1.6 05 Sept 2011
 * @author Ryu Ishimoto
 */
public abstract class DataAccessor {
    protected ZooKeeper zkConn = null;
    protected String zkRoot = null;
    protected String zkMgmtRoot = null;

    /**
     * Constructor
     * 
     * @param zkConn
     *            Zookeeper connection string.
     */
    public DataAccessor(ZooKeeper zkConn, String zkRoot, String zkMgmtRoot) {
        this.zkConn = zkConn;
        this.zkRoot = zkRoot;
        this.zkMgmtRoot = zkMgmtRoot;
    }
}
