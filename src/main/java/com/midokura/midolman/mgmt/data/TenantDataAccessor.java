/*
 * @(#)TenantDataAccessor        1.6 11/09/05
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data;

import java.util.UUID;

import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.TenantDirectory;
import com.midokura.midolman.state.ZkConnection;
import com.midokura.midolman.state.TenantDirectory.TenantConfig;

/**
 * Data access class for tenant.
 *
 * @version        1.6 07 Sept 2011
 * @author         Ryu Ishimoto
 */
public class TenantDataAccessor {
    /*
     * Implements CRUD operations on Tenant.
     */
    
    private String zkConn = null;
    
    /**
     * Default constructor
     * 
     * @param  zkConn  Zookeeper connection string.
     */ 
    public TenantDataAccessor(String zkConn) {
        this.zkConn = zkConn;
    }

    private TenantDirectory getDirectory() throws Exception {
        ZkConnection zk = ZookeeperService.getConnection(zkConn);
        Directory dir = zk.getRootDirectory().getSubDirectory(
                "/midolman/tenants");
        return new TenantDirectory(dir);
    }
    
    private TenantConfig convertToConfig(Tenant tenant) {
        return new TenantConfig(tenant.getName());
    }

    private Tenant convertToTenant(TenantConfig config) {
        Tenant tenant = new Tenant();
        tenant.setName(config.name);        
        return tenant;
    }
    
    /**
     * Add Router object to Zookeeper directories.
     * 
     * @param   router  Tenant object to add.
     * @throws  Exception  Error adding data to Zookeeper.
     */
    public void create(Tenant tenant) throws Exception  {
        // Convert Tenant to TenantConfig.  This may be unnecessary once
        // TenantConfig becomes JSON serializable.
        TenantConfig config = convertToConfig(tenant);
        TenantDirectory dir = getDirectory();
        dir.addTenant(tenant.getId(), config);
    }

    /**
     * Get a Tenant for the given ID.
     * 
     * @param   id  Tenant ID to search.
     * @return  Tenant object with the given ID.
     * @throws  Exception  Error getting data to Zookeeper.
     */
    public Tenant find(UUID id) throws Exception {
        TenantDirectory dir = getDirectory();
        TenantConfig config = dir.getTenant(id);
        // TODO: Throw NotFound exception here.
        Tenant tenant = convertToTenant(config);
        tenant.setId(id);
        return tenant;
    }
}
