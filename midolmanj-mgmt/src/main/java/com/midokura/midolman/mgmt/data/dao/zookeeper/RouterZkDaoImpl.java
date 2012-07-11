/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.dao.zookeeper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.zookeeper.Op;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.data.dao.PortDao;
import com.midokura.midolman.mgmt.data.dao.RouteDao;
import com.midokura.midolman.mgmt.data.dto.Port;
import com.midokura.midolman.mgmt.data.dto.Route;
import com.midokura.midolman.mgmt.data.dto.Router;
import com.midokura.midolman.mgmt.data.dto.RouterPort;
import com.midokura.midolman.mgmt.data.zookeeper.path.PathBuilder;
import com.midokura.midolman.state.RouterZkManager;
import com.midokura.midolman.state.RouterZkManager.RouterConfig;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkConfigSerializer;

/**
 * Router ZK DAO implementation
 */
public class RouterZkDaoImpl implements RouterZkDao {

    private final static Logger log = LoggerFactory
            .getLogger(RouterZkDaoImpl.class);
    private final RouterZkManager zkDao;
    private final PathBuilder pathBuilder;
    private final ZkConfigSerializer serializer;
    private final PortDao portDao;
    private final RouteDao routeDao;

    /**
     * Constructor
     *
     * @param zkDao
     *            RouterZkManager object
     * @param pathBuilder
     *            PathBuilder object to get path data.
     * @param serializer
     *            ZkConfigSerializer object.
     * @param portDao
     *            PortDao object
     * @param routeDao
     *            RouteDao object
     */
    public RouterZkDaoImpl(RouterZkManager zkDao, PathBuilder pathBuilder,
            ZkConfigSerializer serializer, PortDao portDao, RouteDao routeDao) {
        this.zkDao = zkDao;
        this.pathBuilder = pathBuilder;
        this.serializer = serializer;
        this.portDao = portDao;
        this.routeDao = routeDao;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.data.dao.RouterDao#create(com.midokura.midolman
     * .mgmt.data.dto.Router)
     */
    @Override
    public UUID create(Router router) throws StateAccessException {
        log.debug("RouterZkDaoImpl.create entered: router={}", router);

        if (router.getId() == null) {
            router.setId(UUID.randomUUID());
        }

        List<Op> ops = zkDao.prepareRouterCreate(router.getId(),
                router.toConfig());

        ops.add(zkDao.getPersistentCreateOp(
                pathBuilder.getTenantRouterPath(router.getTenantId(),
                        router.getId()), null));

        byte[] data = serializer.serialize(router.toNameMgmtConfig());
        ops.add(zkDao.getPersistentCreateOp(
                pathBuilder.getTenantRouterNamePath(router.getTenantId(),
                        router.getName()), data));

        zkDao.multi(ops);

        log.debug("RouterZkDaoImpl.create exiting: router={}", router);
        return router.getId();
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.dao.RouterDao#delete(java.util.UUID)
     */
    @Override
    public void delete(UUID id) throws StateAccessException {
        log.debug("RouterZkDaoImpl.delete entered: id={}", id);

        List<Op> ops = prepareDelete(id);
        zkDao.multi(ops);

        log.debug("RouterZkDaoImpl.delete exiting.");
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.dao.RouterDao#get(java.util.UUID,
     * boolean)
     */
    @Override
    public Router get(UUID id) throws StateAccessException {
        log.debug("RouterZkDaoImpl.get entered: id={}", id);

        Router router = null;
        if (zkDao.exists(id)) {
            RouterConfig config = zkDao.get(id);
            router = new Router(id, config);
        }

        log.debug("RouterZkDaoImpl.get exiting: router={}", router);
        return router;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.data.dao.RouterDao#getByAdRoute(java.util.
     * UUID)
     */
    @Override
    public Router getByAdRoute(UUID adRouteId) throws StateAccessException {
        log.debug("RouterZkDaoImpl.getByAdRoute entered: adRouteId={}",
                adRouteId);

        Port port = portDao.getByAdRoute(adRouteId);
        Router router = get(port.getDeviceId());

        log.debug("RouterZkDaoImpl.getByAdRoute exiting: router={}", router);
        return router;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.data.dao.RouterDao#getByBgp(java.util.UUID)
     */
    @Override
    public Router getByBgp(UUID bgpId) throws StateAccessException {
        log.debug("RouterZkDaoImpl.getByBgp entered: bgpId={}", bgpId);

        Port port = portDao.getByBgp(bgpId);
        Router router = get(port.getDeviceId());

        log.debug("RouterZkDaoImpl.getByBgp exiting: router={}", router);
        return router;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.data.dao.RouterDao#getByName(java.lang.String,
     * java.lang.String)
     */
    @Override
    public Router getByName(String tenantId, String name)
            throws StateAccessException {
        log.debug("RouterZkDaoImpl.getByName entered: tenantId=" + tenantId
                + ", name=" + name);

        List<Router> routers = list(tenantId);
        Router match = null;
        for (Router router : routers) {
            if (router.getName().equals(name)) {
                match = router;
                break;
            }
        }

        log.debug("RouterZkDaoImpl.getByName exiting: router={}", match);
        return match;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.data.dao.RouterDao#getByPort(java.util.UUID)
     */
    @Override
    public Router getByPort(UUID portId) throws StateAccessException {
        log.debug("RouterZkDaoImpl.getByPort entered: portId={}", portId);

        Port port = portDao.get(portId);
        if (!(port instanceof RouterPort)) {
            return null;
        }
        Router router = get(port.getDeviceId());

        log.debug("RouterZkDaoImpl.getByPort exiting: router={}", router);
        return router;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.data.dao.RouterDao#getByRoute(java.util.UUID)
     */
    @Override
    public Router getByRoute(UUID routeId) throws StateAccessException {
        log.debug("RouterZkDaoImpl.getByRoute entered: routeId={}", routeId);

        Route route = routeDao.get(routeId);
        Router router = get(route.getRouterId());

        log.debug("RouterZkDaoImpl.getByRoute exiting: router={}", router);
        return router;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.data.dao.RouterDao#getByVpn(java.util.UUID)
     */
    @Override
    public Router getByVpn(UUID vpnId) throws StateAccessException {
        log.debug("RouterZkDaoImpl.getByVpn entered: vpnId={}", vpnId);

        Port port = portDao.getByVpn(vpnId);
        Router router = get(port.getDeviceId());

        log.debug("RouterZkDaoImpl.getByVpn exiting: router={}", router);
        return router;
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.dao.RouterDao#list(java.lang.String,
     * boolean)
     */
    @Override
    public List<Router> list(String tenantId) throws StateAccessException {
        log.debug("RouterZkDaoImpl.list entered: tenantId={}", tenantId);

        String path = pathBuilder.getTenantRoutersPath(tenantId);
        Set<String> ids = zkDao.getChildren(path, null);
        List<Router> routers = new ArrayList<Router>();
        for (String id : ids) {
            routers.add(get(UUID.fromString(id)));
        }

        log.debug("RouterZkDaoImpl.list exiting: routers count={}",
                routers.size());
        return routers;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.data.dao.zookeeper.RouterZkDao#prepareDelete
     * (java.util.UUID)
     */
    @Override
    public List<Op> prepareDelete(UUID id) throws StateAccessException {
        return prepareDelete(get(id));
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.data.dao.zookeeper.RouterZkDao#prepareDelete
     * (com.midokura.midolman.mgmt.data.dto.Router)
     */
    @Override
    public List<Op> prepareDelete(Router router) throws StateAccessException {

        List<Op> ops = zkDao.prepareRouterDelete(router.getId());
        String path = pathBuilder.getTenantRouterPath(router.getTenantId(),
                router.getId());
        ops.add(zkDao.getDeleteOp(path));

        path = pathBuilder.getTenantRouterNamePath(router.getTenantId(),
                router.getName());
        ops.add(zkDao.getDeleteOp(path));

        return ops;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.data.dao.RouterDao#update(com.midokura.midolman
     * .mgmt.data.dto.Router)
     */
    @Override
    public void update(Router router) throws StateAccessException {
        log.debug("RouterZkDaoImpl.update entered: router={}", router);

        List<Op> ops = new ArrayList<Op>();

        // Get the original config
        RouterConfig oldConfig = zkDao.get(router.getId());

        // Update the config
        Op op = zkDao.prepareUpdate(router.getId(), router.toConfig());
        if (op != null) {
            ops.add(op);
        }

        // Update index if the name changed
        if (!router.getName().equals(oldConfig.name)) {

            String path = pathBuilder.getTenantRouterNamePath(
                    router.getTenantId(), oldConfig.name);
            ops.add(zkDao.getDeleteOp(path));

            path = pathBuilder.getTenantRouterNamePath(router.getTenantId(),
                    router.getName());
            byte[] data = serializer.serialize(router.toNameMgmtConfig());
            ops.add(zkDao.getPersistentCreateOp(path, data));
        }

        if (ops.size() > 0) {
            zkDao.multi(ops);
        }

        log.debug("RouterZkDaoImpl.update exiting");
    }
}
