/*
 * @(#)RouterOpService        1.6 12/1/6
 *
 * Copyright 2012 Midokura KK
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
import com.midokura.midolman.mgmt.data.dto.config.PeerRouterConfig;
import com.midokura.midolman.mgmt.data.dto.config.RouterMgmtConfig;
import com.midokura.midolman.mgmt.data.dto.config.RouterNameMgmtConfig;
import com.midokura.midolman.mgmt.rest_api.core.ChainTable;
import com.midokura.midolman.state.PortConfig;
import com.midokura.midolman.state.StateAccessException;

/**
 * Router Op service.
 *
 * @version 1.6 6 Jan 2012
 * @author Ryu Ishimoto
 */
public class RouterOpService {

    private final static Logger log = LoggerFactory
            .getLogger(RouterOpService.class);
    private final RouterOpBuilder opBuilder;
    private final PortOpService portOpService;
    private final ChainOpService chainOpService;
    private final RouterZkDao zkDao;

    /**
     * Constructor
     *
     * @param opBuilder
     *            RouterOpBuilder object.
     * @param chainOpService
     *            ChainOpService object
     * @param portOpService
     *            PortOpService object.
     * @param zkDao
     *            RouterZkDao object.
     */
    public RouterOpService(RouterOpBuilder opBuilder,
            ChainOpService chainOpService, PortOpService portOpService,
            RouterZkDao zkDao) {
        this.opBuilder = opBuilder;
        this.chainOpService = chainOpService;
        this.portOpService = portOpService;
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
    public List<Op> buildCreate(UUID id, RouterMgmtConfig mgmtConfig,
            RouterNameMgmtConfig nameConfig) throws StateAccessException {

        List<Op> ops = new ArrayList<Op>();

        ops.add(opBuilder.getRouterCreateOp(id, mgmtConfig));

        // link
        ops.add(opBuilder.getRouterRoutersCreateOp(id));

        // tables
        ops.add(opBuilder.getRouterTablesCreateOp(id));
        for (ChainTable chainTable : ChainTable.class.getEnumConstants()) {

            // table
            ops.add(opBuilder.getRouterTableCreateOp(id, chainTable));

            // chains
            ops.add(opBuilder.getRouterTableChainsCreateOp(id, chainTable));

            // chain names
            ops.add(opBuilder
                    .getRouterTableChainNamesCreateOp(id, chainTable));

            // Build the actual chains
            ops.addAll(chainOpService.buildBuiltInChains(id, chainTable));
        }

        // tenant
        ops.add(opBuilder.getTenantRouterCreateOp(mgmtConfig.tenantId, id));

        // name
        ops.add(opBuilder.getTenantRouterNameCreateOp(mgmtConfig.tenantId,
                mgmtConfig.name, nameConfig));

        ops.addAll(opBuilder.getRouterCreateOps(id));

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

        // Ports
        ops.addAll(portOpService.buildRouterPortsDelete(id));

        // name
        ops.add(opBuilder.getTenantRouterNameDeleteOp(mgmtConfig.tenantId,
                mgmtConfig.name));

        // tenant
        ops.add(opBuilder.getTenantRouterDeleteOp(mgmtConfig.tenantId, id));

        // tables
        for (ChainTable chainTable : ChainTable.class.getEnumConstants()) {

            // Delete all the chains for this table
            ops.addAll(chainOpService.buildDeleteRouterChains(id, chainTable));

            // chain names
            ops.add(opBuilder
                    .getRouterTableChainNamesDeleteOp(id, chainTable));

            // chains
            ops.add(opBuilder.getRouterTableChainsDeleteOp(id, chainTable));

            // table
            ops.add(opBuilder.getRouterTableDeleteOp(id, chainTable));

        }

        // tables
        ops.add(opBuilder.getRouterTablesDeleteOp(id));

        // link
        ops.add(opBuilder.getRouterRoutersDeleteOp(id));

        // root
        ops.add(opBuilder.getRouterDeleteOp(id));

        log.debug("RouterOpService.buildDelete exiting: ops count={}",
                ops.size());
        return ops;
    }

    /**
     * Build list of Op objects to update a router
     *
     * @param id
     *            ID of the router
     * @param name
     *            Name of the router
     * @return List of Op objects
     * @throws StateAccessException
     *             Data access error
     */
    public List<Op> buildUpdate(UUID id, String name)
            throws StateAccessException {
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

        log.debug("RouterOpService.buildUpdate exiting: ops count={}",
                ops.size());
        return ops;
    }

    /**
     * Build list of Op objects to link routers
     *
     * @param ID
     *            Port ID
     * @param config
     *            PortConfig object
     * @param peerPortId
     *            Peer port ID
     * @param peerConfig
     *            Peer PortConfig object
     *
     * @return List of Op objects
     * @throws StateAccessException
     *             Data access error
     */
    public List<Op> buildLink(UUID portId, PortConfig config, UUID peerPortId,
            PortConfig peerConfig) throws StateAccessException {
        log.debug("RouterOpService.buildLink entered: portId=" + portId
                + ",peerPortId=" + peerPortId + ",routerId=" + config.device_id
                + ",peerRouterId=" + peerConfig.device_id);

        List<Op> ops = new ArrayList<Op>();

        ops.add(opBuilder.getRouterRouterCreateOp(config.device_id,
                peerConfig.device_id,
                zkDao.constructPeerRouterConfig(portId, peerPortId)));

        ops.add(opBuilder.getRouterRouterCreateOp(peerConfig.device_id,
                config.device_id,
                zkDao.constructPeerRouterConfig(peerPortId, portId)));

        // Create the port entries.
        ops.addAll(portOpService.buildCreateLink(portId, config, peerPortId,
                peerConfig));

        log.debug("RouterOpService.buildLink exiting: ops count={}", ops.size());
        return ops;
    }

    /**
     * Build list of Op objects to unlink routers
     *
     * @param id
     *            Router ID
     * @param peerId
     *            Peer router ID
     * @return List of Op objects
     * @throws StateAccessException
     *             Data access error
     */
    public List<Op> buildUnlink(UUID id, UUID peerId)
            throws StateAccessException {
        log.debug("RouterOpService.buildUnLink entered: id=" + id + ",peerId="
                + peerId);

        PeerRouterConfig config = zkDao.getRouterLinkData(id, peerId);
        List<Op> ops = new ArrayList<Op>();

        ops.addAll(portOpService.buildDeleteLink(config.portId,
                config.peerPortId));
        ops.add(opBuilder.getRouterRouterDeleteOp(peerId, id));
        ops.add(opBuilder.getRouterRouterDeleteOp(id, peerId));

        log.debug("RouterOpService.buildUnLink exiting: ops count={}",
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
