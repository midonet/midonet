/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.zookeeper.op;

import java.util.List;
import java.util.UUID;

import org.apache.zookeeper.Op;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.data.dto.config.RouterMgmtConfig;
import com.midokura.midolman.mgmt.data.dto.config.RouterNameMgmtConfig;
import com.midokura.midolman.mgmt.data.zookeeper.io.RouterSerializer;
import com.midokura.midolman.mgmt.data.zookeeper.path.PathBuilder;
import com.midokura.midolman.state.RouterZkManager;
import com.midokura.midolman.state.RouterZkManager.RouterConfig;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkStateSerializationException;

/**
 * Class to build Op for the router paths.
 */
public class RouterOpBuilder {

    private final static Logger log = LoggerFactory
            .getLogger(RouterOpBuilder.class);
    private final RouterSerializer serializer;
    private final PathBuilder pathBuilder;
    private final RouterZkManager zkDao;

    /**
     * Constructor
     * 
     * @param zkDao
     *            ZkManager object to access ZK data.
     * @param pathBuilder
     *            PathBuilder object to get path data.
     * @param serializer
     *            RouterSerializer object.
     */
    public RouterOpBuilder(RouterZkManager zkDao, PathBuilder pathBuilder,
            RouterSerializer serializer) {
        this.zkDao = zkDao;
        this.pathBuilder = pathBuilder;
        this.serializer = serializer;
    }

    /**
     * Get the router create Op object.
     * 
     * @param id
     *            ID of the router.
     * @param config
     *            RouterMgmtConfig object to create.
     * @return Op for router create.
     */
    public Op getRouterCreateOp(UUID id, RouterMgmtConfig config)
            throws ZkStateSerializationException {
        log.debug("RouterOpBuilder.getRouterCreateOp entered: id={}", id);

        String path = pathBuilder.getRouterPath(id);
        byte[] data = serializer.serialize(config);
        Op op = zkDao.getPersistentCreateOp(path, data);

        log.debug("RouterOpBuilder.getRouterCreateOp exiting.");
        return op;
    }

    /**
     * Gets a list of Op objects to create a router in Midolman side.
     * 
     * @param id
     *            ID of the router
     * @return List of Op objects.
     * @throws StateAccessException
     *             Data access error.
     */
    public List<Op> getRouterCreateOps(UUID id, RouterConfig config)
            throws StateAccessException {
        log.debug("RouterOpBuilder.getRouterCreateOps entered: id=" + id);
        List<Op> ops = zkDao.prepareRouterCreate(id, config);
        log.debug("RouterOpBuilder.getRouterCreateOps exiting: ops count="
                + ops.size());
        return ops;
    }

    public Op getRouterUpdateOp(UUID id, RouterConfig config)
            throws StateAccessException {
        return zkDao.prepareUpdate(id, config);
    }

    /**
     * Get the router delete Op object.
     * 
     * @param id
     *            ID of the router.
     * @return Op for router delete.
     */
    public Op getRouterDeleteOp(UUID id) {
        log.debug("RouterOpBuilder.getRouterDeleteOp entered: id={}", id);

        String path = pathBuilder.getRouterPath(id);
        Op op = zkDao.getDeleteOp(path);

        log.debug("RouterOpBuilder.getRouterDeleteOp exiting.");
        return op;
    }

    /**
     * Gets a list of Op objects to delete a Router in Midolman side.
     * 
     * @param id
     *            ID of the router
     * @return List of Op objects.
     * @throws StateAccessException
     *             Data access error.
     */
    public List<Op> getRouterDeleteOps(UUID id) throws StateAccessException {
        log.debug("RouterOpBuilder.getRouterDeleteOps entered: id={}", id);

        RouterZkManager routerZkDao = zkDao;
        List<Op> ops = routerZkDao.prepareRouterDelete(id);

        log.debug("RouterOpBuilder.getRouterDeleteOps exiting: ops count={}",
                ops.size());
        return ops;
    }

    /**
     * Get the router update Op object.
     * 
     * @param id
     *            ID of the router.
     * @param config
     *            RouterMgmtConfig object to set.
     * @return Op for router update.
     */
    public Op getRouterSetDataOp(UUID id, RouterMgmtConfig config)
            throws ZkStateSerializationException {
        log.debug("RouterOpBuilder.getRouterSetDataOp entered: id={}", id);

        String path = pathBuilder.getRouterPath(id);
        byte[] data = serializer.serialize(config);
        Op op = zkDao.getSetDataOp(path, data);

        log.debug("RouterOpBuilder.getRouterSetDataOp exiting.");
        return op;
    }

    /**
     * Get the tenant router create Op object.
     * 
     * @param tenantId
     *            ID of the tenant
     * @param id
     *            ID of the router.
     * @return Op for tenant router create.
     */
    public Op getTenantRouterCreateOp(String tenantId, UUID id) {
        log.debug("RouterOpBuilder.getTenantRouterCreateOp entered: tenantId="
                + tenantId + ", id=" + id);

        String path = pathBuilder.getTenantRouterPath(tenantId, id);
        Op op = zkDao.getPersistentCreateOp(path, null);

        log.debug("RouterOpBuilder.getTenantRouterCreateOp exiting.");
        return op;
    }

    /**
     * Get the tenant router delete Op object.
     * 
     * @param tenantId
     *            ID of the tenant
     * @param id
     *            ID of the router.
     * @return Op for tenant router delete.
     */
    public Op getTenantRouterDeleteOp(String tenantId, UUID id) {
        log.debug("RouterOpBuilder.getTenantRouterDeleteOp entered: tenantId="
                + tenantId + ", id=" + id);

        String path = pathBuilder.getTenantRouterPath(tenantId, id);
        Op op = zkDao.getDeleteOp(path);

        log.debug("RouterOpBuilder.getTenantRouterDeleteOp exiting.");
        return op;
    }

    /**
     * Get the tenant router name create Op object.
     * 
     * @param tenantId
     *            ID of the tenant
     * @param name
     *            name of the router
     * @param config
     *            RouterMgmtConfig object to set.
     * @return Op for tenant router name create.
     */
    public Op getTenantRouterNameCreateOp(String tenantId, String name,
            RouterNameMgmtConfig config) throws ZkStateSerializationException {
        log.debug("RouterOpBuilder.getTenantRouterNameCreateOp entered: tenantId="
                + tenantId + ", name=" + name);

        String path = pathBuilder.getTenantRouterNamePath(tenantId, name);
        byte[] data = serializer.serialize(config);
        Op op = zkDao.getPersistentCreateOp(path, data);

        log.debug("RouterOpBuilder.getTenantRouterNameCreateOp exiting.");
        return op;
    }

    /**
     * Get the tenant router name delete Op object.
     * 
     * @param tenantId
     *            ID of the tenant
     * @param name
     *            name of the router
     * @return Op for tenant router name delete.
     */
    public Op getTenantRouterNameDeleteOp(String tenantId, String name) {
        log.debug("RouterOpBuilder.getTenantRouterNameDeleteOp entered: tenantId="
                + tenantId + ", name=" + name);

        String path = pathBuilder.getTenantRouterNamePath(tenantId, name);
        Op op = zkDao.getDeleteOp(path);

        log.debug("RouterOpBuilder.getTenantRouterNameDeleteOp exiting.");
        return op;
    }

}
