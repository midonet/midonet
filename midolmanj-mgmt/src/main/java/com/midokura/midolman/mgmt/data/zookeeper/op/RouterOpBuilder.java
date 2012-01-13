/*
 * @(#)RouterOpBuilder        1.6 12/1/6
 *
 * Copyright 2012 Midokura KK
 */
package com.midokura.midolman.mgmt.data.zookeeper.op;

import java.util.List;
import java.util.UUID;

import org.apache.zookeeper.Op;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.data.dto.config.PeerRouterConfig;
import com.midokura.midolman.mgmt.data.dto.config.RouterMgmtConfig;
import com.midokura.midolman.mgmt.data.dto.config.RouterNameMgmtConfig;
import com.midokura.midolman.mgmt.data.zookeeper.io.RouterSerializer;
import com.midokura.midolman.mgmt.data.zookeeper.path.PathBuilder;
import com.midokura.midolman.mgmt.rest_api.core.ChainTable;
import com.midokura.midolman.state.RouterZkManager;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkStateSerializationException;

/**
 * Class to build Op for the router paths.
 *
 * @version 1.6 6 Jan 2012
 * @author Ryu Ishimoto
 */
public class RouterOpBuilder {

    private final static Logger log = LoggerFactory
            .getLogger(RouterOpBuilder.class);
    private final RouterSerializer serializer;
    private final PathBuilder pathBuilder;
    private final RouterZkManager zkDao;

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
    public RouterOpBuilder(RouterZkManager zkDao, PathBuilder pathBuilder,
            RouterSerializer serializer) {
        this.zkDao = zkDao;
        this.pathBuilder = pathBuilder;
        this.serializer = serializer;
    }

    /**
     * Get the router create Op object.
     *
     * @param id
     *            ID of the router.
     * @param config
     *            RouterMgmtConfig object to create.
     * @return Op for router create.
     */
    public Op getRouterCreateOp(UUID id, RouterMgmtConfig config)
            throws ZkStateSerializationException {
        log.debug("RouterOpBuilder.getRouterCreateOp entered: id={}", id);

        String path = pathBuilder.getRouterPath(id);
        byte[] data = serializer.serialize(config);
        Op op = zkDao.getPersistentCreateOp(path, data);

        log.debug("RouterOpBuilder.getRouterCreateOp exiting.");
        return op;
    }

    /**
     * Gets a list of Op objects to create a router in Midolman side.
     *
     * @param id
     *            ID of the router
     * @return List of Op objects.
     * @throws StateAccessException
     *             Data access error.
     */
    public List<Op> getRouterCreateOps(UUID id) throws StateAccessException {
        log.debug("RouterOpBuilder.getRouterCreateOps entered: id=" + id);
        List<Op> ops = zkDao.prepareRouterCreate(id);
        log.debug("RouterOpBuilder.getRouterCreateOps exiting: ops count="
                + ops.size());
        return ops;
    }

    /**
     * Get the router delete Op object.
     *
     * @param id
     *            ID of the router.
     * @return Op for router delete.
     */
    public Op getRouterDeleteOp(UUID id) {
        log.debug("RouterOpBuilder.getRouterDeleteOp entered: id={}", id);

        String path = pathBuilder.getRouterPath(id);
        Op op = zkDao.getDeleteOp(path);

        log.debug("RouterOpBuilder.getRouterDeleteOp exiting.");
        return op;
    }

    /**
     * Gets a list of Op objects to delete a Router in Midolman side.
     *
     * @param id
     *            ID of the router
     * @return List of Op objects.
     * @throws StateAccessException
     *             Data access error.
     */
    public List<Op> getRouterDeleteOps(UUID id) throws StateAccessException {
        log.debug("RouterOpBuilder.getRouterDeleteOps entered: id={}", id);

        RouterZkManager routerZkDao = zkDao;
        List<Op> ops = routerZkDao.prepareRouterDelete(id);

        log.debug("RouterOpBuilder.getRouterDeleteOps exiting: ops count={}",
                ops.size());
        return ops;
    }

    /**
     * Get the router link create Op object.
     *
     * @param id
     *            ID of the router
     * @return Op for router link create.
     */
    public Op getRouterRoutersCreateOp(UUID id) {
        log.debug("RouterOpBuilder.getRouterRoutersCreateOp entered: id={}", id);

        String path = pathBuilder.getRouterRoutersPath(id);
        Op op = zkDao.getPersistentCreateOp(path, null);

        log.debug("RouterOpBuilder.getRouterRoutersCreateOp exiting.");
        return op;
    }

    public Op getRouterRouterCreateOp(UUID id, UUID peerId,
            PeerRouterConfig config) throws ZkStateSerializationException {
        log.debug("RouterOpBuilder.getRouterRouterCreateOp entered: id=" + id
                + ", peerId=" + peerId);

        String path = pathBuilder.getRouterRouterPath(id, peerId);
        byte[] data = serializer.serialize(config);
        Op op = zkDao.getPersistentCreateOp(path, data);

        log.debug("RouterOpBuilder.getRouterRouterCreateOp exiting.");
        return op;
    }

