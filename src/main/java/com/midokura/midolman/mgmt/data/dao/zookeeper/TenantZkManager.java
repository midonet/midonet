/*
 * @(#)TenantZkManager        1.6 19/09/08
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dao.zookeeper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.data.dao.TenantDao;
import com.midokura.midolman.mgmt.data.dto.Tenant;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkPathManager;

/**
 * Abstract base class for TenantZkManager.
 *
 * @version 1.6 19 Sept 2011
 * @author Ryu Ishimoto
 */
public class TenantZkManager implements TenantDao {

    private ZkMgmtPathManager zkMgmtPathManager = null;
    private ZkPathManager pathManager = null;
    private Directory zk = null;
    private final static Logger log = LoggerFactory
            .getLogger(TenantZkManager.class);

    /**
     * TenantZkManager constructor.
     *
     * @param zk
     *            ZooKeeper object.
     * @param basePath
     *            Directory to set as the base.
     */
    public TenantZkManager(Directory zk, String basePath, String mgmtBasePath) {
        zkMgmtPathManager = new ZkMgmtPathManager(mgmtBasePath);
        this.pathManager = new ZkPathManager(basePath);
        this.zk = zk;
    }

    @Override
    public void delete(String id) throws StateAccessException {
        try {
            List<Op> ops = new ArrayList<Op>();
            Set<String> routers = zk.getChildren(
                    zkMgmtPathManager.getTenantRoutersPath(id), null);
            RouterZkManagerProxy routerManager = new RouterZkManagerProxy(zk,
                    pathManager.getBasePath(), zkMgmtPathManager.getBasePath());
            for (String router : routers) {
                ops.addAll(routerManager.prepareRouterDelete(UUID
                        .fromString(router)));
            }

            Set<String> bridges = zk.getChildren(
                    zkMgmtPathManager.getTenantBridgesPath(id), null);
            BridgeZkManagerProxy bridgeManager = new BridgeZkManagerProxy(zk,
                    pathManager.getBasePath(), zkMgmtPathManager.getBasePath());
            for (String bridge : bridges) {
                ops.addAll(bridgeManager.prepareDelete(UUID.fromString(bridge)));
            }

            String path = zkMgmtPathManager.getTenantBridgeNamesPath(id);
            log.debug("Preparing to delete: " + path);
            ops.add(Op.delete(path, -1));
            path = zkMgmtPathManager.getTenantRouterNamesPath(id);
            log.debug("Preparing to delete: " + path);
            ops.add(Op.delete(path, -1));
            path = zkMgmtPathManager.getTenantBridgesPath(id);
            log.debug("Preparing to delete: " + path);
            ops.add(Op.delete(path, -1));
            path = zkMgmtPathManager.getTenantRoutersPath(id);
            log.debug("Preparing to delete: " + path);
            ops.add(Op.delete(path, -1));
            path = zkMgmtPathManager.getTenantPath(id);
            log.debug("Preparing to delete: " + path);
            ops.add(Op.delete(path, -1));
            zk.multi(ops);
        } catch (Exception ex) {
            throw new StateAccessException("ZK error", ex);
        }
    }

    /**
     * Add a new tenant entry in the ZooKeeper directory.
     *
     * @param id
     *            Tenant UUID
     * @param tenant
     *            TenantConfig object to store tenant data.
     * @throws KeeperException
     *             General ZooKeeper exception.
     * @throws InterruptedException
     *             Unresponsive thread getting interrupted by another thread.
     */
    public String create(String id) throws StateAccessException {
        if (null == id) {
            id = UUID.randomUUID().toString();
        }
        List<Op> ops = new ArrayList<Op>();
        ops.add(Op.create(zkMgmtPathManager.getTenantPath(id), "".getBytes(),
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        ops.add(Op.create(zkMgmtPathManager.getTenantRoutersPath(id), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        ops.add(Op.create(zkMgmtPathManager.getTenantBridgesPath(id), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        ops.add(Op.create(zkMgmtPathManager.getTenantRouterNamesPath(id), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        ops.add(Op.create(zkMgmtPathManager.getTenantBridgeNamesPath(id), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        try {
            zk.multi(ops);
        } catch (Exception ex) {
            throw new StateAccessException("ZK error", ex);
        }
        return id;
    }

    @Override
    public List<Tenant> list() throws StateAccessException {
        try {
            List<Tenant> tenants = new ArrayList<Tenant>();
            Set<String> tenantIds = zk.getChildren(
                    zkMgmtPathManager.getTenantsPath(), null);
            for (String tenantId : tenantIds) {
                // For now, get each one.
                Tenant t = new Tenant();
                t.setId(tenantId);
                tenants.add(t);
            }
            return tenants;
        } catch (Exception ex) {
            throw new StateAccessException("ZK error", ex);
        }
    }

    @Override
    public Tenant get(String id) throws StateAccessException {
        // Call get to generate the StateAccessException if the tenant hasn't
        // been created yet.
        try {
            zk.get(zkMgmtPathManager.getTenantPath(id), null);
        } catch (Exception ex) {
            throw new StateAccessException("ZK error", ex);
        }
        Tenant t = new Tenant();
        t.setId(id);
        return t;
    }

    @Override
    public String create(Tenant tenant) throws StateAccessException {
        return create(tenant.getId());
    }

    @Override
    public Tenant getByAdRoute(UUID adRouteId) throws StateAccessException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Tenant getByBgp(UUID bgpId) throws StateAccessException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Tenant getByBridge(UUID bridgeId) throws StateAccessException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Tenant getByChain(UUID chainId) throws StateAccessException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Tenant getByPort(UUID portId) throws StateAccessException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Tenant getByRoute(UUID routeId) throws StateAccessException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Tenant getByRouter(UUID routerId) throws StateAccessException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Tenant getByRule(UUID ruleId) throws StateAccessException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Tenant getByVpn(UUID vpnId) throws StateAccessException {
        // TODO Auto-generated method stub
        return null;
    }
}
