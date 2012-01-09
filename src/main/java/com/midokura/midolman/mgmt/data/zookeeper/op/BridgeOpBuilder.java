/*
 * @(#)BridgeOpBuilder        1.6 12/1/6
 *
 * Copyright 2012 Midokura KK
 */
package com.midokura.midolman.mgmt.data.zookeeper.op;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.zookeeper.Op;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.data.dao.zookeeper.BridgeZkDao;
import com.midokura.midolman.mgmt.data.dto.config.BridgeMgmtConfig;
import com.midokura.midolman.mgmt.data.dto.config.BridgeNameMgmtConfig;
import com.midokura.midolman.state.BridgeZkManager.BridgeConfig;
import com.midokura.midolman.state.StateAccessException;

/**
 * Bridge Op builder.
 *
 * @version 1.6 6 Jan 2012
 * @author Ryu Ishimoto
 */
public class BridgeOpBuilder {

    private final static Logger log = LoggerFactory
            .getLogger(BridgeOpBuilder.class);
    private final BridgeOpPathBuilder pathBuilder;
    private final PortOpBuilder portOpBuilder;
    private final BridgeZkDao zkDao;

    /**
     * Constructor
     *
     * @param pathBuilder
     *            BridgeOpPathBuilder object.
     * @param portOpBuilder
     *            PortOpBuilder object.
     * @param zkDao
     *            BridgeZkDao object.
     */
    public BridgeOpBuilder(BridgeOpPathBuilder pathBuilder,
            PortOpBuilder portOpBuilder, BridgeZkDao zkDao) {
        this.pathBuilder = pathBuilder;
        this.portOpBuilder = portOpBuilder;
        this.zkDao = zkDao;
    }

    /**
     * Build list of Op objects to create a bridge
     *
     * @param id
     *            ID of the bridge
     * @param config
     *            BridgeConfig object
     * @param mgmtConfig
     *            BridgeMgmtConfig object
     * @param nameConfig
     *            BridgeNameMgmtConfig object
     * @return List of Op objects
     * @throws StateAccessException
     *             Data access error
     */
    public List<Op> buildCreate(UUID id, BridgeConfig config,
            BridgeMgmtConfig mgmtConfig, BridgeNameMgmtConfig nameConfig)
            throws StateAccessException {
        log.debug("BridgeOpBuilder.buildCreate entered: id={}", id);

        List<Op> ops = new ArrayList<Op>();

        // Create the root bridge path
        ops.add(pathBuilder.getBridgeCreateOp(id, mgmtConfig));

        // Add the bridge under tenant.
        ops.add(pathBuilder.getTenantBridgeCreateOp(mgmtConfig.tenantId, id));

        // Add the bridge name.
        ops.add(pathBuilder.getTenantBridgeNameCreateOp(mgmtConfig.tenantId,
                mgmtConfig.name, nameConfig));

        // Create Midolman data
        ops.addAll(pathBuilder.getBridgeCreateOps(id, config));

        log.debug("BridgeOpBuilder.buildCreate exiting: ops count={}",
                ops.size());
        return ops;
    }

    /**
     * Build list of Op objects to delete a bridge
     *
     * @param id
     *            ID of the port
     * @param cascade
     *            True if cascade delete
     * @return List of Op objects
     * @throws StateAccessException
     *             Data error.
     */
    public List<Op> buildDelete(UUID id, boolean cascade)
            throws StateAccessException {
        log.debug("BridgeOpBuilder.buildDelete entered: id=" + id
                + ", cascade=" + cascade);

        BridgeMgmtConfig mgmtConfig = zkDao.getMgmtData(id);

        List<Op> ops = new ArrayList<Op>();

        // Delete the Midolman side.
        if (cascade) {
            ops.addAll(pathBuilder.getBridgeDeleteOps(id));
        }

        // Delete the ports
        ops.addAll(portOpBuilder.buildBridgePortsDelete(id));

        // Delete the tenant bridge name
        ops.add(pathBuilder.getTenantBridgeNameDeleteOp(mgmtConfig.tenantId,
                mgmtConfig.name));

        // Delete the tenant bridge
        ops.add(pathBuilder.getTenantBridgeDeleteOp(mgmtConfig.tenantId, id));

        // Delete the root bridge path.
        ops.add(pathBuilder.getBridgeDeleteOp(id));

        log.debug("BridgeOpBuilder.buildDelete exiting: ops count={}",
                ops.size());
        return ops;
    }

    /**
     * Build list of Op objects to update a bridge
     *
     * @param id
     *            ID of the bridge
     * @param name
     *            Name of the bridge
     * @return Op list
     * @throws StateAccessException
     *             Data error.
     */
    public List<Op> buildUpdate(UUID id, String name)
            throws StateAccessException {
        log.debug("BridgeOpBuilder.buildUpdate entered: id=" + id + ", name="
                + name);

        List<Op> ops = new ArrayList<Op>();
        BridgeMgmtConfig config = zkDao.getMgmtData(id);
        BridgeNameMgmtConfig nameConfig = zkDao.getNameData(config.tenantId,
                config.name);

        // Remove the name of this bridge
        ops.add(pathBuilder.getTenantBridgeNameDeleteOp(config.tenantId,
                config.name));

        // Move NameConfig to the new name path.
        ops.add(pathBuilder.getTenantBridgeNameCreateOp(config.tenantId, name,
                nameConfig));

        // Update bridge
        config.name = name;
        ops.add(pathBuilder.getBridgeSetDataOp(id, config));

        log.debug("BridgeOpBuilder.buildUpdate exiting: ops count={}",
                ops.size());
        return ops;
    }
}
