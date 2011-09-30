/*
 * @(#)BridgeZkManagerProxy        1.6 19/09/08
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dao;

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

import com.midokura.midolman.mgmt.data.OwnerQueryable;
import com.midokura.midolman.mgmt.data.dto.Bridge;
import com.midokura.midolman.state.BridgeZkManager;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkNodeEntry;
import com.midokura.midolman.state.ZkStateSerializationException;
import com.midokura.midolman.state.BridgeZkManager.BridgeConfig;

/**
 * Class to manage the bridge ZooKeeper data.
 * 
 * @version 1.6 19 Sept 2011
 * @author Ryu Ishimoto
 */
public class BridgeZkManagerProxy extends ZkMgmtManager implements
        OwnerQueryable {

    public static class BridgeMgmtConfig {

        public BridgeMgmtConfig() {
            super();
        }

        public BridgeMgmtConfig(UUID tenantId, String name) {
            super();
            this.tenantId = tenantId;
            this.name = name;
        }

        public UUID tenantId;
        public String name;
    }

    public static class BridgeNameMgmtConfig {
        public BridgeNameMgmtConfig() {
            super();
        }

        public BridgeNameMgmtConfig(UUID id) {
            super();
            this.id = id;
        }

        public UUID id;
    }

    private BridgeZkManager zkManager = null;
    private final static Logger log = LoggerFactory
            .getLogger(BridgeZkManagerProxy.class);

    public BridgeZkManagerProxy(Directory zk, String basePath,
            String mgmtBasePath) {
        super(zk, basePath, mgmtBasePath);
        zkManager = new BridgeZkManager(zk, basePath);
    }

    public List<Op> prepareCreate(Bridge bridge) throws StateAccessException,
            ZkStateSerializationException {
        List<Op> ops = new ArrayList<Op>();

        // Create the root bridge path
        String bridgePath = mgmtPathManager.getBridgePath(bridge.getId());
        log.debug("Preparing to create: " + bridgePath);
        try {
            ops.add(Op.create(bridgePath, serialize(bridge.toMgmtConfig()),
                    Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Serialization error occurred while preparing bridge creation multi ops for UUID "
                            + bridge.getId(), e, BridgeMgmtConfig.class);
        }

        // Add under tenant.
        String tenantBridgePath = mgmtPathManager.getTenantBridgePath(bridge
                .getTenantId(), bridge.getId());
        log.debug("Preparing to create: " + tenantBridgePath);
        ops.add(Op.create(tenantBridgePath, null, Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT));

        String tenantBridgeNamePath = mgmtPathManager.getTenantBridgeNamePath(
                bridge.getTenantId(), bridge.getName());
        log.debug("Preparing to create:" + tenantBridgeNamePath);
        try {
            ops.add(Op.create(tenantBridgeNamePath, serialize(bridge
                    .toNameMgmtConfig()), Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT));
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not serialize BridgeNameMgmtConfig", e,
                    BridgeNameMgmtConfig.class);
        }

        // Create Midolman data
        ops.addAll(zkManager.prepareBridgeCreate(bridge.getId(),
                new BridgeConfig()));

        return ops;
    }

    public List<Op> prepareDelete(UUID id) throws StateAccessException,
            ZkStateSerializationException {
        return prepareDelete(get(id));
    }

    public List<Op> prepareDelete(Bridge bridge) throws StateAccessException,
            ZkStateSerializationException {
        List<Op> ops = new ArrayList<Op>();

        // Delete the Midolman side.
        ZkNodeEntry<UUID, BridgeConfig> bridgeNode = zkManager.get(bridge
                .getId());
        ops.addAll(zkManager.prepareBridgeDelete(bridgeNode));

        // Delete the tenant router entry
        String tenantBridgeNamePath = mgmtPathManager.getTenantBridgeNamePath(
                bridge.getTenantId(), bridge.getName());
        log.debug("Preparing to delete:" + tenantBridgeNamePath);
        ops.add(Op.delete(tenantBridgeNamePath, -1));

        String tenantBridgePath = mgmtPathManager.getTenantBridgePath(bridge
                .getTenantId(), bridge.getId());
        log.debug("Preparing to delete: " + tenantBridgePath);
        ops.add(Op.delete(tenantBridgePath, -1));

        // Delete the root bridge path.
        String bridgePath = mgmtPathManager.getBridgePath(bridge.getId());
        log.debug("Preparing to delete: " + bridgePath);
        ops.add(Op.delete(bridgePath, -1));

        // Remove all the ports in mgmt directory but don't cascade here.
        PortZkManagerProxy portMgr = new PortZkManagerProxy(zk, pathManager
                .getBasePath(), mgmtPathManager.getBasePath());
        ops.addAll(portMgr.prepareBridgeDelete(bridge.getId()));

        return ops;
    }

    public UUID create(Bridge bridge) throws StateAccessException,
            ZkStateSerializationException {
        UUID id = UUID.randomUUID();
        bridge.setId(id);
        multi(prepareCreate(bridge));
        return id;
    }

    public Bridge get(UUID id) throws StateAccessException,
            ZkStateSerializationException {
        byte[] data = get(mgmtPathManager.getBridgePath(id));
        BridgeMgmtConfig config = null;
        try {
            config = deserialize(data, BridgeMgmtConfig.class);
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Serialization error occurred while getting the bridge with UUID "
                            + id, e, BridgeMgmtConfig.class);
        }
        return Bridge.createBridge(id, config);
    }

    public List<Bridge> list(UUID tenantId) throws StateAccessException,
            ZkStateSerializationException {
        List<Bridge> result = new ArrayList<Bridge>();
        String path = mgmtPathManager.getTenantBridgesPath(tenantId);
        Set<String> ids = getChildren(path);
        for (String id : ids) {
            // For now, get each one.
            result.add(get(UUID.fromString(id)));
        }
        return result;
    }

    public void update(Bridge bridge) throws StateAccessException,
            ZkStateSerializationException {
        Bridge b = get(bridge.getId());
        b.setName(bridge.getName());

        String bridgePath = mgmtPathManager.getBridgePath(b.getId());
        log.debug("Updating path: " + bridgePath);
        try {
            update(bridgePath, serialize(b.toMgmtConfig()));
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Serialization error occurred while updating the bridge with UUID "
                            + b.getId(), e, BridgeMgmtConfig.class);
        }
    }

    public void delete(UUID id) throws StateAccessException,
            ZkStateSerializationException {
        multi(prepareDelete(id));
    }

    @Override
    public UUID getOwner(UUID id) throws StateAccessException,
            ZkStateSerializationException {
        return get(id).getTenantId();
    }
}
