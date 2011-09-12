/*
 * @(#)TenantDataAccessor        1.6 11/09/05
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dao;

import java.util.UUID;

import com.midokura.midolman.mgmt.data.ZookeeperService;
import com.midokura.midolman.mgmt.data.dto.Tenant;
import com.midokura.midolman.state.TenantZkManager;
import com.midokura.midolman.state.ZkConnection;

/**
 * Data access class for tenant.
 * 
 * @version 1.6 07 Sept 2011
 * @author Ryu Ishimoto
 */
public class TenantDataAccessor extends DataAccessor {
    /*
     * Implements CRUD operations on Tenant.
     */

    /**
     * Constructor
     * 
     * @param zkConn
     *            ZooKeeper connection string
     */
    public TenantDataAccessor(String zkConn) {
        super(zkConn);
    }

    private TenantZkManager getTenantZkManager() throws Exception {
        ZkConnection conn = ZookeeperService.getConnection(zkConn);
        return new TenantZkManager(conn.getZooKeeper(), "/midolman");
    }

    /**
     * Add Router object to ZooKeeper directories.
     * 
     * @param tenant
     *            Tenant object to add.
     * @throws Exception
     *             ZooKeeper connection error.
     * @return Tenant ID.
     */
    public UUID create(Tenant tenant) throws Exception {
        TenantZkManager manager = getTenantZkManager();
        return manager.create(tenant.getId());
    }
}
