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

import com.midokura.midolman.mgmt.data.dao.ChainDao;
import com.midokura.midolman.mgmt.data.dao.PortDao;
import com.midokura.midolman.mgmt.data.dao.RouteDao;
import com.midokura.midolman.mgmt.data.dao.RouterDao;
import com.midokura.midolman.mgmt.data.dto.Chain;
import com.midokura.midolman.mgmt.data.dto.Port;
import com.midokura.midolman.mgmt.data.dto.Route;
import com.midokura.midolman.mgmt.data.dto.Router;
import com.midokura.midolman.mgmt.data.dto.RouterPort;
import com.midokura.midolman.mgmt.data.dto.config.RouterMgmtConfig;
import com.midokura.midolman.mgmt.data.zookeeper.op.RouterOpService;
import com.midokura.midolman.state.StateAccessException;

/**
 * Router ZK DAO adapter
 */
public class RouterDaoAdapter implements RouterDao {

    private final static Logger log = LoggerFactory
            .getLogger(RouterDaoAdapter.class);
    private final RouterZkDao zkDao;
    private final RouterOpService opService;
    private final ChainDao chainDao;
    private final PortDao portDao;
    private final RouteDao routeDao;

    /**
     * Constructor
     *
     * @param zkDao
     *            RouterZkDao object
     * @param opService
     *            RouterOpService object
     * @param chainDao
     *            ChainDao object
     * @param portDao
     *            PortDao object
     * @param routeDao
     *            RouteDao object
     */
    public RouterDaoAdapter(RouterZkDao zkDao, RouterOpService opService,
            ChainDao chainDao, PortDao portDao, RouteDao routeDao) {
        this.zkDao = zkDao;
        this.opService = opService;
        this.chainDao = chainDao;
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
        log.debug("RouterDaoAdapter.create entered: router={}", router);

        if (router.getId() == null) {
            router.setId(UUID.randomUUID());
        }

        List<Op> ops = opService.buildCreate(router.getId(), router.toConfig(),
                router.toMgmtConfig(), router.toNameMgmtConfig());
        zkDao.multi(ops);

        log.debug("RouterDaoAdapter.create exiting: router={}", router);
        return router.getId();
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.dao.RouterDao#delete(java.util.UUID)
     */
    @Override
    public void delete(UUID id) throws StateAccessException {
        log.debug("RouterDaoAdapter.delete entered: id={}", id);

        List<Op> ops = opService.buildDelete(id, true);
        zkDao.multi(ops);

        log.debug("RouterDaoAdapter.delete exiting.");
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.dao.RouterDao#get(java.util.UUID,
     * boolean)
     */
    @Override
    public Router get(UUID id) throws StateAccessException {
        log.debug("RouterDaoAdapter.get entered: id={}", id);

        Router router = null;
        if (zkDao.exists(id)) {
            RouterMgmtConfig config = zkDao.getMgmtData(id);
            router = new Router(id, config.name, config.tenantId);
        }

        log.debug("RouterDaoAdapter.get existing: router={}", router);
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
        log.debug("RouterDaoAdapter.list entered: tenantId={}", tenantId);

        Set<String> ids = zkDao.getIds(tenantId);
        List<Router> routers = new ArrayList<Router>();
        for (String id : ids) {
            routers.add(get(UUID.fromString(id)));
        }

        log.debug("RouterDaoAdapter.list exiting: routers count={}",
                routers.size());
        return routers;
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
        log.debug("RouterDaoAdapter.update entered: router={}", router);

        List<Op> ops = opService.buildUpdate(router.getId(), router.getName());
        zkDao.multi(ops);

        log.debug("RouterDaoAdapter.update exiting");
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
        log.debug("RouterDaoAdapter.getByAdRoute entered: adRouteId={}",
                adRouteId);

        Port port = portDao.getByAdRoute(adRouteId);
        Router router = get(port.getDeviceId());

        log.debug("RouterDaoAdapter.getByAdRoute exiting: router={}", router);
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
        log.debug("RouterDaoAdapter.getByBgp entered: bgpId={}", bgpId);

        Port port = portDao.getByBgp(bgpId);
        Router router = get(port.getDeviceId());

        log.debug("RouterDaoAdapter.getByBgp exiting: router={}", router);
        return router;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.data.dao.RouterDao#getByPort(java.util.UUID)
     */
    @Override
    public Router getByPort(UUID portId) throws StateAccessException {
        log.debug("RouterDaoAdapter.getByPort entered: portId={}", portId);

        Port port = portDao.get(portId);
        if (!(port instanceof RouterPort)) {
            return null;
        }
        Router router = get(port.getDeviceId());

        log.debug("RouterDaoAdapter.getByPort exiting: router={}", router);
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
        log.debug("RouterDaoAdapter.getByVpn entered: vpnId={}", vpnId);

        Port port = portDao.getByVpn(vpnId);
        Router router = get(port.getDeviceId());

        log.debug("RouterDaoAdapter.getByVpn exiting: router={}", router);
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
        log.debug("RouterDaoAdapter.getByRoute entered: routeId={}", routeId);

        Route route = routeDao.get(routeId);
        Router router = get(route.getRouterId());

        log.debug("RouterDaoAdapter.getByRoute exiting: router={}", router);
        return router;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.data.dao.RouterDao#getByRule(java.util.UUID)
     */
    @Override
    public Router getByRule(UUID ruleId) throws StateAccessException {
        log.debug("RouterDaoAdapter.getByRule entered: ruleId={}", ruleId);

        Chain chain = chainDao.getByRule(ruleId);
        Router router = get(chain.getOwnerId());

        log.debug("RouterDaoAdapter.getByRule exiting: router={}", router);
        return router;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.data.dao.RouterDao#getByChain(java.util.UUID)
     */
    @Override
    public Router getByChain(UUID chainId) throws StateAccessException {
        log.debug("RouterDaoAdapter.getByChain entered: chainId={}", chainId);

        Chain chain = chainDao.get(chainId);
        Router router = get(chain.getOwnerId());

        log.debug("RouterDaoAdapter.getByChain exiting: router={}", router);
        return router;
    }
}
