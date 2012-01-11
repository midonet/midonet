/*
 * @(#)BridgeZkDao        1.6 12/1/6
 *
 * Copyright 2012 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dao.zookeeper;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.zookeeper.Op;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.data.dto.config.BridgeMgmtConfig;
import com.midokura.midolman.mgmt.data.dto.config.BridgeNameMgmtConfig;
import com.midokura.midolman.mgmt.data.zookeeper.io.BridgeSerializer;
import com.midokura.midolman.mgmt.data.zookeeper.path.PathBuilder;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkManager;

/**
 * Proxy class to access ZooKeeper for bridge data.
 *
 * @version 1.6 6 Jan 2012
 * @author Ryu Ishimoto
 */
public class BridgeZkDao {

    private final static Logger log = LoggerFactory
            .getLogger(BridgeZkDao.class);
    private final ZkManager zkDao;
    private final PathBuilder pathBuilder;
    private final BridgeSerializer serializer;

    /**
     * Constructor
     *
     * @param zkDao
     *            ZkManager object to access ZK data.
     * @param pathBuilder
     *            PathBuilder object to get path data.
     * @param serializer
     *            BridgeSerializer object.
     */
    public BridgeZkDao(ZkManager zkDao, PathBuilder pathBuilder,
            BridgeSerializer serializer) {
        this.zkDao = zkDao;
        this.pathBuilder = pathBuilder;
        this.serializer = serializer;
    }

    /**
     * Get the data for the given bridge.
     *
     * @param id
     *            ID of the bridge.
     * @return BridgeMgmtConfig stored in ZK.
     * @throws StateAccessException
     *             Data access error.
     */
    public BridgeMgmtConfig getMgmtData(UUID id) throws StateAccessException {
        log.debug("BridgeZkDao.getData entered: id={}", id);

        String path = pathBuilder.getBridgePath(id);
        byte[] data = zkDao.get(path);
        BridgeMgmtConfig config = serializer.deserialize(data);

        log.debug("BridgeZkDao.getData exiting: path={}", path);
        return config;
    }

    /**
     * Get the name data for the given bridge.
     *
     * @param id
     *            ID of the bridge.
     * @return BridgeNameMgmtConfig stored in ZK.
     * @throws StateAccessException
     *             Data access error.
     */
    public BridgeNameMgmtConfig getNameData(String tenantId, String name)
            throws StateAccessException {
        log.debug("BridgeZkDao.getNameData entered: tenantId=" + tenantId
                + ",name=" + name);

        String path = pathBuilder.getTenantBridgeNamePath(tenantId, name);
        byte[] data = zkDao.get(path);
        BridgeNameMgmtConfig config = serializer.deserializeName(data);

        log.debug("BridgeZkDao.getNameData exiting: path=" + path);
        return config;
    }

    /**
     * Get a set of bridge IDs for a given tenant.
     *
     * @param tenantId
     *            ID of the tenant.
     * @return Set of bridge IDs.
     * @throws StateAccessException
     *             Data access error.
     */
    public Set<String> getIds(String tenantId) throws StateAccessException {
        log.debug("BridgeZkDao.getIds entered: tenantId={}", tenantId);

        String path = pathBuilder.getTenantBridgesPath(tenantId);
        Set<String> ids = zkDao.getChildren(path, null);

        log.debug("BridgeZkDao.getIds exiting: path=" + path + " ids count="
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
        log.debug("BridgeZkDao.multi entered: ops count={}", ops.size());
        zkDao.multi(ops);
        log.debug("BridgeZkDao.multi exiting.");
    }
}
