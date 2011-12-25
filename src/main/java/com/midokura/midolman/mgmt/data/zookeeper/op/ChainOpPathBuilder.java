/*
 * @(#)ChainOpPathBuilder        1.6 11/12/25
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.zookeeper.op;

import java.util.List;
import java.util.UUID;

import org.apache.zookeeper.Op;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.data.dto.config.ChainMgmtConfig;
import com.midokura.midolman.mgmt.data.dto.config.ChainNameMgmtConfig;
import com.midokura.midolman.mgmt.data.zookeeper.io.ChainSerializer;
import com.midokura.midolman.mgmt.data.zookeeper.path.PathBuilder;
import com.midokura.midolman.mgmt.rest_api.core.ChainTable;
import com.midokura.midolman.state.ChainZkManager;
import com.midokura.midolman.state.ChainZkManager.ChainConfig;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkNodeEntry;
import com.midokura.midolman.state.ZkStateSerializationException;

/**
 * Class to build Op for the chain paths.
 *
 * @version 1.6 25 Dec 2011
 * @author Ryu Ishimoto
 */
public class ChainOpPathBuilder {

    private final static Logger log = LoggerFactory
            .getLogger(ChainOpPathBuilder.class);
    private final PathBuilder pathBuilder;
    private final ChainSerializer serializer;
    private final ChainZkManager zkDao;

    /**
     * Constructor
     *
     * @param zkDao
     *            ChainZkManager object
     * @param pathBuilder
     *            PathBuilder object
     * @param serializer
     *            ChainSerializer object
     */
    public ChainOpPathBuilder(ChainZkManager zkDao, PathBuilder pathBuilder,
            ChainSerializer serializer) {
        this.zkDao = zkDao;
        this.pathBuilder = pathBuilder;
        this.serializer = serializer;
    }

    /**
     * Get the chain create Op object.
     *
     * @param id
     *            ID of the chain.
     * @param config
     *            ChainMgmtConfig object to add to the path.
     * @return Op for chain create.
     */
    public Op getChainCreateOp(UUID id, ChainMgmtConfig config)
            throws ZkStateSerializationException {
        log.debug("ChainOpPathBuilder.getChainCreateOp entered: id={}", id);

        String path = pathBuilder.getChainPath(id);
        byte[] data = serializer.serialize(config);
        Op op = zkDao.getPersistentCreateOp(path, data);

        log.debug("ChainOpPathBuilder.getChainCreateOp exiting.");
        return op;
    }

    /**
     * Gets a list of Op objects to create a chain in Midolman side. This is a
     * hack until refactoring is finished in Midolman side. When the Midolman
     * side is refactored, we no longer need this method, and instead let the
     * ChainOpService chain the handlers of Midolman Chain OpBuilders
     * appropriately.
     *
     * @param id
     *            ID of the chain
     * @param config
     *            ChainConfig object to create.
     * @return List of Op objects.
     * @throws StateAccessException
     *             Data access error.
     */
    public List<Op> getChainCreateOps(UUID id, ChainConfig config)
            throws StateAccessException {
        log.debug("ChainOpPathBuilder.getChainCreateOps entered: id=" + id
                + ", name=" + config.name + ", routerId=" + config.routerId);

        ZkNodeEntry<UUID, ChainConfig> chainNode = new ZkNodeEntry<UUID, ChainConfig>(
                id, config);
        List<Op> ops = zkDao.prepareChainCreate(chainNode);

        log.debug("ChainOpPathBuilder.getChainCreateOps exiting: ops count="
                + ops.size());
        return ops;
    }

    /**
     * Get the chain delete Op object.
     *
     * @param id
     *            ID of the chain.
     * @return Op for chain delete.
     */
    public Op getChainDeleteOp(UUID id) {
        log.debug("ChainOpPathBuilder.getChainDeleteOp entered: id={}", id);

        String path = pathBuilder.getChainPath(id);
        Op op = zkDao.getDeleteOp(path);

        log.debug("ChainOpPathBuilder.getChainDeleteOp exiting.");
        return op;
    }

