/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.dao.zookeeper;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.zookeeper.Op;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.data.dto.config.RouterMgmtConfig;
import com.midokura.midolman.mgmt.data.dto.config.RouterNameMgmtConfig;
import com.midokura.midolman.mgmt.data.zookeeper.path.PathBuilder;
import com.midokura.midolman.state.RouterZkManager;
import com.midokura.midolman.state.RouterZkManager.RouterConfig;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkConfigSerializer;
import com.midokura.midolman.state.ZkNodeEntry;

/**
 * Proxy class to access ZooKeeper for router data.
 */
public class RouterZkDao {

    private final static Logger log = LoggerFactory
            .getLogger(RouterZkDao.class);
    private final RouterZkManager zkDao;
    private final PathBuilder pathBuilder;
    private final ZkConfigSerializer serializer;

    /**
     * Constructor
     *
     * @param zkDao
     *            ZkManager object to access ZK data.
     * @param pathBuilder
     *            PathBuilder object to get path data.
     * @param serializer
     *            ZkConfigSerializer object.
     */
    public RouterZkDao(RouterZkManager zkDao, PathBuilder pathBuilder,
            ZkConfigSerializer serializer) {
        this.zkDao = zkDao;
        this.pathBuilder = pathBuilder;
        this.serializer = serializer;
    }

    /**
     * Checks whether a router exists with the given ID.
     *
     * @param id
     *            router ID
     * @return True if router exists.
     * @throws StateAccessException
     *             Data access error.
     */
    public boolean exists(UUID id) throws StateAccessException {
        log.debug("RouterZkDao.exists entered: id={}", id);

        String path = pathBuilder.getRouterPath(id);
        boolean exists = zkDao.exists(path);

        log.debug("RouterZkDao.exists exiting: exists=" + exists);
        return exists;
    }

    /**
     * Get the management data for the given router.
     *
     * @param id
     *            ID of the router.
     * @return RouterMgmtConfig stored in ZK.
     * @throws StateAccessException
     *             Data access error.
     */
    public RouterMgmtConfig getMgmtData(UUID id) throws StateAccessException {
        log.debug("RouterZkDao.getMgmtData entered: id={}", id);

        String path = pathBuilder.getRouterPath(id);
        byte[] data = zkDao.get(path);
        RouterMgmtConfig config = serializer.deserialize(data,
                RouterMgmtConfig.class);

        log.debug("RouterZkDao.getMgmtData exiting: path={}", path);
        return config;
    }

    /**
     * Get the data for the given router.
     *
     * @param id
     *            ID of the router.
     * @return RouterConfig stored in ZK.
     * @throws StateAccessException
     *             Data access error.
     */
    public RouterConfig getData(UUID id) throws StateAccessException {
        ZkNodeEntry<UUID, RouterConfig> node = zkDao.get(id);
        RouterConfig config = node.value;
        return config;

    }

    /**
     * Get the name data for the given router.
     *
     * @param tenantId
     *            ID of the tenant.
     * @param name
     *            Name of the router.
     * @return RouterNameMgmtConfig stored in ZK.
     * @throws StateAccessException
     *             Data access error.
     */
    public RouterNameMgmtConfig getNameData(String tenantId, String name)
            throws StateAccessException {
        log.debug("RouterZkDao.getNameData entered: tenantId=" + tenantId
                + ",name=" + name);

        String path = pathBuilder.getTenantRouterNamePath(tenantId, name);
        byte[] data = zkDao.get(path);
        RouterNameMgmtConfig config = serializer.deserialize(data,
                RouterNameMgmtConfig.class);

        log.debug("RouterZkDao.getNameData exiting: path=" + path);
        return config;
    }

    /**
     * Get a set of router IDs for a given tenant.
     *
     * @param tenantId
     *            ID of the tenant.
     * @return Set of router IDs.
     * @throws StateAccessException
     *             Data access error.
     */
    public Set<String> getIds(String tenantId) throws StateAccessException {
        log.debug("RouterZkDao.getIds entered: tenantId={}", tenantId);

        String path = pathBuilder.getTenantRoutersPath(tenantId);
        Set<String> ids = zkDao.getChildren(path, null);

        log.debug("RouterZkDao.getIds exiting: path=" + path + " ids count="
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
        log.debug("RouterZkDao.multi entered: ops count={}", ops.size());
        zkDao.multi(ops);
        log.debug("RouterZkDao.multi exiting.");
    }
}
