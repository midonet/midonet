/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.zookeeper.dao;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.google.inject.Inject;
import com.midokura.midolman.state.zkManagers.RouterZkManager;
import org.apache.zookeeper.Op;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.data.dao.PortDao;
import com.midokura.midolman.mgmt.data.dao.RouteDao;
import com.midokura.midolman.mgmt.data.dto.Port;
import com.midokura.midolman.mgmt.data.dto.Route;
import com.midokura.midolman.mgmt.data.dto.Router;
import com.midokura.midolman.mgmt.data.dto.RouterPort;
import com.midokura.midolman.mgmt.data.dto.config.RouterNameMgmtConfig;
import com.midokura.midolman.mgmt.data.zookeeper.path.PathBuilder;
import com.midokura.midolman.state.zkManagers.RouterZkManager.RouterConfig;
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
    @Inject
    public RouterZkDaoImpl(RouterZkManager zkDao, PathBuilder pathBuilder,
            ZkConfigSerializer serializer, PortDao portDao, RouteDao routeDao) {
        this.zkDao = zkDao;
        this.pathBuilder = pathBuilder;
        this.serializer = serializer;
        this.portDao = portDao;
        this.routeDao = routeDao;
    }

    @Override
    public UUID create(Router router) throws StateAccessException {
        log.debug("RouterZkDaoImpl.create entered: router={}", router);

        if (router.getId() == null) {
            router.setId(UUID.randomUUID());
        }

        List<Op> ops = zkDao.prepareRouterCreate(router.getId(),
                router.toConfig());

        byte[] data = serializer.serialize(router.toNameMgmtConfig());
        ops.add(zkDao.getPersistentCreateOp(
                pathBuilder.getTenantRouterNamePath(router.getTenantId(),
                        router.getName()), data));

        zkDao.multi(ops);

        log.debug("RouterZkDaoImpl.create exiting: router={}", router);
        return router.getId();
    }

    @Override
    public void delete(UUID id) throws StateAccessException {
        log.debug("RouterZkDaoImpl.delete entered: id={}", id);

        List<Op> ops = prepareDelete(id);
        zkDao.multi(ops);

        log.debug("RouterZkDaoImpl.delete exiting.");
    }

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

    @Override
    public List<Op> prepareDelete(UUID id) throws StateAccessException {
        return prepareDelete(get(id));
    }

    @Override
    public List<Op> prepareDelete(Router router) throws StateAccessException {

        List<Op> ops = zkDao.prepareRouterDelete(router.getId());

        String path = pathBuilder.getTenantRouterNamePath(router.getTenantId(),
                router.getName());
        ops.add(zkDao.getDeleteOp(path));

        return ops;
    }

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

    @Override
    public Router findByName(String tenantId, String name)
            throws StateAccessException {
        log.debug("RouterZkDaoImpl.findByName entered: tenantId=" + tenantId
                + ", name=" + name);

        Router router = null;
        String path = pathBuilder.getTenantRouterNamePath(tenantId, name);
        if (zkDao.exists(path)) {
            byte[] data = zkDao.get(path);
            RouterNameMgmtConfig nameConfig = serializer.deserialize(data,
                    RouterNameMgmtConfig.class);
            router = get(nameConfig.id);
        }

        log.debug("RouterZkDaoImpl.findByName exiting: router={}", router);
        return router;
    }

    @Override
    public Router findByAdRoute(UUID adRouteId) throws StateAccessException {
        log.debug("RouterZkDaoImpl.findByAdRoute entered: adRouteId={}",
                adRouteId);

        Port port = portDao.findByAdRoute(adRouteId);
        Router router = get(port.getDeviceId());

        log.debug("RouterZkDaoImpl.findByAdRoute exiting: router={}", router);
        return router;
    }

    @Override
    public Router findByBgp(UUID bgpId) throws StateAccessException {
        log.debug("RouterZkDaoImpl.findByBgp entered: bgpId={}", bgpId);

        Port port = portDao.findByBgp(bgpId);
        Router router = get(port.getDeviceId());

        log.debug("RouterZkDaoImpl.findByBgp exiting: router={}", router);
        return router;
    }

    @Override
    public Router findByPort(UUID portId) throws StateAccessException {
        log.debug("RouterZkDaoImpl.findByPort entered: portId={}", portId);

        Port port = portDao.get(portId);
        if (!(port instanceof RouterPort)) {
            return null;
        }
        Router router = get(port.getDeviceId());

        log.debug("RouterZkDaoImpl.findByPort exiting: router={}", router);
        return router;
    }

    @Override
    public Router findByRoute(UUID routeId) throws StateAccessException {
        log.debug("RouterZkDaoImpl.findByRoute entered: routeId={}", routeId);

        Route route = routeDao.get(routeId);
        Router router = get(route.getRouterId());

        log.debug("RouterZkDaoImpl.findByRoute exiting: router={}", router);
        return router;
    }

    @Override
    public Router findByVpn(UUID vpnId) throws StateAccessException {
        log.debug("RouterZkDaoImpl.findByVpn entered: vpnId={}", vpnId);

        Port port = portDao.findByVpn(vpnId);
        Router router = get(port.getDeviceId());

        log.debug("RouterZkDaoImpl.findByVpn exiting: router={}", router);
        return router;
    }

    @Override
    public List<Router> findByTenant(String tenantId)
            throws StateAccessException {
        log.debug("RouterZkDaoImpl.findByTenant entered: tenantId={}",
                tenantId);

        String path = pathBuilder.getTenantRouterNamesPath(tenantId);
        Set<String> names = zkDao.getChildren(path, null);
        List<Router> routers = new ArrayList<Router>();
        for (String name : names) {
            routers.add(findByName(tenantId, name));
        }

        log.debug("RouterZkDaoImpl.findByTenant exiting: routers count={}",
                routers.size());
        return routers;
    }
}
