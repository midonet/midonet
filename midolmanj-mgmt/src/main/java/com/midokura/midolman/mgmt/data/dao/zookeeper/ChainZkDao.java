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

import com.midokura.midolman.mgmt.data.dto.config.ChainMgmtConfig;
import com.midokura.midolman.mgmt.data.dto.config.ChainNameMgmtConfig;
import com.midokura.midolman.mgmt.data.zookeeper.path.PathBuilder;
import com.midokura.midolman.state.ChainZkManager;
import com.midokura.midolman.state.ChainZkManager.ChainConfig;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkConfigSerializer;
import com.midokura.midolman.state.ZkNodeEntry;

/**
 * Proxy class to access ZooKeeper for chain data.
 */
public class ChainZkDao {

    private final static Logger log = LoggerFactory.getLogger(ChainZkDao.class);
    private final ChainZkManager zkDao;
    private final PathBuilder pathBuilder;
    private final ZkConfigSerializer serializer;

    /**
     * Constructor
     *
     * @param zkDao
     *            ChainZkManager object to access ZK data.
     * @param pathBuilder
     *            PathBuilder object to get path data.
     * @param serializer
     *            ZkConfigSerializer object.
     */
    public ChainZkDao(ChainZkManager zkDao, PathBuilder pathBuilder,
            ZkConfigSerializer serializer) {
        this.zkDao = zkDao;
        this.pathBuilder = pathBuilder;
        this.serializer = serializer;
    }

    /**
     * Checks whether a chain exists with the given ID.
     *
     * @param id
     *            Chain ID
     * @return True if chain exists.
     * @throws StateAccessException
     *             Data access error.
     */
    public boolean exists(UUID id) throws StateAccessException {
        log.debug("ChainZkDao.exists entered: id={}", id);

        String path = pathBuilder.getChainPath(id);
        boolean exists = zkDao.exists(path);

        log.debug("ChainZkDao.exists exiting: exists=" + exists);
        return exists;
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
        ChainMgmtConfig config = serializer.deserialize(data,
                ChainMgmtConfig.class);

        log.debug("ChainZkDao.getMgmtData exiting: path=" + path);
        return config;
    }

    /**
     * Get the data for the given chain by name.
     *
     * @param tenantId
     *            ID of the Tenant that owns the chain.
     * @param chainName
     *            Name of the chain.
     * @return ChainNameMgmtConfig stored in ZK.
     * @throws StateAccessException
     *             Data access error.
     */
    public ChainNameMgmtConfig getNameData(String tenantId, String chainName)
            throws StateAccessException {
        if (tenantId == null || chainName == null) {
            throw new IllegalArgumentException(
                    "Neither tenant ID nor chain name may be null");
        }
        log.debug("ChainZkDao.getNameData entered: tenantId=" + tenantId
                + ", name=" + chainName);

        String path = pathBuilder.getTenantChainNamePath(tenantId, chainName);
        byte[] data = zkDao.get(path);
        ChainNameMgmtConfig config = serializer.deserialize(data,
                ChainNameMgmtConfig.class);

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
     * Get a set of chain IDs for a given tenant.
     *
     * @param tenantId
     *            ID of the tenant.
     * @return Set of chain IDs.
     * @throws StateAccessException
     *             Data access error.
     */
    public Set<String> getIds(String tenantId) throws StateAccessException {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId cannot be null");
        }
        String path = pathBuilder.getTenantChainsPath(tenantId);
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
     * @return ChainConfig object
     */
    public ChainConfig constructChainConfig(String name) {
        return new ChainConfig(name);
    }

    /**
     * Construct a new ChainMgmtConfig object.
     *
     * @param tenantId
     *            Tenant ID
     * @param name
     *            Name of the the chain
     * @return ChainConfig object
     */
    public ChainMgmtConfig constructChainMgmtConfig(String tenantId, String name) {
        return new ChainMgmtConfig(tenantId, name);
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
