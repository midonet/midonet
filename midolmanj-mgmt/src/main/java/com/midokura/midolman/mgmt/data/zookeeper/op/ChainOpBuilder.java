/*
 * @(#)ChainOpBuilder        1.6 11/12/25
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
public class ChainOpBuilder {

    private final static Logger log = LoggerFactory
            .getLogger(ChainOpBuilder.class);
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
    public ChainOpBuilder(ChainZkManager zkDao, PathBuilder pathBuilder,
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
        if (id == null || config == null) {
            throw new IllegalArgumentException("ID and config cannot be null");
        }
        log.debug("ChainOpBuilder.getChainCreateOp entered: id={}", id);

        String path = pathBuilder.getChainPath(id);
        byte[] data = serializer.serialize(config);
        Op op = zkDao.getPersistentCreateOp(path, data);

        log.debug("ChainOpBuilder.getChainCreateOp exiting.");
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
        if (id == null || config == null) {
            throw new IllegalArgumentException("ID and config cannot be null");
        }
        ZkNodeEntry<UUID, ChainConfig> chainNode =
                new ZkNodeEntry<UUID, ChainConfig>(id, config);
        List<Op> ops = zkDao.prepareChainCreate(chainNode);
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
        if (id == null) {
            throw new IllegalArgumentException("ID cannot be null");
        }
        log.debug("ChainOpBuilder.getChainDeleteOp entered: id={}", id);

        String path = pathBuilder.getChainPath(id);
        Op op = zkDao.getDeleteOp(path);

        log.debug("ChainOpBuilder.getChainDeleteOp exiting.");
        return op;
    }

    /**
     * Gets a list of Op objects to delete a Chain in Midolman side. This is a
     * hack until refactoring is finished in Midolman side. When the Midolman
     * side is refactored, we no longer need this method, and instead let the
     * ChainOpService chain the handlers of Midolman Chain OpBuilders
     * appropriately.
     *
     * @param chainId
     *            ID of the chain
     * @return List of Op objects.
     * @throws StateAccessException
     *             Data access error.
     */
    public List<Op> getChainDeleteOps(UUID chainId) throws StateAccessException {
        if (chainId == null) {
            throw new IllegalArgumentException("ID cannot be null");
        }
        return zkDao.prepareChainDelete(chainId);
    }

    /**
     * Get the tenant chain create Op object.
     *
     * @param tenantId
     *            ID of the tenant
     * @param chainId
     *            ID of the chain.
     * @return Op for tenant chain create.
     */
    public Op getTenantChainCreateOp(String tenantId, UUID chainId) {
        return zkDao.getPersistentCreateOp(
                pathBuilder.getTenantChainPath(tenantId, chainId), null);
    }

    /**
     * Get the tenant chain delete Op object.
     *
     * @param tenantId
     *            ID of the tenant
     * @param chainId
     *            ID of the chain.
     * @return Op for tenant chain delete.
     */
    public Op getTenantChainDeleteOp(String tenantId, UUID chainId) {
        if (tenantId == null || chainId == null) {
            throw new IllegalArgumentException(
                    "tenantId and chainId cannot be null");
        }
        String path = pathBuilder.getTenantChainPath(tenantId, chainId);
        return zkDao.getDeleteOp(path);
    }

    /**
     * Get the tenant chain name create Op object.
     *
     * @param tenantId
     *            ID of the tenant
     * @param chainName
     *            Name of the chain.
     * @param config
     *            ChainNameMgmtConfig object to add to the path.
     * @return Op for tenant chain name create.
     */
    public Op getTenantChainNameCreateOp(String tenantId, String chainName,
                                         ChainNameMgmtConfig config)
            throws ZkStateSerializationException {
        if (tenantId == null || chainName == null || config == null) {
            throw new IllegalArgumentException(
                    "tenantId, config, and chainName cannot be null");
        }
        String path = pathBuilder.getTenantChainNamePath(tenantId, chainName);
        byte[] data = serializer.serialize(config);
        return zkDao.getPersistentCreateOp(path, data);
    }

    /**
     * Get the tenant chain name delete Op object.
     *
     * @param tenantId
     *            ID of the tenant.
     * @param chainName
     *            Name of the chain.
     * @return Op for tenant chain name delete.
     */
    public Op getTenantChainNameDeleteOp(String tenantId, String chainName) {
        if (tenantId == null || chainName == null) {
            throw new IllegalArgumentException(
                    "tenantId and chainName cannot be null");
        }
        String path = pathBuilder.getTenantChainNamePath(tenantId, chainName);
        return zkDao.getDeleteOp(path);
    }
}
