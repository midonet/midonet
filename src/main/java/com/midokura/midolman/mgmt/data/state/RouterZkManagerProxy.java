/*
 * @(#)RouterZkManagerProxy        1.6 19/09/08
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.state;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooDefs.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.data.dto.LogicalRouterPort;
import com.midokura.midolman.mgmt.data.dto.PeerRouterLink;
import com.midokura.midolman.mgmt.data.dto.Router;
import com.midokura.midolman.state.PortZkManager;
import com.midokura.midolman.state.RouterZkManager;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkNodeEntry;
import com.midokura.midolman.state.ZkStateSerializationException;
import com.midokura.midolman.state.PortDirectory.PortConfig;
import com.midokura.midolman.util.ShortUUID;

public class RouterZkManagerProxy extends ZkMgmtManager {

    public static class RouterMgmtConfig {

        public RouterMgmtConfig() {
            super();
        }

        public RouterMgmtConfig(UUID tenantId, String name) {
            super();
            this.tenantId = tenantId;
            this.name = name;
        }

        public UUID tenantId;
        public String name;
    }

    public static class PeerRouterConfig {
        public PeerRouterConfig() {
            super();
        }

        public PeerRouterConfig(UUID portId, UUID peerPortId) {
            super();
            this.portId = portId;
            this.peerPortId = peerPortId;
        }

        public UUID portId;
        public UUID peerPortId;
    }

    private RouterZkManager zkManager = null;
    private final static Logger log = LoggerFactory
            .getLogger(RouterZkManagerProxy.class);

    public RouterZkManagerProxy(ZooKeeper zk, String basePath,
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
        String tenantRouterPath = mgmtPathManager.getTenantRouterPath(router
                .getTenantId(), router.getId());
        log.debug("Preparing to create:" + tenantRouterPath);
        ops.add(Op.create(tenantRouterPath, null, Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT));

        // Create the Midolman side.
        ops.addAll(zkManager.prepareRouterCreate(router.getId()));

        // Initialize chains directories
        ChainZkManagerProxy chainZkManager = new ChainZkManagerProxy(zooKeeper,
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

        PortZkManager portZkManager = new PortZkManager(zooKeeper, pathManager
                .getBasePath());
        ops.addAll(portZkManager.preparePortCreateLink(port.toZkNode(), port
                .toPeerZkNode()));

        String linkPath = mgmtPathManager.getRouterRouterPath(port
                .getDeviceId(), port.getPeerRouterId());
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
            ZkStateSerializationException {
        return prepareRouterDelete(get(id));
    }

    public List<Op> prepareRouterDelete(Router router)
            throws StateAccessException, ZkStateSerializationException {

        List<Op> ops = new ArrayList<Op>();

        // Delete router in Midolman side.
        ops.addAll(zkManager.prepareRouterDelete(router.getId()));

        // Delete the chains
        ChainZkManagerProxy chainManager = new ChainZkManagerProxy(zooKeeper,
                pathManager.getBasePath(), mgmtPathManager.getBasePath());
        chainManager.prepareRouterDelete(router.getId(), false);

        // Delete the tenant router entry
        // Get all the paths to delete
        String tenantRouterPath = mgmtPathManager.getTenantRouterPath(router
                .getTenantId(), router.getId());
        log.debug("Preparing to delete: " + tenantRouterPath);
        ops.add(Op.delete(tenantRouterPath, -1));

        // Remove all the ports in mgmt directory but don't cascade here.
        PortZkManagerProxy portMgr = new PortZkManagerProxy(zooKeeper,
                pathManager.getBasePath(), mgmtPathManager.getBasePath());
        PortZkManager portZkManager = new PortZkManager(zk, pathManager
                .getBasePath());
        List<ZkNodeEntry<UUID, PortConfig>> portNodes = portZkManager
                .listRouterPorts(router.getId());
        for (ZkNodeEntry<UUID, PortConfig> portNode : portNodes) {
            ops.addAll(portMgr.prepareDelete(portNode.key, false));
            // TODO: Remove VIF if plugged, and remove peer port for logical
        }

        // Remove the router-router mappings
        Set<String> peers = getChildren(mgmtPathManager
                .getRouterRoutersPath(router.getId()), null);
        for (String peer : peers) {
            UUID peerId = UUID.fromString(peer);
            ops.add(Op.delete(mgmtPathManager.getRouterRouterPath(router
                    .getId(), peerId), -1));
            ops.add(Op.delete(mgmtPathManager.getRouterRouterPath(peerId,
                    router.getId()), -1));
        }
        ops.add(Op.delete(mgmtPathManager.getRouterRoutersPath(router.getId()),
                -1));

        ops.add(Op.delete(mgmtPathManager.getRouterPath(router.getId()), -1));
        return ops;
    }

    public UUID create(Router router) throws StateAccessException,
            ZkStateSerializationException {
        UUID id = UUID.randomUUID();
        router.setId(id);
        multi(prepareRouterCreate(router));
        return id;
    }

    public PeerRouterLink createLink(LogicalRouterPort port)
            throws StateAccessException, ZkStateSerializationException {
        // Check that they are not currently linked.
        if (exists(mgmtPathManager.getRouterRouterPath(port.getDeviceId(), port
                .getPeerRouterId()))) {
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

    public Router get(UUID id) throws StateAccessException,
            ZkStateSerializationException {
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

    public PeerRouterLink getPeerRouterLink(UUID routerId, UUID peerRouterId)
            throws StateAccessException, ZkStateSerializationException {
        byte[] data = get(mgmtPathManager.getRouterRouterPath(routerId,
                peerRouterId), null);
        try {
            return PeerRouterLink.createPeerRouterLink(deserialize(data,
                    PeerRouterConfig.class));
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not deserialize peer router " + routerId
                            + " to PeerRouterConfig", e, PeerRouterConfig.class);
        }
    }

    public List<Router> list(UUID tenantId) throws StateAccessException,
            ZkStateSerializationException {
        List<Router> result = new ArrayList<Router>();
        Set<String> routerIds = getChildren(mgmtPathManager
                .getTenantRoutersPath(tenantId), null);
        for (String routerId : routerIds) {
            // For now, get each one.
            result.add(get(UUID.fromString(routerId)));
        }
        return result;
    }

    public void update(Router router) throws StateAccessException,
            ZkStateSerializationException {
        // Update any version for now.
        String path = mgmtPathManager.getRouterPath(router.getId());

        try {
            update(path, serialize(router.toMgmtConfig()));
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not serialize router mgmt " + router.getId()
                            + " to RouterMgmtConfig", e, RouterMgmtConfig.class);
        }
    }

    public void delete(UUID id) throws StateAccessException,
            ZkStateSerializationException {
        multi(prepareRouterDelete(id));
    }

}