    /**
     * Gets a list of Op objects to delete a Chain in Midolman side. This is a
     * hack until refactoring is finished in Midolman side. When the Midolman
     * side is refactored, we no longer need this method, and instead let the
     * ChainOpService chain the handlers of Midolman Chain OpBuilders
     * appropriately.
     *
     * @param id
     *            ID of the chain
     * @return List of Op objects.
     * @throws StateAccessException
     *             Data access error.
     */
    public List<Op> getChainDeleteOps(UUID id) throws StateAccessException {
        log.debug("ChainOpPathBuilder.getChainDeleteOps entered: id={}", id);

        List<Op> ops = zkDao.prepareChainDelete(id);

        log.debug("ChainOpPathBuilder.getChainDeleteOps exiting: ops count={}",
                ops.size());
        return ops;
    }

    /**
     * Get the tenant router create Op object.
     *
     * @param routerId
     *            ID of the router
     * @param table
     *            ChainTable value.
     * @param id
     *            ID of the chain.
     * @return Op for router table chain create.
     */
    public Op getRouterTableChainCreateOp(UUID routerId, ChainTable table,
            UUID id) {
        log.debug("ChainOpPathBuilder.getRouterTableChainCreateOp entered: routerId="
                + routerId + ", table=" + table + ", id=" + id);

        String path = pathBuilder.getRouterTableChainPath(routerId, table, id);
        Op op = zkDao.getPersistentCreateOp(path, null);

        log.debug("ChainOpPathBuilder.getRouterTableChainCreateOp exiting.");
        return op;
    }

    /**
     * Get the tenant router delete Op object.
     *
     * @param routerId
     *            ID of the router
     * @param table
     *            ChainTable value.
     * @param id
     *            ID of the chain.
     * @return Op for router table chain delete.
     */
    public Op getRouterTableChainDeleteOp(UUID routerId, ChainTable table,
            UUID id) {
        log.debug("ChainOpPathBuilder.getRouterTableChainDeleteOp entered: routerId="
                + routerId + ", table=" + table + ",id=" + id);

        String path = pathBuilder.getRouterTableChainPath(routerId, table, id);
        Op op = zkDao.getDeleteOp(path);

        log.debug("ChainOpPathBuilder.getRouterTableChainDeleteOp exiting.");
        return op;
    }

    /**
     * Get the router table chain name create Op object.
     *
     * @param id
     *            ID of the router
     * @param table
     *            ChainTable value.
     * @param name
     *            Name of the chain.
     * @param config
     *            ChainNameMgmtConfig object to add to the path.
     * @return Op for router table chain name create.
     */
    public Op getRouterTableChainNameCreateOp(UUID routerId, ChainTable table,
            String name, ChainNameMgmtConfig config)
            throws ZkStateSerializationException {
        log.debug("ChainOpPathBuilder.getRouterTableChainNameCreateOp entered: routerId="
                + routerId + ", table=" + table + ", name=" + name);

        String path = pathBuilder.getRouterTableChainNamePath(routerId, table,
                name);
        byte[] data = serializer.serialize(config);
        Op op = zkDao.getPersistentCreateOp(path, data);

        log.debug("ChainOpPathBuilder.getRouterTableChainNameCreateOp exiting.");
        return op;
    }

    /**
     * Get the router table chain name delete Op object.
     *
     * @param id
     *            ID of the router
     * @param table
     *            ChainTable value.
     * @param name
     *            Name of the chain.
     * @return Op for router table chain name delete.
     */
    public Op getRouterTableChainNameDeleteOp(UUID routerId, ChainTable table,
            String name) {
        log.debug("ChainOpPathBuilder.getRouterTableChainNameDeleteOp entered: routerId="
                + routerId + ", table=" + table + ", name=" + name);

        String path = pathBuilder.getRouterTableChainNamePath(routerId, table,
                name);
        Op op = zkDao.getDeleteOp(path);

        log.debug("ChainOpPathBuilder.getRouterTableChainNameDeleteOp exiting.");
        return op;
    }
}
