/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.zookeeper.dao;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.midokura.midolman.state.InvalidStateOperationException;
import org.apache.zookeeper.Op;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.data.dao.TenantDao;
import com.midokura.midolman.mgmt.data.dto.Bridge;
import com.midokura.midolman.mgmt.data.dto.Chain;
import com.midokura.midolman.mgmt.data.dto.PortGroup;
import com.midokura.midolman.mgmt.data.dto.Router;
import com.midokura.midolman.mgmt.data.dto.Tenant;
import com.midokura.midolman.mgmt.data.zookeeper.path.PathBuilder;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkManager;

/**
 * Tenant ZK DAO implementation.
 */
public class TenantDaoImpl implements TenantDao {

    private final static Logger log = LoggerFactory
            .getLogger(TenantDaoImpl.class);
    private final BridgeZkDao bridgeZkDao;
    private final ChainZkDao chainZkDao;
    private final PortGroupZkDao portGroupZkDao;
    private final RouterZkDao routerZkDao;
    private final ZkManager zkDao;
    private final PathBuilder pathBuilder;

    /**
     * Constructor
     *
     * @param zkDao
     *            ZkManager object
     * @param pathBuilder
     *            PathBuilder object to get path data.
     * @param bridgeZkDao
     *            BridgeZkDao object
     * @param routerZkDao
     *            RouterZkDao object
     * @param chainZkDao
     *            ChainZkDao object
     * @param portGroupZkDao
     *            PortGroupZkDao object
     */
    public TenantDaoImpl(ZkManager zkDao, PathBuilder pathBuilder,
            BridgeZkDao bridgeZkDao, RouterZkDao routerZkDao,
            ChainZkDao chainZkDao, PortGroupZkDao portGroupZkDao) {
        this.zkDao = zkDao;
        this.pathBuilder = pathBuilder;
        this.bridgeZkDao = bridgeZkDao;
        this.routerZkDao = routerZkDao;
        this.chainZkDao = chainZkDao;
        this.portGroupZkDao = portGroupZkDao;
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
        log.debug("TenantDaoImpl.create entered: tenant={}", tenant);

        if (tenant.getId() == null) {
            tenant.setId(UUID.randomUUID().toString());
        }

        List<Op> ops = new ArrayList<Op>();
        ops.add(zkDao.getPersistentCreateOp(
                pathBuilder.getTenantPath(tenant.getId()), null));
        ops.add(zkDao.getPersistentCreateOp(
                pathBuilder.getTenantBridgeNamesPath(tenant.getId()), null));
        ops.add(zkDao.getPersistentCreateOp(
                pathBuilder.getTenantRouterNamesPath(tenant.getId()), null));
        ops.add(zkDao.getPersistentCreateOp(
                pathBuilder.getTenantChainNamesPath(tenant.getId()), null));
        ops.add(zkDao.getPersistentCreateOp(
                pathBuilder.getTenantPortGroupNamesPath(tenant.getId()), null));
        zkDao.multi(ops);

        log.debug("TenantDaoImpl.create exiting: tenant={}", tenant);
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
        log.debug("TenantDaoImpl.delete entered: id={}", id);

        List<Op> ops = new ArrayList<Op>();

        // Remove routers
        List<Router> routers = routerZkDao.findByTenant(id);
        for (Router router : routers) {
            ops.addAll(routerZkDao.prepareDelete(router));
        }

        // Remove bridges
        List<Bridge> bridges = bridgeZkDao.findByTenant(id);
        for (Bridge bridge : bridges) {
            ops.addAll(bridgeZkDao.prepareDelete(bridge));
        }

        // Remove chains
        List<Chain> chains = chainZkDao.findByTenant(id);
        for (Chain chain : chains) {
            ops.addAll(chainZkDao.prepareDelete(chain));
        }

        // Remove port groups
        List<PortGroup> groups = portGroupZkDao.findByTenant(id);
        for (PortGroup group : groups) {
            ops.addAll(portGroupZkDao.prepareDelete(group));
        }

        ops.add(zkDao.getDeleteOp(pathBuilder.getTenantPortGroupNamesPath(id)));
        ops.add(zkDao.getDeleteOp(pathBuilder.getTenantChainNamesPath(id)));
        ops.add(zkDao.getDeleteOp(pathBuilder.getTenantRouterNamesPath(id)));
        ops.add(zkDao.getDeleteOp(pathBuilder.getTenantBridgeNamesPath(id)));
        ops.add(zkDao.getDeleteOp(pathBuilder.getTenantPath(id)));
        zkDao.multiDedup(ops);

        log.debug("TenantDaoImpl.delete exiting.");
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.dao.TenantDao#get(java.lang.String)
     */
    @Override
    public Tenant get(String id) throws StateAccessException {
        log.debug("TenantDaoImpl.get entered: id={}", id);

        Tenant tenant = null;
        String path = pathBuilder.getTenantPath(id);
        if (zkDao.exists(path)) {
            tenant = new Tenant(id);
        }

        log.debug("TenantDaoImpl.get existing: tenant={}", tenant);
        return tenant;
    }

    @Override
    public void update(Tenant obj)
            throws StateAccessException, InvalidStateOperationException {
        throw new UnsupportedOperationException();
    }

    @Override
    public Tenant findByAdRoute(UUID adRouteId) throws StateAccessException {
        log.debug("TenantDaoImpl.findByAdRoute entered: adRouteId={}",
                adRouteId);

        Router router = routerZkDao.findByAdRoute(adRouteId);
        Tenant tenant = get(router.getTenantId());

        log.debug("TenantDaoImpl.findByAdRoute exiting: tenant={}", tenant);
        return tenant;
    }

    @Override
    public Tenant findByBgp(UUID bgpId) throws StateAccessException {
        log.debug("TenantDaoImpl.findByBgp entered: bgpId={}", bgpId);

        Router router = routerZkDao.findByBgp(bgpId);
        Tenant tenant = get(router.getTenantId());

        log.debug("TenantDaoImpl.findByBgp exiting: tenant={}", tenant);
        return tenant;
    }

    @Override
    public Tenant findByBridge(UUID bridgeId) throws StateAccessException {
        log.debug("TenantDaoImpl.findByBridge entered: bridgeId={}", bridgeId);

        Bridge bridge = bridgeZkDao.get(bridgeId);
        Tenant tenant = get(bridge.getTenantId());

        log.debug("TenantDaoImpl.findByBridge exiting: tenant={}", tenant);
        return tenant;
    }

    @Override
    public Tenant findByChain(UUID chainId) throws StateAccessException {
        log.debug("TenantDaoImpl.findByChain entered: chainId={}", chainId);

        Chain chain = chainZkDao.get(chainId);
        Tenant tenant = get(chain.getTenantId());

        log.debug("TenantDaoImpl.findByChain exiting: tenant={}", tenant);
        return tenant;
    }

    @Override
    public Tenant findByPortGroup(UUID groupId) throws StateAccessException {
        log.debug("TenantDaoImpl.findByPortGroup entered: groupId={}", groupId);

        PortGroup group = portGroupZkDao.get(groupId);
        Tenant tenant = get(group.getTenantId());

        log.debug("TenantDaoImpl.findByPortGroup exiting: tenant={}", tenant);
        return tenant;
    }

    @Override
    public Tenant findByPort(UUID portId) throws StateAccessException {
        log.debug("TenantDaoImpl.findByPort entered: portId={}", portId);

        String tenantId = null;
        Router router = routerZkDao.findByPort(portId);
        if (router == null) {
            Bridge bridge = bridgeZkDao.findByPort(portId);
            tenantId = bridge.getTenantId();
        } else {
            tenantId = router.getTenantId();
        }
        Tenant tenant = get(tenantId);

        log.debug("TenantDaoImpl.findByPort exiting: tenant={}", tenant);
        return tenant;
    }

    @Override
    public Tenant findByRoute(UUID routeId) throws StateAccessException {
        log.debug("TenantDaoImpl.findByRoute entered: routeId={}", routeId);

        Router router = routerZkDao.findByRoute(routeId);
        Tenant tenant = get(router.getTenantId());

        log.debug("TenantDaoImpl.findByRoute exiting: tenant={}", tenant);
        return tenant;
    }

    @Override
    public Tenant findByRouter(UUID routerId) throws StateAccessException {
        log.debug("TenantDaoImpl.findByRouter entered: routerId={}", routerId);

        Router router = routerZkDao.get(routerId);
        Tenant tenant = get(router.getTenantId());

        log.debug("TenantDaoImpl.findByRouter exiting: tenant={}", tenant);
        return tenant;
    }

    @Override
    public Tenant findByRule(UUID ruleId) throws StateAccessException {
        log.debug("TenantDaoImpl.findByRule entered: ruleId={}", ruleId);

        Chain chain = chainZkDao.findByRule(ruleId);
        Tenant tenant = get(chain.getTenantId());

        log.debug("TenantDaoImpl.findByRule exiting: tenant={}", tenant);
        return tenant;
    }

    @Override
    public Tenant findByVpn(UUID vpnId) throws StateAccessException {
        log.debug("TenantDaoImpl.findByVpn entered: vpnId={}", vpnId);

        Router router = routerZkDao.findByVpn(vpnId);
        Tenant tenant = get(router.getTenantId());

        log.debug("TenantDaoImpl.findByVpn exiting: tenant={}", tenant);
        return tenant;
    }

    @Override
    public List<Tenant> findAll() throws StateAccessException {
        log.debug("TenantDaoImpl.findAll entered.");

        String path = pathBuilder.getTenantsPath();
        Set<String> ids = zkDao.getChildren(path, null);
        List<Tenant> tenants = new ArrayList<Tenant>();
        for (String id : ids) {
            tenants.add(get(id));
        }

        log.debug("TenantDaoImpl.findAll exiting: ids count={}", ids.size());
        return tenants;
    }
}
