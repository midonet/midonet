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

import com.midokura.midolman.mgmt.data.dto.config.BridgeMgmtConfig;
import com.midokura.midolman.mgmt.data.dto.config.BridgeNameMgmtConfig;
import com.midokura.midolman.mgmt.data.dto.config.PeerRouterConfig;
import com.midokura.midolman.mgmt.data.zookeeper.io.BridgeSerializer;
import com.midokura.midolman.mgmt.data.zookeeper.path.PathBuilder;
import com.midokura.midolman.state.BridgeZkManager;
import com.midokura.midolman.state.BridgeZkManager.BridgeConfig;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkNodeEntry;

/**
 * Proxy class to access ZooKeeper for bridge data.
 */
public class BridgeZkDao {

    private final static Logger log = LoggerFactory
            .getLogger(BridgeZkDao.class);
    private final BridgeZkManager zkDao;
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
    public BridgeZkDao(BridgeZkManager zkDao, PathBuilder pathBuilder,
            BridgeSerializer serializer) {
        this.zkDao = zkDao;
        this.pathBuilder = pathBuilder;
        this.serializer = serializer;
    }

    /**
     * Checks whether a bridge exists with the given ID.
     *
     * @param id
     *            Bridge ID
     * @return True if bridge exists.
     * @throws StateAccessException
     *             Data access error.
     */
    public boolean exists(UUID id) throws StateAccessException {
        log.debug("BridgeZkDao.exists entered: id={}", id);

        String path = pathBuilder.getBridgePath(id);
        boolean exists = zkDao.exists(path);

        log.debug("BridgeZkDao.exists exiting: exists=" + exists);
        return exists;
    }

    /**
     * Get the data for the given bridge.
     *
     * @param id
     *            ID of the bridge.
     * @return BridgeConfig stored in ZK.
     * @throws StateAccessException
     *             Data access error.
     */
    public BridgeConfig getData(UUID id) throws StateAccessException {
        ZkNodeEntry<UUID, BridgeConfig> node = zkDao.get(id);
        BridgeConfig config = node.value;
        return config;

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
     * @param tenantId
     *            ID of the Tenant.
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

    /**
     * Get a set of connected router IDs for a given bridge.
     *
     * @param bridgeId
     *            ID of the bridge.
     * @return Set of router IDs.
     * @throws StateAccessException
     *             Data access error.
     */
    public Set<String> getRouterIds(UUID bridgeId) throws StateAccessException {
        String path = pathBuilder.getBridgeRoutersPath(bridgeId);
        return zkDao.getChildren(path, null);
    }

    /**
     * Get the PeerRouterConfig object for the link between the given IDs.
     *
     * @param bridgeId
     *            Bridge ID
     * @param routerId
     *            Router ID
     * @return PeerRouterConfig object.
     * @throws StateAccessException
     *             Data access error.
     */
    public PeerRouterConfig getBridgeRouterLinkData(UUID bridgeId, UUID routerId)
            throws StateAccessException {
        String path = pathBuilder.getBridgeRouterPath(bridgeId, routerId);
        byte[] data = zkDao.get(path);
        return serializer.deserializePeer(data);
    }
}
