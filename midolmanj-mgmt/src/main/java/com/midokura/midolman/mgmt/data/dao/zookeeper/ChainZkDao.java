/*
 * @(#)ChainZkDao        1.6 11/12/25
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dao.zookeeper;

import java.util.List;
import java.util.Set;
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

/**
 * Proxy class to access ZooKeeper for chain data.
 *
 * @version 1.6 25 Dec 2011
 * @author Ryu Ishimoto
 */
public class ChainZkDao {

    private final static Logger log = LoggerFactory.getLogger(ChainZkDao.class);
    private final ChainZkManager zkDao;
    private final PathBuilder pathBuilder;
    private final ChainSerializer serializer;

    /**
     * Constructor
     *
     * @param zkDao
     *            ChainZkManager object to access ZK data.
     * @param pathBuilder
     *            PathBuilder object to get path data.
     * @param serializer
     *            ChainSerializer object.
     */
    public ChainZkDao(ChainZkManager zkDao, PathBuilder pathBuilder,
            ChainSerializer serializer) {
        this.zkDao = zkDao;
        this.pathBuilder = pathBuilder;
        this.serializer = serializer;
    }

    /**
     * Get the data for the given chain.
     *
     * @param id
     *            ID of the chain.
     * @return ChainMgmtConfig stored in ZK.
     * @throws StateAccessException
     *             Data access error.
     */
    public ChainMgmtConfig getMgmtData(UUID id) throws StateAccessException {
        if (id == null) {
            throw new IllegalArgumentException("ID cannot be null");
        }
        log.debug("ChainZkDao.getMgmtData entered: id={}", id);

        String path = pathBuilder.getChainPath(id);
        byte[] data = zkDao.get(path);
        ChainMgmtConfig config = serializer.deserialize(data);

        log.debug("ChainZkDao.getMgmtData exiting: path=" + path);
        return config;
    }

    /**
     * Get the name data for the given chain.
     *
     * @param id
     *            ID of the chain.
     * @return ChainNameMgmtConfig stored in ZK.
     * @throws StateAccessException
     *             Data access error.
     */
    public ChainNameMgmtConfig getNameData(UUID routerId, ChainTable table,
            String name) throws StateAccessException {
        if (routerId == null || table == null || name == null) {
            throw new IllegalArgumentException(
                    "routerId, table and name cannot be null");
        }
        log.debug("ChainZkDao.getNameData entered: routerId=" + routerId
                + ", table=" + table + ", name=" + name);

        String path = pathBuilder.getRouterTableChainNamePath(routerId, table,
                name.toLowerCase());
        byte[] data = zkDao.get(path);
        ChainNameMgmtConfig config = serializer.deserializeName(data);

        log.debug("ChainZkDao.getNameData exiting: path=" + path);
        return config;
    }

    /**
     * Get the Midolman config data for Chain.
     *
     * @param id
     *            Chain ID
     * @return ChainConfig object.
     * @throws StateAccessException
     *             Data access error.
     */
    public ChainConfig getData(UUID id) throws StateAccessException {
        if (id == null) {
            throw new IllegalArgumentException("ID cannot be null");
        }
        log.debug("ChainZkDao.getData entered: id=" + id);

        ZkNodeEntry<UUID, ChainConfig> config = zkDao.get(id);

        log.debug("ChainZkDao.getData exiting");
        return config.value;
    }

    /**
     * Get a set of chain IDs for a given router's table.
     *
     * @param routerId
     *            ID of the router.
     * @param table
     *            ChainTable value.
     * @return Set of chain IDs.
     * @throws StateAccessException
     *             Data access error.
     */
    public Set<String> getIds(UUID routerId, ChainTable table)
            throws StateAccessException {
        if (routerId == null || table == null) {
            throw new IllegalArgumentException(
                    "routerId and table cannot be null");
        }
        log.debug("ChainZkDao.getIds entered: routerId=" + routerId
                + ", table=" + table);
        String path = pathBuilder.getRouterTableChainsPath(routerId, table);
        Set<String> ids = zkDao.getChildren(path, null);

        log.debug("ChainZkDao.getIds exiting: path=" + path + " ids count="
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
        if (ops == null) {
            throw new IllegalArgumentException("ops cannot be null");
        }
        log.debug("ChainZkDao.multi entered: ops count={}", ops.size());
        zkDao.multi(ops);
        log.debug("ChainZkDao.multi exiting.");
    }

    /**
     * Construct a new ChainConfig object.
     *
     * @param name
     *            Name of the the chain
     * @param routerId
     *            Router ID
     * @return ChainConfig object
     */
    public ChainConfig constructChainConfig(String name, UUID routerId) {
        return new ChainConfig(name, routerId);
    }

    /**
     * Construct a new ChainMgmtConfig object.
     *
     * @param table
     *            ChainTable object
     * @return ChainMgmtConfig object
     */
    public ChainMgmtConfig constructChainMgmtConfig(ChainTable table) {
        return new ChainMgmtConfig(table);
    }

    /**
     * Construct a new ChainNameMgmtConfig object.
     *
     * @param id
     *            ID of the chain
     * @return ChainNameMgmtConfig object
     */
    public ChainNameMgmtConfig constructChainNameMgmtConfig(UUID id) {
        return new ChainNameMgmtConfig(id);
    }

}
