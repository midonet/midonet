/*
 * @(#)DataAccessor        1.6 11/09/07
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data;

import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.RouterDirectory;
import com.midokura.midolman.state.TenantDirectory;
import com.midokura.midolman.state.ZkConnection;

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
    
    protected RouterDirectory getRouterDirectory() throws Exception {
        ZkConnection zk = ZookeeperService.getConnection(zkConn);
        Directory dir = zk.getRootDirectory().getSubDirectory(
                "/midolman/routers");
        return new RouterDirectory(dir);
    }

    protected TenantDirectory getTenantDirectory() throws Exception {
        ZkConnection zk = ZookeeperService.getConnection(zkConn);
        Directory dir = zk.getRootDirectory().getSubDirectory(
                "/midolman/tenants");
        return new TenantDirectory(dir);
    }
}
