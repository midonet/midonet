/*
 * @(#)RouterZkManagerProxy        1.6 19/09/08
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dao.zookeeper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.data.dao.OwnerQueryable;
import com.midokura.midolman.mgmt.data.dao.RouterDao;
import com.midokura.midolman.mgmt.data.dto.LogicalRouterPort;
import com.midokura.midolman.mgmt.data.dto.PeerRouterLink;
import com.midokura.midolman.mgmt.data.dto.Router;
import com.midokura.midolman.mgmt.data.dto.config.PeerRouterConfig;
import com.midokura.midolman.mgmt.data.dto.config.RouterMgmtConfig;
import com.midokura.midolman.mgmt.data.dto.config.RouterNameMgmtConfig;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.PortConfig;
import com.midokura.midolman.state.PortDirectory;
import com.midokura.midolman.state.PortZkManager;
import com.midokura.midolman.state.RouterZkManager;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkNodeEntry;
import com.midokura.midolman.state.ZkStateSerializationException;
import com.midokura.midolman.util.ShortUUID;

public class RouterZkManagerProxy extends ZkMgmtManager implements RouterDao,
        OwnerQueryable {

    private RouterZkManager zkManager = null;
    private final static Logger log = LoggerFactory
            .getLogger(RouterZkManagerProxy.class);

    public RouterZkManagerProxy(Directory zk, String basePath,
            String mgmtBasePath) {
        super(zk, basePath, mgmtBasePath);
        zkManager = new RouterZkManager(zk, basePath);
    }

    public List<Op> prepareRouterCreate(Router router)
            throws ZkStateSerializationException, StateAccessException {
        List<Op> ops = new ArrayList<Op>();
        String routerPath = mgmtPathManager.getRouterPath(router.getId());
        log.debug("Preparing to create:" + routerPath);
        try {
            ops.add(Op.create(routerPath, serialize(router.toMgmtConfig()),
                    Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not serialize RouterMgmtConfig", e,
                    RouterMgmtConfig.class);
        }

        // Add a node to keep track of inter-router connections.
        String routerRouterPath = mgmtPathManager.getRouterRoutersPath(router
                .getId());
        log.debug("Preparing to create:" + routerRouterPath);
        ops.add(Op.create(routerRouterPath, null, Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT));

        // Add under tenant.
        String tenantRouterPath = mgmtPathManager.getTenantRouterPath(
                router.getTenantId(), router.getId());
        log.debug("Preparing to create:" + tenantRouterPath);
        ops.add(Op.create(tenantRouterPath, null, Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT));

        String tenantRouterNamePath = mgmtPathManager.getTenantRouterNamePath(
                router.getTenantId(), router.getName());
        log.debug("Preparing to create:" + tenantRouterNamePath);
        try {
            ops.add(Op.create(tenantRouterNamePath,
                    serialize(router.toNameMgmtConfig()), Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT));
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not serialize RouterNameMgmtConfig", e,
                    RouterNameMgmtConfig.class);
        }

        // Create the Midolman side.
        ops.addAll(zkManager.prepareRouterCreate(router.getId()));

        // Initialize chains directories
        ChainZkManagerProxy chainZkManager = new ChainZkManagerProxy(zk,
                pathManager.getBasePath(), mgmtPathManager.getBasePath());
        ops.addAll(chainZkManager.prepareRouterInit(router.getId()));

        return ops;
    }

    public List<Op> preparePortCreateLink(LogicalRouterPort port)
            throws ZkStateSerializationException {
        List<Op> ops = new ArrayList<Op>();

        ops.add(Op.create(mgmtPathManager.getPortPath(port.getId()), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        ops.add(Op.create(mgmtPathManager.getPortPath(port.getPeerId()), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));

        PortZkManager portZkManager = new PortZkManager(zk,
                pathManager.getBasePath());
        ops.addAll(portZkManager.preparePortCreateLink(port.toZkNode(),
                port.toPeerZkNode()));

        String linkPath = mgmtPathManager.getRouterRouterPath(
                port.getDeviceId(), port.getPeerRouterId());
        log.debug("Preparing to create: " + linkPath);
        try {
            ops.add(Op.create(linkPath, serialize(port.toPeerRouterConfig()),
                    Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not deserialize peer routers to PeerRouterConfig",
                    e, PeerRouterConfig.class);
        }

        linkPath = mgmtPathManager.getRouterRouterPath(port.getPeerRouterId(),
                port.getDeviceId());
        log.debug("Preparing to create: " + linkPath);
        try {
            ops.add(Op.create(linkPath,
                    serialize(port.toPeerPeerRouterConfig()),
                    Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not deserialize reverse peer routers to PeerRouterConfig",
                    e, PeerRouterConfig.class);
        }

        return ops;
    }

    public List<Op> prepareRouterDelete(UUID id) throws StateAccessException,
            ZkStateSerializationException, UnsupportedOperationException {
        return prepareRouterDelete(get(id));
    }

    public List<Op> prepareRouterLinkDelete(UUID routerId, UUID peerRouterId,
            boolean cascade) throws StateAccessException,
            ZkStateSerializationException, UnsupportedOperationException {
        List<Op> ops = new ArrayList<Op>();

        // Get the peer info
        PeerRouterLink link = getPeerRouterLink(routerId, peerRouterId);
        String path = mgmtPathManager.getRouterRouterPath(routerId,
                peerRouterId);
        log.debug("Preparing to delete: " + path);
        ops.add(Op.delete(path, -1));
        path = mgmtPathManager.getRouterRouterPath(peerRouterId, routerId);
        log.debug("Preparing to delete: " + path);
        ops.add(Op.delete(path, -1));

        // Delete logical ports
        PortZkManagerProxy proxy = new PortZkManagerProxy(zk,
                pathManager.getBasePath(), mgmtPathManager.getBasePath());
        ops.addAll(proxy.prepareDelete(link.getPortId(), false, true));
        ops.addAll(proxy.prepareDelete(link.getPeerPortId(), false, true));
        if (cascade) {
            PortZkManager manager = new PortZkManager(zk,
                    pathManager.getBasePath());
            ops.addAll(manager.preparePortDelete(link.getPortId()));
        }
        return ops;
    }

    public List<Op> prepareRouterDelete(Router router)
            throws StateAccessException, ZkStateSerializationException,
            UnsupportedOperationException {

        List<Op> ops = new ArrayList<Op>();

        // Delete router in Midolman side.
        ops.addAll(zkManager.prepareRouterDelete(router.getId()));

        // Delete the chains
        ChainZkManagerProxy chainManager = new ChainZkManagerProxy(zk,
                pathManager.getBasePath(), mgmtPathManager.getBasePath());
        ops.addAll(chainManager.prepareRouterDelete(router.getId(), false));

        // Delete the tenant router entry
        String tenantRouterNamePath = mgmtPathManager.getTenantRouterNamePath(
                router.getTenantId(), router.getName());
        log.debug("Preparing to delete:" + tenantRouterNamePath);
        ops.add(Op.delete(tenantRouterNamePath, -1));

        // Get all the paths to delete
        String tenantRouterPath = mgmtPathManager.getTenantRouterPath(
                router.getTenantId(), router.getId());
        log.debug("Preparing to delete: " + tenantRouterPath);
        ops.add(Op.delete(tenantRouterPath, -1));

        // Remove the router-router mappings - do this before deleting ports
        // to remove logical ports first.
        Set<String> peers = getChildren(
                mgmtPathManager.getRouterRoutersPath(router.getId()), null);
        for (String peer : peers) {
            ops.addAll(prepareRouterLinkDelete(router.getId(),
                    UUID.fromString(peer), false));
        }
        String routerRouterPath = mgmtPathManager.getRouterRoutersPath(router
                .getId());
        log.debug("Preparing to delete: " + routerRouterPath);
        ops.add(Op.delete(routerRouterPath, -1));

        // Remove all the ports in mgmt directory but don't cascade here.
        PortZkManagerProxy portMgr = new PortZkManagerProxy(zk,
                pathManager.getBasePath(), mgmtPathManager.getBasePath());
        PortZkManager portZkManager = new PortZkManager(zk,
                pathManager.getBasePath());
        List<ZkNodeEntry<UUID, PortConfig>> portNodes = portZkManager
                .listRouterPorts(router.getId());
        for (ZkNodeEntry<UUID, PortConfig> portNode : portNodes) {
            if (portNode.value instanceof PortDirectory.MaterializedRouterPortConfig) {
                ops.addAll(portMgr.prepareDelete(portNode.key, false));
            }
        }

        String routerPath = mgmtPathManager.getRouterPath(router.getId());
        log.debug("Preparing to delete: " + routerPath);
        ops.add(Op.delete(routerPath, -1));
        return ops;
    }

    @Override
    public UUID create(Router router) throws StateAccessException {
        if (null == router.getId()) {
            router.setId(UUID.randomUUID());
        }
        multi(prepareRouterCreate(router));
        return router.getId();
    }

    @Override
    public PeerRouterLink createLink(LogicalRouterPort port)
            throws StateAccessException {
        // Check that they are not currently linked.
        if (exists(mgmtPathManager.getRouterRouterPath(port.getDeviceId(),
                port.getPeerRouterId()))) {
            throw new IllegalArgumentException(
                    "Invalid connection.  The router ports are already connected.");
        }
        UUID portId = ShortUUID.generate32BitUUID();
        UUID peerPortId = ShortUUID.generate32BitUUID();
        port.setId(portId);
        port.setPeerId(peerPortId);
        multi(preparePortCreateLink(port));
        return port.toPeerRouterLink();
    }

    @Override
    public void deleteLink(UUID routerId, UUID peerRouterId)
            throws StateAccessException {
        if (!exists(mgmtPathManager.getRouterRouterPath(routerId, peerRouterId))) {
            throw new IllegalArgumentException(
                    "Invalid operation.  The router ports are not connected.");
        }

        multi(prepareRouterLinkDelete(routerId, peerRouterId, true));
    }

    @Override
    public Router get(UUID id) throws StateAccessException {
        byte[] data = get(mgmtPathManager.getRouterPath(id), null);
        RouterMgmtConfig config = null;
        try {
            config = deserialize(data, RouterMgmtConfig.class);
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not deserialize router " + id
                            + " to RouterMgmtConfig", e, RouterMgmtConfig.class);
        }
        return Router.createRouter(id, config);
    }

    @Override
    public PeerRouterLink getPeerRouterLink(UUID routerId, UUID peerRouterId)
            throws StateAccessException {
        byte[] data = get(
                mgmtPathManager.getRouterRouterPath(routerId, peerRouterId),
                null);
        try {
            return PeerRouterLink.createPeerRouterLink(deserialize(data,
                    PeerRouterConfig.class));
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not deserialize peer router " + routerId
                            + " to PeerRouterConfig", e, PeerRouterConfig.class);
        }
    }

    @Override
    public List<Router> list(String tenantId) throws StateAccessException {
        List<Router> result = new ArrayList<Router>();
        Set<String> routerIds = getChildren(
                mgmtPathManager.getTenantRoutersPath(tenantId), null);
        for (String routerId : routerIds) {
            // For now, get each one.
            result.add(get(UUID.fromString(routerId)));
        }
        return result;
    }

    @Override
    public void update(Router router) throws StateAccessException {
        // Update any version for now.
        Router r = get(router.getId());
        r.setName(router.getName());
        String path = mgmtPathManager.getRouterPath(r.getId());

        try {
            update(path, serialize(r.toMgmtConfig()));
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not serialize router mgmt " + r.getId()
                            + " to RouterMgmtConfig", e, RouterMgmtConfig.class);
        }
    }

    @Override
    public void delete(UUID id) throws StateAccessException {
        multi(prepareRouterDelete(id));
    }

    @Override
    public String getOwner(UUID id) throws StateAccessException {
        return get(id).getTenantId();
    }

}
