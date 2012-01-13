/*
 * @(#)TenantZkDao        1.6 12/1/6
 *
 * Copyright 2012 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dao.zookeeper;

import java.util.List;
import java.util.Set;

import org.apache.zookeeper.Op;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.data.zookeeper.path.PathBuilder;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkManager;

/**
 * Proxy class to access ZooKeeper for tenants data.
 *
 * @version 1.6 6 Jan 2012
 * @author Ryu Ishimoto
 */
public class TenantZkDao {

    private final static Logger log = LoggerFactory
            .getLogger(TenantZkDao.class);
    private final ZkManager zkDao;
    private final PathBuilder pathBuilder;

    /**
     * Constructor
     *
     * @param zkDao
     *            ZkManager object to access ZK data.
     * @param pathBuilder
     *            PathBuilder object to get path data.
     */
    public TenantZkDao(ZkManager zkDao, PathBuilder pathBuilder) {
        this.zkDao = zkDao;
        this.pathBuilder = pathBuilder;
    }

    /**
     * Checks whether a tenant exists with the given ID.
     *
     * @param id
     *            Tenant ID
     * @return True if tenant exists.
     * @throws StateAccessException
     *             Data access error.
     */
    public boolean exists(String id) throws StateAccessException {
        log.debug("TenantZkDao.exists entered: id={}", id);

        String path = pathBuilder.getTenantPath(id);
        boolean exists = zkDao.exists(path);

        log.debug("TenantZkDao.exists exiting: exists=" + exists);
        return exists;
    }

    /**
     * Get the data for the given tenant.
     *
     * @param id
     *            ID of the tenant.
     * @return Byte array data stored in ZK.
     * @throws StateAccessException
     *             Data access error.
     */
    public byte[] getData(String id) throws StateAccessException {
        log.debug("TenantZkDao.getData entered: id={}", id);

        String path = pathBuilder.getTenantPath(id);
        byte[] data = zkDao.get(path);

        log.debug("TenantZkDao.getData exiting: path={}", path);
        return data;
    }

    /**
     * Get a set of tenant IDs.
     *
     * @return Set of tenant IDs.
     * @throws StateAccessException
     *             Data access error.
     */
    public Set<String> getIds() throws StateAccessException {
        log.debug("TenantZkDao.getIds entered.");

        String path = pathBuilder.getTenantsPath();
        Set<String> ids = zkDao.getChildren(path, null);

        log.debug("TenantZkDao.getIds exiting: path=" + path + " ids count="
                + ids.size());
        return ids;
    }

    /**
     * Wrapper for multi ZK API. The paths in the Op list are expected already
     * to be created.
     *
     * @param ops
     * @throws StateAccessException
     */
    public void multi(List<Op> ops) throws StateAccessException {
        log.debug("TenantZkDao.multi entered: ops count={}", ops.size());
        zkDao.multi(ops);
        log.debug("TenantZkDao.multi exiting.");
    }
}
