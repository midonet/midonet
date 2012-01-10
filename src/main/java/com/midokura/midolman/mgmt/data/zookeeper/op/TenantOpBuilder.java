/*
 * @(#)TenantOpBuilder        1.6 12/1/8
 *
 * Copyright 2012 Midokura KK
 */
package com.midokura.midolman.mgmt.data.zookeeper.op;

import java.util.ArrayList;
import java.util.List;

import org.apache.zookeeper.Op;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.data.dao.zookeeper.TenantZkDao;
import com.midokura.midolman.state.NoStatePathException;
import com.midokura.midolman.state.StateAccessException;

/**
 * Tenant Op builder.
 *
 * @version 1.6 8 Jan 2012
 * @author Ryu Ishimoto
 */
public class TenantOpBuilder {

    private final static Logger log = LoggerFactory
            .getLogger(TenantOpBuilder.class);
    private final TenantZkDao zkDao;
    private final TenantOpPathBuilder pathBuilder;
    private final BridgeOpBuilder bridgeOpBuilder;
    private final RouterOpBuilder routerOpBuilder;

    /**
     * Constructor
     *
     * @param pathBuilder
     *            TenantOpPathBuilder object
     * @param bridgeOpBuilder
     *            BridgeOpBuilder object
     * @param routerOpBuilder
     *            RouterOpBuilder object
     * @param zkDao
     *            Tenant DAO.
     */
    public TenantOpBuilder(TenantOpPathBuilder pathBuilder,
            BridgeOpBuilder bridgeOpBuilder, RouterOpBuilder routerOpBuilder,
            TenantZkDao zkDao) {
        this.pathBuilder = pathBuilder;
        this.bridgeOpBuilder = bridgeOpBuilder;
        this.routerOpBuilder = routerOpBuilder;
        this.zkDao = zkDao;
    }

    /**
     * Build list of Op objects to create a tenant
     *
     * @param id
     *            Tenant ID
     * @return List of Op objects
     * @throws StateAccessException
     *             Data access error
     */
    public List<Op> buildCreate(String id) throws StateAccessException {
        log.debug("TenantOpBuilder.buildCreate entered: id={}", id);

        List<Op> ops = new ArrayList<Op>();
        ops.add(pathBuilder.getTenantCreateOp(id));
        ops.add(pathBuilder.getTenantBridgesCreateOp(id));
        ops.add(pathBuilder.getTenantRoutersCreateOp(id));
        ops.add(pathBuilder.getTenantBridgeNamesCreateOp(id));
        ops.add(pathBuilder.getTenantRouterNamesCreateOp(id));

        log.debug("TenantOpBuilder.buildCreate exiting: ops count={}",
                ops.size());
        return ops;
    }

    /**
     * Build list of Op objects to delete a tenant
     *
     * @param id
     *            Tenant ID
     * @return List of Op objects
     * @throws StateAccessException
     *             Data access error
     */
    public List<Op> buildDelete(String id) throws StateAccessException {
        log.debug("TenantOpBuilder.buildDelete entered: id={}", id);

        if (!zkDao.exists(id)) {
            throw new NoStatePathException("Tenant " + id + " does not exist");
        }

        List<Op> ops = new ArrayList<Op>();

        ops.addAll(routerOpBuilder.buildTenantRoutersDelete(id));
        ops.addAll(bridgeOpBuilder.buildTenantBridgesDelete(id));

        ops.add(pathBuilder.getTenantRouterNamesDeleteOp(id));
        ops.add(pathBuilder.getTenantBridgeNamesDeleteOp(id));
        ops.add(pathBuilder.getTenantRoutersDeleteOp(id));
        ops.add(pathBuilder.getTenantBridgesDeleteOp(id));
        ops.add(pathBuilder.getTenantDeleteOp(id));

        log.debug("TenantOpBuilder.buildDelete exiting: ops count={}",
                ops.size());
        return ops;
    }
}
