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

import com.midokura.midolman.mgmt.data.dto.config.PortMgmtConfig;
import com.midokura.midolman.mgmt.data.zookeeper.io.PortSerializer;
import com.midokura.midolman.mgmt.data.zookeeper.path.PathBuilder;
import com.midokura.midolman.state.PortConfig;
import com.midokura.midolman.state.PortZkManager;
import com.midokura.midolman.state.StateAccessException;

/**
 * Proxy class to access ZooKeeper for port data.
 */
public class PortZkDao {

    private final static Logger log = LoggerFactory.getLogger(PortZkDao.class);
    private final PortZkManager zkDao;
    private final PathBuilder pathBuilder;
    private final PortSerializer serializer;

    /**
     * Constructor
     *
     * @param zkDao
     *            PortZkManager object to access ZK data.
     * @param pathBuilder
     *            PathBuilder object to get path data.
     * @param serializer
     *            PortSerializer object.
     */
    public PortZkDao(PortZkManager zkDao, PathBuilder pathBuilder,
            PortSerializer serializer) {
        this.zkDao = zkDao;
        this.pathBuilder = pathBuilder;
        this.serializer = serializer;
    }

    /**
     * Checks whether a port exists with the given ID.
     *
     * @param id
     *            Port ID
     * @return True if port exists.
     * @throws StateAccessException
     *             Data access error.
     */
    public boolean exists(UUID id) throws StateAccessException {
        log.debug("PortZkDao.exists entered: id={}", id);

        String path = pathBuilder.getPortPath(id);
        boolean exists = zkDao.exists(path);

        log.debug("PortZkDao.exists exiting: exists=" + exists);
        return exists;
    }

    /**
     * Get the data for the given port.
     *
     * @param id
     *            ID of the port.
     * @return PortConfig stored in ZK.
     * @throws StateAccessException
     *             Data access error.
     */
    public PortConfig getData(UUID id) throws StateAccessException {
        log.debug("PortZkDao.getData entered: id={}", id);

        PortConfig config = zkDao.get(id);

        log.debug("PortZkDao.getData exiting");
        return config;
    }

    /**
     * Get the data for the given port.
     *
     * @param id
     *            ID of the port.
     * @return PortMgmtConfig stored in ZK.
     * @throws StateAccessException
     *             Data access error.
     */
    public PortMgmtConfig getMgmtData(UUID id) throws StateAccessException {
        log.debug("PortZkDao.getMgmtData entered: id={}", id);

        String path = pathBuilder.getPortPath(id);
        byte[] data = zkDao.get(path);
        PortMgmtConfig config = null;
        if (data != null) {
            config = serializer.deserialize(data);
        }

        log.debug("PortZkDao.getMgmtData exiting: path=" + path);
        return config;
    }

    /**
     * Get a set of port IDs for a given router.
     *
     * @param routerId
     *            ID of the router.
     * @return Set of port IDs.
     * @throws StateAccessException
     *             Data access error.
     */
    public Set<UUID> getRouterPortIds(UUID routerId)
            throws StateAccessException {
        log.debug("PortZkDao.getRouterPortIds entered: routerId={}", routerId);

        Set<UUID> ids = zkDao.getRouterPortIDs(routerId);

        log.debug("PortZkDao.getRouterPortIds exiting: ids count=" + ids.size());
        return ids;
    }

    /**
     * Get a set of port IDs for a given bridge.
     *
     * @param bridgeId
     *            ID of the bridge.
     * @return Set of port IDs.
     * @throws StateAccessException
     *             Data access error.
     */
    public Set<UUID> getBridgePortIds(UUID bridgeId)
            throws StateAccessException {
        log.debug("PortZkDao.getBridgePortIds entered: bridgeId={}", bridgeId);

        Set<UUID> ids = zkDao.getBridgePortIDs(bridgeId);

        // Get the logical bridge ports.
        ids.addAll(zkDao.getBridgeLogicalPortIDs(bridgeId));

        log.debug("PortZkDao.getBridgePortIds exiting: ids count=" + ids.size());
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
        log.debug("PortZkDao.multi entered: ops count={}", ops.size());
        zkDao.multi(ops);
        log.debug("PortZkDao.multi exiting.");
    }
}
