/*
 * @(#)DataAccessor        1.6 11/09/07
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dao;

import com.midokura.midolman.mgmt.data.ZookeeperService;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.PortDirectory;
import com.midokura.midolman.state.PortZkManager;
import com.midokura.midolman.state.RouterDirectory;
import com.midokura.midolman.state.RouterZkManager;
import com.midokura.midolman.state.TenantDirectory;
import com.midokura.midolman.state.TenantZkManager;
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

    protected PortDirectory getPortDirectory() throws Exception {
        ZkConnection zk = ZookeeperService.getConnection(zkConn);
        Directory dir = zk.getRootDirectory().getSubDirectory(
                "/midolman/ports");
        return new PortDirectory(dir);
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
    
    protected TenantZkManager getTenantZkManager() throws Exception {
        ZkConnection conn = ZookeeperService.getConnection(zkConn);        
        return new TenantZkManager(conn.getZooKeeper(), "/midolman");
    }     
    
    protected RouterZkManager getRouterZkManager() throws Exception {
        ZkConnection conn = ZookeeperService.getConnection(zkConn);        
        return new RouterZkManager(conn.getZooKeeper(), "/midolman");
    } 

    protected PortZkManager getPortZkManager() throws Exception {
        ZkConnection conn = ZookeeperService.getConnection(zkConn);        
        return new PortZkManager(conn.getZooKeeper(), "/midolman");
    } 
}
