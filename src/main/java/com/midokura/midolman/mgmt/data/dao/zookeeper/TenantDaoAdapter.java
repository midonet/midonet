/*
 * @(#)TenantDaoAdapter        1.6 12/1/10
 *
 * Copyright 2012 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dao.zookeeper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.zookeeper.Op;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.data.dao.BridgeDao;
import com.midokura.midolman.mgmt.data.dao.RouterDao;
import com.midokura.midolman.mgmt.data.dao.TenantDao;
import com.midokura.midolman.mgmt.data.dto.Bridge;
import com.midokura.midolman.mgmt.data.dto.Router;
import com.midokura.midolman.mgmt.data.dto.Tenant;
import com.midokura.midolman.mgmt.data.zookeeper.op.TenantOpService;
import com.midokura.midolman.state.StateAccessException;

/**
 * Tenant DAO data adapter.
 *
 * @version 1.6 10 Jan 2012
 * @author Ryu Ishimoto
 */
public class TenantDaoAdapter implements TenantDao {

    private final static Logger log = LoggerFactory
            .getLogger(TenantDaoAdapter.class);
    private final BridgeDao bridgeDao;
    private final RouterDao routerDao;
    private final TenantOpService opService;
    private final TenantZkDao zkDao;

    /**
     * Constructor
     *
     * @param zkDao
     *            TenantZkDao object
     * @param opService
     *            TenantOpService object
     * @param bridgeDao
     *            BridgeDao object
     * @param routerDao
     *            RouterDao object
     */
    public TenantDaoAdapter(TenantZkDao zkDao, TenantOpService opService,
            BridgeDao bridgeDao, RouterDao routerDao) {
        this.zkDao = zkDao;
        this.opService = opService;
        this.bridgeDao = bridgeDao;
        this.routerDao = routerDao;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.data.dao.TenantDao#create(com.midokura.midolman
     * .mgmt.data.dto.Tenant)
     */
    @Override
    public String create(Tenant tenant) throws StateAccessException {
        log.debug("TenantDaoAdapter.create entered: tenant={}", tenant);

        if (tenant.getId() == null) {
            tenant.setId(UUID.randomUUID().toString());
        }

        List<Op> ops = opService.buildCreate(tenant.getId());
        zkDao.multi(ops);

        log.debug("TenantDaoAdapter.create exiting: tenant={}", tenant);
        return tenant.getId();
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.data.dao.TenantDao#delete(java.lang.String)
     */
    @Override
    public void delete(String id) throws StateAccessException {
        log.debug("TenantDaoAdapter.delete entered: id={}", id);

        List<Op> ops = opService.buildDelete(id);
        zkDao.multi(ops);

        log.debug("TenantDaoAdapter.delete exiting.");
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.dao.TenantDao#get(java.lang.String)
     */
    @Override
    public Tenant get(String id) throws StateAccessException {
        log.debug("TenantDaoAdapter.get entered: id={}", id);

        // Call get just to see if we get StateAccessException.
        zkDao.getData(id);
        Tenant tenant = new Tenant(id);

        log.debug("TenantDaoAdapter.get existing: tenant={}", tenant);
        return tenant;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.data.dao.TenantDao#getByAdRoute(java.util.
     * UUID)
     */
    @Override
    public Tenant getByAdRoute(UUID adRouteId) throws StateAccessException {
        log.debug("TenantDaoAdapter.getByAdRoute entered: adRouteId={}",
                adRouteId);

        Router router = routerDao.getByAdRoute(adRouteId);
        Tenant tenant = get(router.getTenantId());

        log.debug("TenantDaoAdapter.getByAdRoute exiting: tenant={}", tenant);
        return tenant;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.data.dao.TenantDao#getByBgp(java.util.UUID)
     */
    @Override
    public Tenant getByBgp(UUID bgpId) throws StateAccessException {
        log.debug("TenantDaoAdapter.getByBgp entered: bgpId={}", bgpId);

        Router router = routerDao.getByBgp(bgpId);
        Tenant tenant = get(router.getTenantId());

        log.debug("TenantDaoAdapter.getByBgp exiting: tenant={}", tenant);
        return tenant;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.data.dao.TenantDao#getByBridge(java.util.UUID)
     */
    @Override
    public Tenant getByBridge(UUID bridgeId) throws StateAccessException {
        log.debug("TenantDaoAdapter.getByBridge entered: bridgeId={}", bridgeId);

        Bridge bridge = bridgeDao.get(bridgeId);
        Tenant tenant = get(bridge.getTenantId());

        log.debug("TenantDaoAdapter.getByBridge exiting: tenant={}", tenant);
        return tenant;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.data.dao.TenantDao#getByChain(java.util.UUID)
     */
    @Override
    public Tenant getByChain(UUID chainId) throws StateAccessException {
        log.debug("TenantDaoAdapter.getByChain entered: chainId={}", chainId);

        Router router = routerDao.getByChain(chainId);
        Tenant tenant = get(router.getTenantId());

        log.debug("TenantDaoAdapter.getByChain exiting: tenant={}", tenant);
        return tenant;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.data.dao.TenantDao#getByPort(java.util.UUID)
     */
    @Override
    public Tenant getByPort(UUID portId) throws StateAccessException {
        log.debug("TenantDaoAdapter.getByRouterPort entered: portId={}", portId);

        String tenantId = null;
        Router router = routerDao.getByPort(portId);
        if (router == null) {
            Bridge bridge = bridgeDao.getByPort(portId);
            tenantId = bridge.getTenantId();
        } else {
            tenantId = router.getTenantId();
        }
        Tenant tenant = get(tenantId);

        log.debug("TenantDaoAdapter.getByRouterPort exiting: tenant={}", tenant);
        return tenant;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.data.dao.TenantDao#getByRoute(java.util.UUID)
     */
    @Override
    public Tenant getByRoute(UUID routeId) throws StateAccessException {
        log.debug("TenantDaoAdapter.getByRoute entered: routeId={}", routeId);

        Router router = routerDao.getByRoute(routeId);
        Tenant tenant = get(router.getTenantId());

        log.debug("TenantDaoAdapter.getByRoute exiting: tenant={}", tenant);
        return tenant;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.data.dao.TenantDao#getByRouter(java.util.UUID)
     */
    @Override
    public Tenant getByRouter(UUID routerId) throws StateAccessException {
        log.debug("TenantDaoAdapter.getByRouter entered: routerId={}", routerId);

        Router router = routerDao.get(routerId);
        Tenant tenant = get(router.getTenantId());

        log.debug("TenantDaoAdapter.getByRouter exiting: tenant={}", tenant);
        return tenant;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.data.dao.TenantDao#getByRule(java.util.UUID)
     */
    @Override
    public Tenant getByRule(UUID ruleId) throws StateAccessException {
        log.debug("TenantDaoAdapter.getByRule entered: ruleId={}", ruleId);

        Router router = routerDao.getByRule(ruleId);
        Tenant tenant = get(router.getTenantId());

        log.debug("TenantDaoAdapter.getByRule exiting: tenant={}", tenant);
        return tenant;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.data.dao.TenantDao#getByVpn(java.util.UUID)
     */
    @Override
    public Tenant getByVpn(UUID vpnId) throws StateAccessException {
        log.debug("TenantDaoAdapter.getByVpn entered: vpnId={}", vpnId);

        Router router = routerDao.getByVpn(vpnId);
        Tenant tenant = get(router.getTenantId());

        log.debug("TenantDaoAdapter.getByVpn exiting: tenant={}", tenant);
        return tenant;
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.dao.TenantDao#list()
     */
    @Override
    public List<Tenant> list() throws StateAccessException {
        log.debug("TenantDaoAdapter.list entered.");

        Set<String> ids = zkDao.getIds();
        List<Tenant> tenants = new ArrayList<Tenant>();
        for (String id : ids) {
            tenants.add(get(id));
        }

        log.debug("TenantDaoAdapter.list exiting: ids count={}", ids.size());
        return tenants;
    }

}