    /**
     * Get the routers link delete Op object.
     *
     * @param id
     *            ID of the router
     * @return Op for router link delete..
     */
    public Op getRouterRoutersDeleteOp(UUID id) {
        log.debug("RouterOpBuilder.getRouterRoutersDeleteOp entered: id={}", id);

        String path = pathBuilder.getRouterRoutersPath(id);
        Op op = zkDao.getDeleteOp(path);

        log.debug("RouterOpBuilder.getRouterRoutersDeleteOp exiting.");
        return op;
    }

    /**
     * Get the router link delete Op object.
     *
     * @param id
     *            ID of the router
     * @return Op for router link delete..
     */
    public Op getRouterRouterDeleteOp(UUID id, UUID peerRouterId) {
        log.debug("RouterOpBuilder.getRouterRoutersDeleteOp entered: id=" + id
                + ", peer=" + peerRouterId);

        String path = pathBuilder.getRouterRouterPath(id, peerRouterId);
        Op op = zkDao.getDeleteOp(path);

        log.debug("RouterOpBuilder.getRouterRoutersDeleteOp exiting.");
        return op;
    }

    /**
     * Get the router update Op object.
     *
     * @param id
     *            ID of the router.
     * @param config
     *            RouterMgmtConfig object to set.
     * @return Op for router update.
     */
    public Op getRouterSetDataOp(UUID id, RouterMgmtConfig config)
            throws ZkStateSerializationException {
        log.debug("RouterOpBuilder.getRouterSetDataOp entered: id={}", id);

        String path = pathBuilder.getRouterPath(id);
        byte[] data = serializer.serialize(config);
        Op op = zkDao.getSetDataOp(path, data);

        log.debug("RouterOpBuilder.getRouterSetDataOp exiting.");
        return op;
    }

    /**
     * Get the router table chain names create Op object.
     *
     * @param id
     *            ID of the router
     * @param table
     *            ChainTable value to create.
     * @return Op for router table chain names create.
     */
    public Op getRouterTableChainNamesCreateOp(UUID id, ChainTable table) {
        log.debug("RouterOpBuilder.getRouterTableChainNamesCreateOp entered: id="
                + id + ", table=" + table);

        String path = pathBuilder.getRouterTableChainNamesPath(id, table);
        Op op = zkDao.getPersistentCreateOp(path, null);

        log.debug("RouterOpBuilder.getRouterTableChainNamesCreateOp exiting.");
        return op;
    }

    /**
     * Get the router table chain names delete Op object.
     *
     * @param id
     *            ID of the router
     * @param table
     *            ChainTable value to delete.
     * @return Op for router table chain names delete.
     */
    public Op getRouterTableChainNamesDeleteOp(UUID id, ChainTable table) {
        log.debug("RouterOpBuilder.getRouterTableChainNamesDeleteOp entered: id="
                + id + ", table=" + table);

        String path = pathBuilder.getRouterTableChainNamesPath(id, table);
        Op op = zkDao.getDeleteOp(path);

        log.debug("RouterOpBuilder.getRouterTableChainNamesDeleteOp exiting.");
        return op;
    }

    /**
     * Get the router table chains create Op object.
     *
     * @param id
     *            ID of the router
     * @param table
     *            ChainTable value to create.
     * @return Op for router table chains create.
     */
    public Op getRouterTableChainsCreateOp(UUID id, ChainTable table) {
        log.debug("RouterOpBuilder.getRouterTableChainsCreateOp entered: id="
                + id + ", table=" + table);

        String path = pathBuilder.getRouterTableChainsPath(id, table);
        Op op = zkDao.getPersistentCreateOp(path, null);

        log.debug("RouterOpBuilder.getRouterTableChainsCreateOp exiting.");
        return op;
    }

    /**
     * Get the router table chains delete Op object.
     *
     * @param id
     *            ID of the router
     * @param table
     *            ChainTable value to delete.
     * @return Op for router table chains delete.
     */
    public Op getRouterTableChainsDeleteOp(UUID id, ChainTable table) {
        log.debug("RouterOpBuilder.getRouterTableChainsDeleteOp entered: id="
                + id + ", table=" + table);

        String path = pathBuilder.getRouterTableChainsPath(id, table);
        Op op = zkDao.getDeleteOp(path);

        log.debug("RouterOpBuilder.getRouterTableChainsDeleteOp exiting.");
        return op;
    }

