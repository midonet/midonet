/*
 * @(#)DataAccessor        1.6 11/09/07
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dao;

/**
 * Base data access class.
 * 
 * @version 1.6 05 Sept 2011
 * @author Ryu Ishimoto
 */
public abstract class DataAccessor {
    protected String zkConn = null;
    protected int zkTimeout = -1;
    protected String zkRoot = null;
    protected String zkMgmtRoot = null;

    /**
     * Constructor
     * 
     * @param zkConn
     *            Zookeeper connection string.
     */
    public DataAccessor(String zkConn, int timeout, String zkRoot,
            String zkMgmtRoot) {
        this.zkConn = zkConn;
        this.zkTimeout = timeout;
        this.zkRoot = zkRoot;
        this.zkMgmtRoot = zkMgmtRoot;
    }
}
