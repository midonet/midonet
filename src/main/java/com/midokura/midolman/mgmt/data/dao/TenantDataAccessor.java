/*
 * @(#)TenantDataAccessor        1.6 11/09/05
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dao;

import java.util.UUID;

import com.midokura.midolman.mgmt.data.dto.Tenant;
import com.midokura.midolman.state.TenantDirectory;
import com.midokura.midolman.state.TenantZkManager;
import com.midokura.midolman.state.TenantDirectory.TenantConfig;

/**
 * Data access class for tenant.
 *
 * @version        1.6 07 Sept 2011
 * @author         Ryu Ishimoto
 */
public class TenantDataAccessor extends DataAccessor {
    /*
     * Implements CRUD operations on Tenant.
     */

    /**
     * Default constructor 
     * 
     * @param zkConn Zookeeper connection string
     */
    public TenantDataAccessor(String zkConn) {
        super(zkConn);
    }
    
    private static TenantConfig convertToConfig(Tenant tenant) {
        return new TenantConfig(tenant.getName());
    }

    private static Tenant convertToTenant(TenantConfig config) {
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
        TenantZkManager manager = getTenantZkManager();
        manager.create(tenant.getId(), config);
    }

    /**
     * Get a Tenant for the given ID.
     * private
     * @param   id  Tenant ID to search.
     * @return  Tenant object with the given ID.
     * @throws  Exception  Error getting data to Zookeeper.
     */
    public Tenant find(UUID id) throws Exception {
        TenantDirectory dir = getTenantDirectory();
        TenantConfig config = dir.getTenant(id);
        // TODO: Throw NotFound exception here.
        Tenant tenant = convertToTenant(config);
        tenant.setId(id);
        return tenant;
    }
}