    /**
     * Get the router chain table create Op object.
     *
     * @param id
     *            ID of the router
     * @param table
     *            ChainTable value to create.
     * @return Op for router table create.
     */
    public Op getRouterTableCreateOp(UUID id, ChainTable table) {
        log.debug("RouterOpBuilder.getRouterTableCreateOp entered: id=" + id
                + ", table=" + table);

        String path = pathBuilder.getRouterTablePath(id, table);
        Op op = zkDao.getPersistentCreateOp(path, null);

        log.debug("RouterOpBuilder.getRouterTableCreateOp exiting.");
        return op;
    }

    /**
     * Get the router chain table delete Op object.
     *
     * @param id
     *            ID of the router
     * @param table
     *            ChainTable value to delete.
     * @return Op for router table delete.
     */
    public Op getRouterTableDeleteOp(UUID id, ChainTable table) {
        log.debug("RouterOpBuilder.getRouterTableDeleteOp entered: id=" + id
                + ", table=" + table);

        String path = pathBuilder.getRouterTablePath(id, table);
        Op op = zkDao.getDeleteOp(path);

        log.debug("RouterOpBuilder.getRouterTableDeleteOp exiting.");
        return op;
    }

    /**
     * Get the router tables create Op object.
     *
     * @param id
     *            ID of the router
     * @return Op for router tables create.
     */
    public Op getRouterTablesCreateOp(UUID id) {
        log.debug("RouterOpBuilder.getRouterTablesCreateOp entered: id={}", id);

        String path = pathBuilder.getRouterTablesPath(id);
        Op op = zkDao.getPersistentCreateOp(path, null);

        log.debug("RouterOpBuilder.getRouterTablesCreateOp exiting.");
        return op;
    }

    /**
     * Get the router tables Op object.
     *
     * @param id
     *            ID of the router
     * @return Op for router tables delete.
     */
    public Op getRouterTablesDeleteOp(UUID id) {
        log.debug("RouterOpBuilder.getRouterTablesDeleteOp entered: id={}", id);

        String path = pathBuilder.getRouterTablesPath(id);
        Op op = zkDao.getDeleteOp(path);

        log.debug("RouterOpBuilder.getRouterTablesDeleteOp exiting.");
        return op;
    }

    /**
     * Get the tenant router create Op object.
     *
     * @param tenantId
     *            ID of the tenant
     * @param id
     *            ID of the router.
     * @return Op for tenant router create.
     */
    public Op getTenantRouterCreateOp(String tenantId, UUID id) {
        log.debug("RouterOpBuilder.getTenantRouterCreateOp entered: tenantId="
                + tenantId + ", id=" + id);

        String path = pathBuilder.getTenantRouterPath(tenantId, id);
        Op op = zkDao.getPersistentCreateOp(path, null);

        log.debug("RouterOpBuilder.getTenantRouterCreateOp exiting.");
        return op;
    }

    /**
     * Get the tenant router delete Op object.
     *
     * @param tenantId
     *            ID of the tenant
     * @param id
     *            ID of the router.
     * @return Op for tenant router delete.
     */
    public Op getTenantRouterDeleteOp(String tenantId, UUID id) {
        log.debug("RouterOpBuilder.getTenantRouterDeleteOp entered: tenantId="
                + tenantId + ", id=" + id);

        String path = pathBuilder.getTenantRouterPath(tenantId, id);
        Op op = zkDao.getDeleteOp(path);

        log.debug("RouterOpBuilder.getTenantRouterDeleteOp exiting.");
        return op;
    }

    /**
     * Get the tenant router name create Op object.
     *
     * @param tenantId
     *            ID of the tenant
     * @param name
     *            name of the router
     * @param config
     *            RouterMgmtConfig object to set.
     * @return Op for tenant router name create.
     */
    public Op getTenantRouterNameCreateOp(String tenantId, String name,
            RouterNameMgmtConfig config) throws ZkStateSerializationException {
        log.debug("RouterOpBuilder.getTenantRouterNameCreateOp entered: tenantId="
                + tenantId + ", name=" + name);

        String path = pathBuilder.getTenantRouterNamePath(tenantId, name);
        byte[] data = serializer.serialize(config);
        Op op = zkDao.getPersistentCreateOp(path, data);

        log.debug("RouterOpBuilder.getTenantRouterNameCreateOp exiting.");
        return op;
    }

    /**
     * Get the tenant router name delete Op object.
     *
     * @param id
     *            ID of the tenant
     * @param name
     *            name of the router
     * @return Op for tenant router name delete.
     */
    public Op getTenantRouterNameDeleteOp(String tenantId, String name) {
        log.debug("RouterOpBuilder.getTenantRouterNameDeleteOp entered: tenantId="
                + tenantId + ", name=" + name);

        String path = pathBuilder.getTenantRouterNamePath(tenantId, name);
        Op op = zkDao.getDeleteOp(path);

        log.debug("RouterOpBuilder.getTenantRouterNameDeleteOp exiting.");
        return op;
    }

}
