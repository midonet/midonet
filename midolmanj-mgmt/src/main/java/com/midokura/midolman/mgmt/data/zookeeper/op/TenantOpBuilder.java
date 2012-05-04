/*
 * @(#)TenantOpBuilder        1.6 12/1/6
 *
 * Copyright 2012 Midokura KK
 */
package com.midokura.midolman.mgmt.data.zookeeper.op;

import java.util.UUID;

import org.apache.zookeeper.Op;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.data.zookeeper.path.PathBuilder;
import com.midokura.midolman.state.ZkManager;

/**
 * Class to build Op for the tenant paths.
 *
 * @version 1.6 6 Jan 2011
 * @author Ryu Ishimoto
 */
public class TenantOpBuilder {

    private final static Logger log = LoggerFactory
            .getLogger(TenantOpBuilder.class);
    private final ZkManager zkDao;
    private final PathBuilder pathBuilder;

    /**
     * Constructor
     *
     * @param zkDao
     *            ZkManager object to access ZK data.
     * @param pathBuilder
     *            PathBuilder object to get path data.
     */
    public TenantOpBuilder(ZkManager zkDao, PathBuilder pathBuilder) {
        this.zkDao = zkDao;
        this.pathBuilder = pathBuilder;
    }

    /**
     * Get the tenant bridge create Op object.
     *
     * @param id
     *            ID of the tenant
     * @return Op for tenant bridge create.
     */
    public Op getTenantBridgesCreateOp(String id) {
        log.debug(
                "TenantOpBuilder.getTenantBridgesCreateOp entered: id={}",
                id);

        String path = pathBuilder.getTenantBridgesPath(id);
        Op op = zkDao.getPersistentCreateOp(path, null);

        log.debug("TenantOpBuilder.getTenantBridgesCreateOp exiting.");
        return op;
    }

    /**
     * Get the tenant bridge delete Op object.
     *
     * @param id
     *            ID of the tenant
     * @return Op for tenant bridge delete.
     */
    public Op getTenantBridgesDeleteOp(String id) {
        log.debug(
                "TenantOpBuilder.getTenantBridgesDeleteOp entered: id={}",
                id);

        String path = pathBuilder.getTenantBridgesPath(id);
        Op op = zkDao.getDeleteOp(path);

        log.debug("TenantOpBuilder.getTenantBridgesDeleteOp exiting.");
        return op;
    }

    /**
     * Get the tenant bridge name create Op object.
     *
     * @param id
     *            ID of the tenant
     * @return Op for tenant bridge name create.
     */
    public Op getTenantBridgeNamesCreateOp(String id) {
        log.debug(
                "TenantOpBuilder.getTenantBridgeNamesCreateOp entered: id={}",
                id);

        String path = pathBuilder.getTenantBridgeNamesPath(id);
        Op op = zkDao.getPersistentCreateOp(path, null);

        log.debug("TenantOpBuilder.getTenantBridgeNamesCreateOp exiting.");
        return op;
    }

    /**
     * Get the tenant bridge name delete Op object.
     *
     * @param id
     *            ID of the tenant
     * @return Op for tenant bridge name delete.
     */
    public Op getTenantBridgeNamesDeleteOp(String id) {
        log.debug(
                "TenantOpBuilder.getTenantBridgeNamesDeleteOp entered: id={}",
                id);

        String path = pathBuilder.getTenantBridgeNamesPath(id);
        Op op = zkDao.getDeleteOp(path);

        log.debug("TenantOpBuilder.getTenantBridgeNamesDeleteOp exiting.");
        return op;
    }

    /**
     * Get the tenant create Op object.
     *
     * @param id
     *            ID of the tenant
     * @return Op for tenant create.
     */
    public Op getTenantCreateOp(String id) {
        log.debug("TenantOpBuilder.getTenantCreateOp entered: id={}", id);

        String path = pathBuilder.getTenantPath(id);
        Op op = zkDao.getPersistentCreateOp(path, null);

        log.debug("TenantOpBuilder.getTenantCreateOp exiting.");
        return op;
    }

