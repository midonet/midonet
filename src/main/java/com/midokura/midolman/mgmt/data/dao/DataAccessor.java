/*
 * @(#)DataAccessor        1.6 11/09/07
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dao;


/**
 * Base data access class.
 *
 * @version        1.6 05 Sept 2011
 * @author         Ryu Ishimoto
 */
public abstract class DataAccessor {

    protected String zkConn = null;
    
    /**
     * Default constructor
     * 
     * @param  zkConn  Zookeeper connection string.
     */ 
    public DataAccessor(String zkConn) {
        this.zkConn = zkConn;
    }    
}
