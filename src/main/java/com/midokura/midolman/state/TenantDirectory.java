/*
 * @(#)TenantDirectory        1.6 11/09/07
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.state;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;

import com.midokura.midolman.state.RouterDirectory.RouterConfig;

/**
 * Class representing Zookeeper directory for tenants.
 *
 * @version        1.6 07 Sept 2011
 * @author         Ryu Ishimoto
 */
public class TenantDirectory {
    /*
     * Implements Zookeeper directory data access and updates for tenants,
     * including serialization.
     */

    /**
     * Serializable class for tenant data value.
     */
    public static class TenantConfig implements Serializable {

        private static final long serialVersionUID = 1381363889282090330L;
        
        public TenantConfig(String name) {
            super();
            this.name = name;
        }

        /**
         * Arbitrary name given to tenants.  Does not check for uniqueness.
         */
        public String name;
    }
    
    Directory dir;

    /**
     * Default constructor.
     * 
     * @param dir  The root Directory object for the tenant directory. 
     */
    public TenantDirectory(Directory dir) {
        this.dir = dir;
    }

    private byte[] tenantToBytes(TenantConfig tenant) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ObjectOutputStream out = new ObjectOutputStream(bos);
        out.writeObject(tenant);
        out.close();
        return bos.toByteArray();
    }    

    private TenantConfig bytesToTenant(byte[] data)
            throws IOException, ClassNotFoundException, KeeperException,
                InterruptedException {
        ByteArrayInputStream bis = new ByteArrayInputStream(data);
        ObjectInputStream in = new ObjectInputStream(bis);
        TenantConfig tenant = (TenantConfig) in.readObject();
        return tenant;
    }
    
    /**
     * Add a new tenant entry in the Zookeeper directory.
     * 
     * @param id  Tenant UUID
     * @param tenant  TenantConfig object to store tenant data.
     * @throws KeeperException  General Zookeeper exception.
     * @throws InterruptedException  Unresponsive thread getting
     * interrupted by another thread.
     * @throws IOException  Error while converting TenantConfig to bytes.
     */
    public void addTenant(UUID id, TenantConfig tenant) 
        throws KeeperException, InterruptedException, IOException {
        // Perform atomic insert.
        // Add Routers child node.
        byte[] data = tenantToBytes(tenant);
        String path = "/" + id.toString();
        dir.add(path, data, CreateMode.PERSISTENT);
        dir.add(path + "/routers", null, CreateMode.PERSISTENT);
    }
    
    /**
     * Get TenantConfig from a given ID.
     * 
     * @param id  Unique tenant ID.
     * @return  TenantConfig object for the ID.
     * @throws KeeperException  Zookeeper error.
     * @throws InterruptedException  Unresponsive thread error.
     * @throws IOException  Error deserializing data.
     * @throws ClassNotFoundException Class was not found.
     */
    public TenantConfig getTenant(UUID id) 
            throws KeeperException, InterruptedException, IOException, 
                   ClassNotFoundException  {
        byte[] data = dir.get("/" + id.toString(), null);
        TenantConfig config = bytesToTenant(data);
        return config;
    }
    
    /**
     * Check if tenant node exists.
     * 
     * @param id  Tenant UUID
     * @return  True if exists.  False otherwise.
     * @throws KeeperException
     * @throws InterruptedException
     */
    public boolean exists(UUID id)
            throws KeeperException, InterruptedException {
        return dir.has("/" + id.toString());
    }   
    
    /**
     * Get a list of RouterConfig objects for a tenant.
     * 
     * @param id  Tenant ID to get the routers for.
     * @return  A list of Router objects.
     * @throws InterruptedException  Thread paused too long.
     * @throws KeeperException Zookeeper error.
     */
    public Set<RouterConfig> getRouters(UUID id) 
            throws KeeperException, InterruptedException {
        Set<RouterConfig> routers = new HashSet<RouterConfig>();
        String path = new StringBuilder("/")
            .append(id.toString())
            .append("/routers").toString();
        Set<String> routerStrs = dir.getChildren(path, null);
        for (String routerStr : routerStrs)
            routers.add(new RouterConfig("foo", id)); // TODO: Fix this when JSON serialization is done.
        return routers;
    }
}