    /**
     * Get the tenant delete Op object.
     *
     * @param id
     *            ID of the tenant
     * @return Op for tenant delete.
     */
    public Op getTenantDeleteOp(String id) {
        log.debug("TenantOpBuilder.getTenantDeleteOp entered: id={}", id);

        String path = pathBuilder.getTenantPath(id);
        Op op = zkDao.getDeleteOp(path);

        log.debug("TenantOpBuilder.getTenantBridgeDeleteOp exiting.");
        return op;
    }

    /**
     * Get the tenant router create Op object.
     *
     * @param id
     *            ID of the tenant
     * @return Op for tenant router create.
     */
    public Op getTenantRoutersCreateOp(String id) {
        log.debug(
                "TenantOpBuilder.getTenantRoutersCreateOp entered: id={}",
                id);

        String path = pathBuilder.getTenantRoutersPath(id);
        Op op = zkDao.getPersistentCreateOp(path, null);

        log.debug("TenantOpBuilder.getTenantRoutersCreateOp exiting.");
        return op;
    }

    /**
     * Get the tenant router delete Op object.
     *
     * @param id
     *            ID of the tenant
     * @return Op for tenant router delete.
     */
    public Op getTenantRoutersDeleteOp(String id) {
        log.debug(
                "TenantOpBuilder.getTenantRoutersDeleteOp entered: id={}",
                id);

        String path = pathBuilder.getTenantRoutersPath(id);
        Op op = zkDao.getDeleteOp(path);

        log.debug("TenantOpBuilder.getTenantRoutersDeleteOp exiting.");
        return op;
    }

    /**
     * Get the tenant router name create Op object.
     *
     * @param id
     *            ID of the tenant
     * @return Op for tenant router name create.
     */
    public Op getTenantRouterNamesCreateOp(String id) {
        log.debug(
                "TenantOpBuilder.getTenantRouterNamesCreateOp entered: id={}",
                id);

        String path = pathBuilder.getTenantRouterNamesPath(id);
        Op op = zkDao.getPersistentCreateOp(path, null);

        log.debug("TenantOpBuilder.getTenantRouterNamesCreateOp exiting.");
        return op;
    }

    /**
     * Get the tenant router name delete Op object.
     *
     * @param id
     *            ID of the tenant
     * @return Op for tenant router name delete.
     */
    public Op getTenantRouterNamesDeleteOp(String id) {
        log.debug(
                "TenantOpBuilder.getTenantRouterNamesDeleteOp entered: id={}",
                id);

        String path = pathBuilder.getTenantRouterNamesPath(id);
        Op op = zkDao.getDeleteOp(path);

        log.debug("TenantOpBuilder.getTenantRouterNamesDeleteOp exiting.");
        return op;
    }

    /**
     * Get the tenant chain names create Op object.
     *
     * @param id
     *            ID of the tenant
     * @return Op for tenant chain names create.
     */
    public Op getTenantChainNamesCreateOp(String id) {
        return zkDao.getPersistentCreateOp(
                pathBuilder.getTenantChainNamesPath(id), null);
    }

    /**
     * Get the tenant chain names delete Op object.
     *
     * @param id
     *            ID of the tenant
     * @return Op for tenant chain names delete.
     */
    public Op getTenantChainNamesDeleteOp(String id) {
        return zkDao.getDeleteOp(pathBuilder.getTenantChainNamesPath(id));
    }

    /**
     * Get the tenant chains create Op object.
     *
     * @param id
     *            ID of the tenant
     * @return Op for tenant chains create.
     */
    public Op getTenantChainsCreateOp(String id) {
        return zkDao.getPersistentCreateOp(
                pathBuilder.getTenantChainsPath(id), null);
    }

    /**
     * Get the tenant chains delete Op object.
     *
     * @param id
     *            ID of the tenant
     * @return Op for tenant chains delete.
     */
    public Op getTenantChainsDeleteOp(String id) {
        return zkDao.getDeleteOp(pathBuilder.getTenantChainsPath(id));
    }

}
