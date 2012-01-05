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
import com.midokura.midolman.mgmt.data.zookeeper.io.RouterSerializer;
import com.midokura.midolman.mgmt.data.zookeeper.path.PathBuilder;
import com.midokura.midolman.state.RouterZkManager;
import com.midokura.midolman.state.StateAccessException;

/**
 * Proxy class to access ZooKeeper for router data.
 *
 * @version 1.6 6 Jan 2012
 * @author Ryu Ishimoto
 */
public class RouterZkDao {

    private final static Logger log = LoggerFactory
            .getLogger(RouterZkDao.class);
    private final RouterZkManager zkDao;
    private final PathBuilder pathBuilder;
    private final RouterSerializer serializer;

    /**
     * Constructor
     *
     * @param zkDao
     *            RouterZkManager object to access ZK data.
     * @param pathBuilder
     *            PathBuilder object to get path data.
     * @param serializer
     *            RouterSerializer object.
     */
    public RouterZkDao(RouterZkManager zkDao, PathBuilder pathBuilder,
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
    public RouterMgmtConfig getData(UUID id) throws StateAccessException {
        log.debug("RouterPathDao.getData entered: id={}", id);

        String path = pathBuilder.getRouterPath(id);
        byte[] data = zkDao.get(path);
        RouterMgmtConfig config = serializer.deserialize(data);

        log.debug("RouterPathDao.getData exiting: path={}", path);
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
        log.debug("RouterPathDao.getIds entered: tenantId={}", tenantId);

        String path = pathBuilder.getTenantRoutersPath(tenantId);
        Set<String> ids = zkDao.getChildren(path, null);

        log.debug("RouterPathDao.getIds exiting: path=" + path + " ids count="
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
        log.debug("RouterPathDao.getPeerRouterIds entered: routerId={}",
                routerId);

        String path = pathBuilder.getRouterRoutersPath(routerId);
        Set<String> ids = zkDao.getChildren(path, null);

        log.debug("RouterPathDao.getPeerRouterIds exiting: path=" + path
                + " ids count=" + ids.size());
        return ids;
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
        log.debug("RouterPathDao.getRouterLinkData entered: id=" + id
                + ", peerId=" + peerId);

        String path = pathBuilder.getRouterRouterPath(id, peerId);
        byte[] data = zkDao.get(path);
        PeerRouterConfig config = serializer.deserializePeer(data);

        log.debug("RouterPathDao.getRouterLinkData exiting.");
        return config;
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
        log.debug("RouterPathDao.routerLinkExists entered: id=" + id
                + ", peerId=" + peerId);

        String path = pathBuilder.getRouterRouterPath(id, peerId);
        boolean exists = zkDao.exists(path);

        log.debug("RouterPathDao.routerLinkExists exiting: exists=" + exists);
        return exists;
    }

}
