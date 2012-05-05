/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.zookeeper.op;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.zookeeper.Op;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.data.dao.zookeeper.BridgeZkDao;
import com.midokura.midolman.mgmt.data.dto.Bridge;
import com.midokura.midolman.mgmt.data.dto.config.BridgeMgmtConfig;
import com.midokura.midolman.mgmt.data.dto.config.BridgeNameMgmtConfig;
import com.midokura.midolman.state.BridgeZkManager.BridgeConfig;
import com.midokura.midolman.state.StateAccessException;

/**
 * Bridge Op service.
 */
public class BridgeOpService {

    private final static Logger log = LoggerFactory
            .getLogger(BridgeOpService.class);
    private final BridgeOpBuilder opBuilder;
    private final PortOpService portOpService;
    private final BridgeZkDao zkDao;

    /**
     * Constructor
     *
     * @param opBuilder
     *            BridgeOpBuilder object.
     * @param portOpService
     *            PortOpService object.
     * @param zkDao
     *            BridgeZkDao object.
     */
    public BridgeOpService(BridgeOpBuilder opBuilder,
            PortOpService portOpService, BridgeZkDao zkDao) {
        this.opBuilder = opBuilder;
        this.portOpService = portOpService;
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

        // Create Midolman data
        ops.addAll(opBuilder.getBridgeCreateOps(id, config));

        // Create the root bridge path
        ops.add(opBuilder.getBridgeCreateOp(id, mgmtConfig));

        // links
        ops.add(opBuilder.getBridgeRoutersCreateOp(id));

        // Add the bridge under tenant.
        ops.add(opBuilder.getTenantBridgeCreateOp(mgmtConfig.tenantId, id));

        // Add the bridge name.
        ops.add(opBuilder.getTenantBridgeNameCreateOp(mgmtConfig.tenantId,
                mgmtConfig.name, nameConfig));

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
            ops.addAll(opBuilder.getBridgeDeleteOps(id));
        }

        // Delete the ports
        ops.addAll(portOpService.buildBridgePortsDelete(id));

        // Delete the tenant bridge name
        ops.add(opBuilder.getTenantBridgeNameDeleteOp(mgmtConfig.tenantId,
                mgmtConfig.name));

        // Delete the tenant bridge
        ops.add(opBuilder.getTenantBridgeDeleteOp(mgmtConfig.tenantId, id));

        // links
        ops.add(opBuilder.getBridgeRoutersDeleteOp(id));

        // Delete the root bridge path.
        ops.add(opBuilder.getBridgeDeleteOp(id));

        log.debug("BridgeOpBuilder.buildDelete exiting: ops count={}",
                ops.size());
        return ops;
    }

    /**
     * Build list of Op objects to update a bridge
     *
     * @param bridge
     *            BridgeConfig of the bridge to be updated.
     * @return Op list
     * @throws StateAccessException
     *             Data error.
     */
    public List<Op> buildUpdate(Bridge bridge)
            throws StateAccessException {
        UUID id = bridge.getId();
        String name = bridge.getName();
        log.debug("BridgeOpBuilder.buildUpdate entered: id=" + id + ", name="
                + name);

        List<Op> ops = new ArrayList<Op>();
        BridgeMgmtConfig config = zkDao.getMgmtData(id);
        BridgeNameMgmtConfig nameConfig = zkDao.getNameData(config.tenantId,
                config.name);

        // Remove the name of this bridge
        ops.add(opBuilder.getTenantBridgeNameDeleteOp(config.tenantId,
                config.name));

        // Move NameConfig to the new name path.
        ops.add(opBuilder.getTenantBridgeNameCreateOp(config.tenantId, name,
                nameConfig));

        // Update bridge
        config.name = name;
        ops.add(opBuilder.getBridgeSetDataOp(id, config));

        // Update the midolman data
        ops.add(opBuilder.getBridgeUpdateOp(id, bridge.toConfig()));

        log.debug("BridgeOpBuilder.buildUpdate exiting: ops count={}",
                ops.size());
        return ops;
    }

    /**
     * Build operations to delete all bridges for a tenant.
     *
     * @param tenantId
     *            ID of the tenant
     * @return Op list
     * @throws StateAccessException
     *             Data error
     */
    public List<Op> buildTenantBridgesDelete(String tenantId)
            throws StateAccessException {
        log.debug(
                "BridgeOpBuilder.buildTenantBridgesDelete entered: tenantId={}",
                tenantId);

        Set<String> ids = zkDao.getIds(tenantId);
        List<Op> ops = new ArrayList<Op>();
        for (String id : ids) {
            ops.addAll(buildDelete(UUID.fromString(id), true));
        }

        log.debug(
                "BridgeOpBuilder.buildTenantBridgesDelete exiting: ops count={}",
                ops.size());
        return ops;
    }

}
