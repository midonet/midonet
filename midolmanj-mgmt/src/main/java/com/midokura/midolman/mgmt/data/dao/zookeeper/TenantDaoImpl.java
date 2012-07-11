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
                pathBuilder.getTenantBridgesPath(tenant.getId()), null));
        ops.add(zkDao.getPersistentCreateOp(
                pathBuilder.getTenantRoutersPath(tenant.getId()), null));
        ops.add(zkDao.getPersistentCreateOp(
                pathBuilder.getTenantChainsPath(tenant.getId()), null));
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
        List<Router> routers = routerZkDao.list(id);
        for (Router router : routers) {
            ops.addAll(routerZkDao.prepareDelete(router));
        }

        // Remove bridges
        List<Bridge> bridges = bridgeZkDao.list(id);
        for (Bridge bridge : bridges) {
            ops.addAll(bridgeZkDao.prepareDelete(bridge));
        }

        // Remove chains
        List<Chain> chains = chainZkDao.list(id);
        for (Chain chain : chains) {
            ops.addAll(chainZkDao.prepareDelete(chain));
        }

        // Remove port groups
        List<PortGroup> groups = portGroupZkDao.list(id);
        for (PortGroup group : groups) {
            ops.addAll(portGroupZkDao.prepareDelete(group));
        }

        ops.add(zkDao.getDeleteOp(pathBuilder.getTenantPortGroupNamesPath(id)));
        ops.add(zkDao.getDeleteOp(pathBuilder.getTenantChainNamesPath(id)));
        ops.add(zkDao.getDeleteOp(pathBuilder.getTenantRouterNamesPath(id)));
        ops.add(zkDao.getDeleteOp(pathBuilder.getTenantBridgeNamesPath(id)));
        ops.add(zkDao.getDeleteOp(pathBuilder.getTenantChainsPath(id)));
        ops.add(zkDao.getDeleteOp(pathBuilder.getTenantRoutersPath(id)));
        ops.add(zkDao.getDeleteOp(pathBuilder.getTenantBridgesPath(id)));
        ops.add(zkDao.getDeleteOp(pathBuilder.getTenantPath(id)));
        zkDao.multi(ops);

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

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.data.dao.TenantDao#getByAdRoute(java.util.
     * UUID)
     */
    @Override
    public Tenant getByAdRoute(UUID adRouteId) throws StateAccessException {
        log.debug("TenantDaoImpl.getByAdRoute entered: adRouteId={}", adRouteId);

        Router router = routerZkDao.getByAdRoute(adRouteId);
        Tenant tenant = get(router.getTenantId());

        log.debug("TenantDaoImpl.getByAdRoute exiting: tenant={}", tenant);
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
        log.debug("TenantDaoImpl.getByBgp entered: bgpId={}", bgpId);

        Router router = routerZkDao.getByBgp(bgpId);
        Tenant tenant = get(router.getTenantId());

        log.debug("TenantDaoImpl.getByBgp exiting: tenant={}", tenant);
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
        log.debug("TenantDaoImpl.getByBridge entered: bridgeId={}", bridgeId);

        Bridge bridge = bridgeZkDao.get(bridgeId);
        Tenant tenant = get(bridge.getTenantId());

        log.debug("TenantDaoImpl.getByBridge exiting: tenant={}", tenant);
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
        log.debug("TenantDaoImpl.getByChain entered: chainId={}", chainId);

        Chain chain = chainZkDao.get(chainId);
        Tenant tenant = get(chain.getTenantId());

        log.debug("TenantDaoImpl.getByChain exiting: tenant={}", tenant);
        return tenant;
    }

    @Override
    public Tenant getByPortGroup(UUID groupId) throws StateAccessException {
        log.debug("TenantDaoImpl.getByPortGroup entered: groupId={}", groupId);

        PortGroup group = portGroupZkDao.get(groupId);
        Tenant tenant = get(group.getTenantId());

        log.debug("TenantDaoImpl.getByPortGroup exiting: tenant={}", tenant);
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
        log.debug("TenantDaoImpl.getByRouterPort entered: portId={}", portId);

        String tenantId = null;
        Router router = routerZkDao.getByPort(portId);
        if (router == null) {
            Bridge bridge = bridgeZkDao.getByPort(portId);
            tenantId = bridge.getTenantId();
        } else {
            tenantId = router.getTenantId();
        }
        Tenant tenant = get(tenantId);

        log.debug("TenantDaoImpl.getByRouterPort exiting: tenant={}", tenant);
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
        log.debug("TenantDaoImpl.getByRoute entered: routeId={}", routeId);

        Router router = routerZkDao.getByRoute(routeId);
        Tenant tenant = get(router.getTenantId());

        log.debug("TenantDaoImpl.getByRoute exiting: tenant={}", tenant);
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
        log.debug("TenantDaoImpl.getByRouter entered: routerId={}", routerId);

        Router router = routerZkDao.get(routerId);
        Tenant tenant = get(router.getTenantId());

        log.debug("TenantDaoImpl.getByRouter exiting: tenant={}", tenant);
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
        log.debug("TenantDaoImpl.getByRule entered: ruleId={}", ruleId);

        Chain chain = chainZkDao.getByRule(ruleId);
        Tenant tenant = get(chain.getTenantId());

        log.debug("TenantDaoImpl.getByRule exiting: tenant={}", tenant);
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
        log.debug("TenantDaoImpl.getByVpn entered: vpnId={}", vpnId);

        Router router = routerZkDao.getByVpn(vpnId);
        Tenant tenant = get(router.getTenantId());

        log.debug("TenantDaoImpl.getByVpn exiting: tenant={}", tenant);
        return tenant;
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.dao.TenantDao#list()
     */
    @Override
    public List<Tenant> list() throws StateAccessException {
        log.debug("TenantDaoImpl.list entered.");

        String path = pathBuilder.getTenantsPath();
        Set<String> ids = zkDao.getChildren(path, null);
        List<Tenant> tenants = new ArrayList<Tenant>();
        for (String id : ids) {
            tenants.add(get(id));
        }

        log.debug("TenantDaoImpl.list exiting: ids count={}", ids.size());
        return tenants;
    }
}
