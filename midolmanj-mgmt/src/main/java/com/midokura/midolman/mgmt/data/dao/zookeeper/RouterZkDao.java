/*
 * @(#)RouterZkDao        1.6 12/1/6
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

import com.midokura.midolman.mgmt.data.dto.config.PeerRouterConfig;
import com.midokura.midolman.mgmt.data.dto.config.RouterMgmtConfig;
import com.midokura.midolman.mgmt.data.dto.config.RouterNameMgmtConfig;
import com.midokura.midolman.mgmt.data.zookeeper.io.RouterSerializer;
import com.midokura.midolman.mgmt.data.zookeeper.path.PathBuilder;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkManager;

/**
 * Proxy class to access ZooKeeper for router data.
 *
 * @version 1.6 6 Jan 2012
 * @author Ryu Ishimoto
 */
public class RouterZkDao {

    private final static Logger log = LoggerFactory
            .getLogger(RouterZkDao.class);
    private final ZkManager zkDao;
    private final PathBuilder pathBuilder;
    private final RouterSerializer serializer;

    /**
     * Constructor
     *
     * @param zkDao
     *            ZkManager object to access ZK data.
     * @param pathBuilder
     *            PathBuilder object to get path data.
     * @param serializer
     *            RouterSerializer object.
     */
    public RouterZkDao(ZkManager zkDao, PathBuilder pathBuilder,
            RouterSerializer serializer) {
        this.zkDao = zkDao;
        this.pathBuilder = pathBuilder;
        this.serializer = serializer;
    }

    /**
     * Get the data for the given router.
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
        RouterMgmtConfig config = serializer.deserialize(data);

        log.debug("RouterZkDao.getMgmtData exiting: path={}", path);
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
        RouterNameMgmtConfig config = serializer.deserializeName(data);

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
     * Get a set of peer router IDs for a given router.
     *
     * @param routerId
     *            ID of the routerId.
     * @return Set of router IDs.
     * @throws StateAccessException
     *             Data access error.
     */
    public Set<String> getPeerRouterIds(UUID routerId)
            throws StateAccessException {
        log.debug("RouterZkDao.getPeerRouterIds entered: routerId={}", routerId);

        String path = pathBuilder.getRouterRoutersPath(routerId);
        Set<String> ids = zkDao.getChildren(path, null);

        log.debug("RouterZkDao.getPeerRouterIds exiting: path=" + path
                + " ids count=" + ids.size());
        return ids;
    }

    /**
     * Get a set of connected bridge IDs for a given router.
     *
     * @param routerId
     *            ID of the routerId.
     * @return Set of bridge IDs.
     * @throws StateAccessException
     *             Data access error.
     */
    public Set<String> getBridgeIds(UUID routerId)
            throws StateAccessException {
        String path = pathBuilder.getRouterBridgesPath(routerId);
        return zkDao.getChildren(path, null);
    }

    /**
     * Get the PeerRouterConfig object for the link between the given IDs.
     *
     * @param id
     *            Router ID
     * @param peerId
     *            Peer router ID
     * @return PeerRouterConfig object.
     * @throws StateAccessException
     *             Data access error.
     */
    public PeerRouterConfig getRouterLinkData(UUID id, UUID peerId)
            throws StateAccessException {
        log.debug("RouterZkDao.getRouterLinkData entered: id=" + id
                + ", peerId=" + peerId);

        String path = pathBuilder.getRouterRouterPath(id, peerId);
        byte[] data = zkDao.get(path);
        PeerRouterConfig config = serializer.deserializePeer(data);

        log.debug("RouterZkDao.getRouterLinkData exiting.");
        return config;
    }

    /**
     * Get the PeerRouterConfig object for the link between the given IDs.
     *
     * @param id
     *            Router ID
     * @param bridgeId
     *            Bridge ID
     * @return PeerRouterConfig object.
     * @throws StateAccessException
     *             Data access error.
     */
    public PeerRouterConfig getRouterBridgeLinkData(UUID id, UUID bridgeId)
            throws StateAccessException {
        String path = pathBuilder.getRouterBridgePath(id, bridgeId);
        byte[] data = zkDao.get(path);
        return serializer.deserializePeer(data);
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

    /**
     * Checks whether the given link exists.
     *
     * @param id
     *            Router ID
     * @param peerId
     *            Peer router ID
     * @return True if the router link exists.
     * @throws StateAccessException
     *             Data access error.
     */
    public boolean routerLinkExists(UUID id, UUID peerId)
            throws StateAccessException {
        log.debug("RouterZkDao.routerLinkExists entered: id=" + id
                + ", peerId=" + peerId);

        String path = pathBuilder.getRouterRouterPath(id, peerId);
        boolean exists = zkDao.exists(path);

        log.debug("RouterZkDao.routerLinkExists exiting: exists=" + exists);
        return exists;
    }

    /**
     * Checks whether the given link exists.
     *
     * @param routerId
     *            Router ID
     * @param bridgeId
     *            Bridge ID
     * @return True if the router to bridge link exists.
     * @throws StateAccessException
     *             Data access error.
     */
    public boolean routerBridgeLinkExists(UUID routerId, UUID bridgeId)
            throws StateAccessException {
        String path = pathBuilder.getRouterBridgePath(routerId, bridgeId);
        return zkDao.exists(path);
    }

    /**
     * Construct a new PeerRouterConfig object
     *
     * @param portId
     *            Port ID
     * @param peerPortId
     *            Peer port ID
     * @return PeerRouterConfig object
     */
    public PeerRouterConfig constructPeerRouterConfig(UUID portId,
            UUID peerPortId) {
        return new PeerRouterConfig(portId, peerPortId);
    }
}
