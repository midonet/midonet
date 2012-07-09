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

import com.midokura.midolman.mgmt.data.dao.zookeeper.RouterZkDao;
import com.midokura.midolman.mgmt.data.dto.Router;
import com.midokura.midolman.mgmt.data.dto.config.RouterMgmtConfig;
import com.midokura.midolman.mgmt.data.dto.config.RouterNameMgmtConfig;
import com.midokura.midolman.state.RouterZkManager.RouterConfig;
import com.midokura.midolman.state.StateAccessException;

/**
 * Router Op service.
 */
public class RouterOpService {

    private final static Logger log = LoggerFactory
            .getLogger(RouterOpService.class);
    private final RouterOpBuilder opBuilder;
    private final RouterZkDao zkDao;

    /**
     * Constructor
     *
     * @param opBuilder
     *            RouterOpBuilder object.
     * @param zkDao
     *            RouterZkDao object.
     */
    public RouterOpService(RouterOpBuilder opBuilder, RouterZkDao zkDao) {
        this.opBuilder = opBuilder;
        this.zkDao = zkDao;
    }

    /**
     * Build list of Op objects to create a router
     *
     * @param id
     *            Router Id
     * @param mgmtConfig
     *            RouterMgmtConfig object
     * @param nameConfig
     *            RouterNameMgmtConfig object
     * @return List of Op objects
     * @throws StateAccessException
     *             Data access error
     */
    public List<Op> buildCreate(UUID id, RouterConfig config,
            RouterMgmtConfig mgmtConfig, RouterNameMgmtConfig nameConfig)
            throws StateAccessException {

        List<Op> ops = new ArrayList<Op>();

        ops.addAll(opBuilder.getRouterCreateOps(id, config));
        ops.add(opBuilder.getRouterCreateOp(id, mgmtConfig));

        // tenant
        ops.add(opBuilder.getTenantRouterCreateOp(mgmtConfig.tenantId, id));

        // name
        ops.add(opBuilder.getTenantRouterNameCreateOp(mgmtConfig.tenantId,
                mgmtConfig.name, nameConfig));

        return ops;
    }

    /**
     * Build list of Op objects to delete a router
     *
     * @param id
     *            Router ID to delete
     * @param cascade
     *            Delete midolman data if set to true.
     * @return List of Op objects
     * @throws StateAccessException
     *             Data access error
     */
    public List<Op> buildDelete(UUID id, boolean cascade)
            throws StateAccessException {
        log.debug("RouterOpService.buildDelete entered: id=" + id
                + ", cascade=" + cascade);

        RouterMgmtConfig mgmtConfig = zkDao.getMgmtData(id);

        List<Op> ops = new ArrayList<Op>();

        // Midolman
        if (cascade) {
            ops.addAll(opBuilder.getRouterDeleteOps(id));
        }

        // name
        ops.add(opBuilder.getTenantRouterNameDeleteOp(mgmtConfig.tenantId,
                mgmtConfig.name));

        // tenant
        ops.add(opBuilder.getTenantRouterDeleteOp(mgmtConfig.tenantId, id));

        // root
        ops.add(opBuilder.getRouterDeleteOp(id));

        log.debug("RouterOpService.buildDelete exiting: ops count={}",
                ops.size());
        return ops;
    }

    /**
     * Build list of Op objects to update a router
     *
     * @param router
     *            Router DTO
     * @return List of Op objects
     * @throws StateAccessException
     *             Data access error
     */
    public List<Op> buildUpdate(Router router) throws StateAccessException {
        UUID id = router.getId();
        String name = router.getName();
        log.debug("RouterOpService.buildUpdate entered: id=" + id + ",name="
                + name);

        RouterMgmtConfig config = zkDao.getMgmtData(id);
        RouterNameMgmtConfig nameConfig = zkDao.getNameData(config.tenantId,
                config.name);

        List<Op> ops = new ArrayList<Op>();

        // Remove the name of this router
        ops.add(opBuilder.getTenantRouterNameDeleteOp(config.tenantId,
                config.name));

        // Add the new name.
        ops.add(opBuilder.getTenantRouterNameCreateOp(config.tenantId, name,
                nameConfig));

        // Update router
        config.name = name;
        ops.add(opBuilder.getRouterSetDataOp(id, config));

        // Update the midolman state
        Op op = opBuilder.getRouterUpdateOp(id, router.toConfig());
        if (null != op)
            ops.add(op);

        log.debug("RouterOpService.buildUpdate exiting: ops count={}",
                ops.size());
        return ops;
    }

    /**
     * Build operations to delete all routers for a tenant.
     *
     * @param tenantId
     *            ID of the tenant
     * @return Op list
     * @throws StateAccessException
     *             Data error
     */
    public List<Op> buildTenantRoutersDelete(String tenantId)
            throws StateAccessException {
        log.debug(
                "RouterOpService.buildTenantRoutersDelete entered: tenantId={}",
                tenantId);

        Set<String> ids = zkDao.getIds(tenantId);
        List<Op> ops = new ArrayList<Op>();
        for (String id : ids) {
            ops.addAll(buildDelete(UUID.fromString(id), true));
        }

        log.debug(
                "RouterOpService.buildTenantRoutersDelete exiting: ops count={}",
                ops.size());
        return ops;
    }
}
