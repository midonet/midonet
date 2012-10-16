/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.state.zkManagers;

import com.midokura.midolman.state.*;
import com.midokura.midolman.state.zkManagers.TunnelZkManager.TunnelKey;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Class to manage the port ZooKeeper data.
 */
public class PortZkManager extends ZkManager {

    private final static Logger log = LoggerFactory
            .getLogger(PortZkManager.class);
    private final TunnelZkManager tunnelZkManager;
    private FiltersZkManager filterZkManager;
    private BgpZkManager bgpManager;
    private VpnZkManager vpnManager;
    private RouteZkManager routeZkManager;

    /**
     * Initializes a PortZkManager object with a ZooKeeper client and the root
     * path of the ZooKeeper directory.
     *
     * @param zk
     *            Directory object.
     * @param basePath
     *            The root path.
     */
    public PortZkManager(Directory zk, String basePath) {
        super(zk, basePath);
        tunnelZkManager = new TunnelZkManager(zk, basePath);
        filterZkManager = new FiltersZkManager(zk, basePath);
        this.bgpManager = new BgpZkManager(zk, basePath);
        this.vpnManager = new VpnZkManager(zk, basePath);
        this.routeZkManager = new RouteZkManager(zk, basePath);
    }

    public <T extends PortConfig> T get(UUID id, Class<T> clazz)
            throws StateAccessException {
        return get(id, clazz, null);
    }

    public <T extends PortConfig> T get(UUID id, Class<T> clazz,
            Runnable watcher) throws StateAccessException {
        byte[] data = get(paths.getPortPath(id), watcher);
        return serializer.deserialize(data, clazz);
    }

    public PortConfig get(UUID id, Runnable watcher)
            throws StateAccessException {
        return get(id, PortConfig.class, watcher);
    }

    public PortConfig get(UUID id) throws StateAccessException {
        return get(id, PortConfig.class, null);
    }

    public boolean exists(UUID id) throws StateAccessException {
        return exists(paths.getPortPath(id));
    }

    private void addToPortGroupsOps(List<Op> ops, UUID id,
            Set<UUID> portGroupIds) throws StateAccessException {
        for (UUID portGroupId : portGroupIds) {

            // Check to make sure that the port group path exists.
            String pgPath =  paths.getPortGroupPortsPath(portGroupId);
            if (!exists(pgPath)) {
                throw new IllegalArgumentException("Invalid port group " +
                        "passed in: " + portGroupId);
            }

            ops.add(Op.create(
                    paths.getPortGroupPortPath(portGroupId, id), null,
                    Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        }
    }

    private void deleteFromPortGroupsOps(List<Op> ops, UUID id,
            Set<UUID> portGroupIds) {
        for (UUID portGroupId : portGroupIds) {
            ops.add(Op.delete(
                    paths.getPortGroupPortPath(portGroupId, id), -1));
        }
    }

    private List<Op> prepareRouterPortCreate(UUID id,
            PortDirectory.RouterPortConfig config) throws StateAccessException {
        List<Op> ops = new ArrayList<Op>();

        ops.add(Op.create(paths.getPortPath(id),
                serializer.serialize(config), Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT));
        ops.add(Op.create(paths.getRouterPortPath(config.device_id, id),
                null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        ops.add(Op.create(paths.getPortRoutesPath(id), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));

        ops.addAll(filterZkManager.prepareCreate(id));

        ops.addAll(routeZkManager.prepareLocalRoutesCreate(id, config));

        // If port groups are specified, need to update the membership.
        if (config.portGroupIDs != null) {
            addToPortGroupsOps(ops, id, config.portGroupIDs);
        }
        return ops;
    }

    public List<Op> prepareCreate(UUID id,
            PortDirectory.MaterializedRouterPortConfig config)
            throws StateAccessException {

        // Create a new GRE key. Hide this from outside.
        int tunnelKey = tunnelZkManager.createTunnelKey();
        config.tunnelKey = tunnelKey;

        // Add common router port create operations
        List<Op> ops = prepareRouterPortCreate(id, config);

        // Add materialized port specific operations.
        ops.add(Op.create(paths.getPortBgpPath(id), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        ops.add(Op.create(paths.getPortVpnPath(id), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));

        // Update TunnelKey to reference the port.
        TunnelKey tunnel = new TunnelZkManager.TunnelKey(id);
        ops.addAll(tunnelZkManager.prepareTunnelUpdate(tunnelKey, tunnel));

        return ops;
    }

    public List<Op> prepareCreate(UUID id,
            PortDirectory.LogicalRouterPortConfig config)
            throws StateAccessException {

        // Add common router port create operations
        return prepareRouterPortCreate(id, config);
    }

    private List<Op> prepareBridgePortCreate(UUID id,
            PortDirectory.BridgePortConfig config) throws StateAccessException {

        List<Op> ops = new ArrayList<Op>();

        ops.add(Op.create(paths.getPortPath(id),
                serializer.serialize(config), Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT));

        ops.addAll(filterZkManager.prepareCreate(id));

        // If port groups are specified, need to update the membership.
        if (config.portGroupIDs != null) {
            addToPortGroupsOps(ops, id, config.portGroupIDs);
        }

        return ops;
    }

    public List<Op> prepareCreate(UUID id,
            PortDirectory.MaterializedBridgePortConfig config)
            throws StateAccessException {

        // Create a new GRE key. Hide this from outside.
        int tunnelKey = tunnelZkManager.createTunnelKey();
        config.tunnelKey = tunnelKey;

        // Add common bridge port create operations
        List<Op> ops = prepareBridgePortCreate(id, config);

        // Add materialized bridge port specific operations.
        ops.add(Op.create(paths.getBridgePortPath(config.device_id, id),
                null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));

        // Update TunnelKey to reference the port.
        TunnelKey tunnel = new TunnelKey(id);
        ops.addAll(tunnelZkManager.prepareTunnelUpdate(tunnelKey, tunnel));

        return ops;
    }

    public List<Op> prepareCreate(UUID id,
            PortDirectory.LogicalBridgePortConfig config)
            throws StateAccessException {

        // Add common bridge port create operations
        List<Op> ops = prepareBridgePortCreate(id, config);

        // Add logical bridge port specific operations.
        ops.add(Op.create(
                paths.getBridgeLogicalPortPath(config.device_id, id),
                null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));

        return ops;
    }

    public List<Op> prepareCreate(UUID id, PortConfig config)
            throws StateAccessException {
        if (config instanceof PortDirectory.MaterializedRouterPortConfig) {
            return prepareCreate(id,
                    (PortDirectory.MaterializedRouterPortConfig) config);
        } else if (config instanceof PortDirectory.LogicalRouterPortConfig) {
            return prepareCreate(id,
                    (PortDirectory.LogicalRouterPortConfig) config);
        } else if (config instanceof PortDirectory.LogicalBridgePortConfig) {
            return prepareCreate(id,
                    (PortDirectory.LogicalBridgePortConfig) config);
        } else if (config instanceof PortDirectory.MaterializedBridgePortConfig) {
            return prepareCreate(id,
                    (PortDirectory.MaterializedBridgePortConfig) config);
        } else {
            throw new IllegalArgumentException("Unknown port type found.");
        }
    }

    public UUID create(PortDirectory.MaterializedBridgePortConfig port, UUID id)
            throws StateAccessException, ZkStateSerializationException {
        multi(prepareCreate(id, port));
        return id;
    }

    public UUID create(PortDirectory.LogicalBridgePortConfig port, UUID id)
            throws StateAccessException, ZkStateSerializationException {
        multi(prepareCreate(id, port));
        return id;
    }

    public UUID create(PortDirectory.MaterializedBridgePortConfig port)
            throws StateAccessException, ZkStateSerializationException {
        UUID id = UUID.randomUUID();
        multi(prepareCreate(id, port));
        return id;
    }

    public UUID create(PortDirectory.LogicalBridgePortConfig port)
            throws StateAccessException, ZkStateSerializationException {
        UUID id = UUID.randomUUID();
        multi(prepareCreate(id, port));
        return id;
    }

    public UUID create(PortDirectory.MaterializedRouterPortConfig port, UUID id)
            throws StateAccessException {
        multi(prepareCreate(id, port));
        return id;
    }

    public UUID create(PortDirectory.MaterializedRouterPortConfig port)
            throws StateAccessException {
        UUID id = UUID.randomUUID();
        multi(prepareCreate(id, port));
        return id;
    }

    public UUID create(PortDirectory.LogicalRouterPortConfig port, UUID id)
            throws StateAccessException {
        multi(prepareCreate(id, port));
        return id;
    }

    public UUID create(PortDirectory.LogicalRouterPortConfig port)
            throws StateAccessException {
        UUID id = UUID.randomUUID();
        multi(prepareCreate(id, port));
        return id;
    }

    public UUID create(PortConfig port) throws StateAccessException {
        UUID id = UUID.randomUUID();
        multi(prepareCreate(id, port));
        return id;
    }

    public List<Op> prepareLink(UUID id, UUID peerId)
            throws StateAccessException {

        PortConfig port = get(id);
        if (!(port instanceof  LogicalPortConfig)) {
            throw new IllegalArgumentException("id is not for a logical port:" +
                        id.toString());
        }

        PortConfig peerPort = get(peerId);
        if (!(peerPort instanceof LogicalPortConfig)) {
            throw new IllegalArgumentException("peerId is not for a logical" +
                    " port:" + id.toString());
        }

        LogicalPortConfig typedPort = (LogicalPortConfig) port;
        LogicalPortConfig typedPeerPort = (LogicalPortConfig) peerPort;

        typedPort.setPeerId(peerId);
        typedPeerPort.setPeerId(id);

        List<Op> ops = new ArrayList<Op>();
        ops.add(Op.setData(paths.getPortPath(id),
                serializer.serialize(port), -1));
        ops.add(Op.setData(paths.getPortPath(peerId),
                serializer.serialize(peerPort), -1));

        return ops;
    }

    public List<Op> prepareUnlink(UUID id) throws StateAccessException {

        PortConfig port = get(id);
        if (!(port instanceof  LogicalPortConfig)) {
            throw new IllegalArgumentException("id is not for a logical port:" +
                    id.toString());
        }

        List<Op> ops = new ArrayList<Op>();
        LogicalPortConfig typedPort = (LogicalPortConfig) port;
        if (typedPort.peerId() == null) {
            return ops;
        }

        UUID peerId = typedPort.peerId();
        PortConfig peerPort = get(peerId);
        LogicalPortConfig typedPeerPort = (LogicalPortConfig) peerPort;

        typedPort.setPeerId(null);
        typedPeerPort.setPeerId(null);

        ops.add(Op.setData(paths.getPortPath(id),
                serializer.serialize(port), -1));
        ops.add(Op.setData(paths.getPortPath(peerId),
                serializer.serialize(peerPort), -1));

        return ops;
    }

    public List<Op> prepareUpdate(UUID id, PortConfig config)
            throws StateAccessException {
        List<Op> ops = new ArrayList<Op>();

        // Get the old port config so that we can find differences created from
        // this update that requires other ZK directories to be updated.
        PortConfig oldConfig = get(id);

        // Copy over only the fields that can be updated.
        // portAddr is not among them, otherwise we would have to update the
        // LOCAL routes too.
        oldConfig.inboundFilter = config.inboundFilter;
        oldConfig.outboundFilter = config.outboundFilter;
        oldConfig.properties = config.properties;

        ops.add(Op.setData(paths.getPortPath(id),
                serializer.serialize(oldConfig), -1));

        return ops;
    }

    public void update(UUID id, PortConfig port) throws StateAccessException {
        multi(prepareUpdate(id, port));
    }

    private List<Op> prepareRouterPortDelete(UUID id,
            PortDirectory.RouterPortConfig config) throws StateAccessException {
        List<Op> ops = new ArrayList<Op>();

        List<UUID> routeIds = routeZkManager.listPortRoutes(id, null);
        for (UUID routeId : routeIds) {
            ops.addAll(routeZkManager.prepareRouteDelete(routeId));
        }
        String portRoutesPath = paths.getPortRoutesPath(id);
        log.debug("Preparing to delete: " + portRoutesPath);
        ops.add(Op.delete(portRoutesPath, -1));

        String routerPortPath = paths.getRouterPortPath(config.device_id,
                id);
        log.debug("Preparing to delete: " + routerPortPath);
        ops.add(Op.delete(routerPortPath, -1));

        String portPath = paths.getPortPath(id);
        log.debug("Preparing to delete: " + portPath);
        ops.add(Op.delete(portPath, -1));
        ops.addAll(filterZkManager.prepareDelete(id));

        // Remove the reference of this port from the port groups
        if (config.portGroupIDs != null) {
            deleteFromPortGroupsOps(ops, id, config.portGroupIDs);
        }

        return ops;
    }

    private List<Op> prepareBridgePortDelete(UUID id,
            PortDirectory.BridgePortConfig config) throws StateAccessException {

        // Common operations for deleting logical and materialized
        // bridge ports
        List<Op> ops = new ArrayList<Op>();
        ops.add(Op.delete(paths.getPortPath(id), -1));
        ops.addAll(filterZkManager.prepareDelete(id));

        // Remove the reference of this port from the port groups
        if (config.portGroupIDs != null) {
            deleteFromPortGroupsOps(ops, id, config.portGroupIDs);
        }

        return ops;
    }

    public List<Op> prepareDelete(UUID id,
            PortDirectory.MaterializedRouterPortConfig config)
            throws StateAccessException {

        // Add materialized router port specific operations
        List<Op> ops = new ArrayList<Op>();
        ops.addAll(bgpManager.preparePortDelete(id));
        String path = paths.getPortBgpPath(id);
        log.debug("Preparing to delete: " + path);
        ops.add(Op.delete(path, -1));

        ops.addAll(vpnManager.preparePortDelete(id));
        path = paths.getPortVpnPath(id);
        log.debug("Preparing to delete: {}", path);
        ops.add(Op.delete(path, -1));

        // Remove the reference from the port interface mapping
        if(config.getHostId() != null) {
            path = paths.getHostVrnPortMappingPath(config.getHostId(),
                    id);
            ops.add(Op.delete(path, -1));
        }

        // Get common router port deletion operations
        ops.addAll(prepareRouterPortDelete(id, config));

        return ops;
    }

    public List<Op> prepareDelete(UUID id,
            PortDirectory.LogicalRouterPortConfig config)
            throws StateAccessException {

        List<Op> ops = new ArrayList<Op>();

        // Get logical router port specific operations
        if (config.peerId() != null) {
            ops.addAll(prepareUnlink(id));
        }

        // Get common router port deletion operations
        ops.addAll(prepareRouterPortDelete(id, config));

        return ops;
    }

    public List<Op> prepareDelete(UUID id,
            PortDirectory.MaterializedBridgePortConfig config)
            throws StateAccessException {

        List<Op> ops = new ArrayList<Op>();
        ops.add(Op.delete(paths.getBridgePortPath(config.device_id, id),
                -1));

        // Remove the reference from the port interface mapping
        if(config.getHostId() != null) {
            String path = paths.getHostVrnPortMappingPath(
                    config.getHostId(), id);
            ops.add(Op.delete(path, -1));
        }

        ops.addAll(prepareBridgePortDelete(id, config));

        // Delete the GRE key
        ops.addAll(tunnelZkManager.prepareTunnelDelete(config.tunnelKey));

        return ops;
    }

    public List<Op> prepareDelete(UUID id,
            PortDirectory.LogicalBridgePortConfig config)
            throws StateAccessException {

        List<Op> ops = new ArrayList<Op>();

        // Get logical router port specific operations
        if (config.peerId() != null) {
            ops.addAll(prepareUnlink(id));
        }

        ops.add(Op.delete(
                paths.getBridgeLogicalPortPath(config.device_id, id), -1));
        ops.addAll(prepareBridgePortDelete(id, config));

        return ops;
    }

    public List<Op> prepareDelete(UUID id) throws StateAccessException {
        List<Op> ops = new ArrayList<Op>();

        PortConfig config = get(id);
        if (config == null) {
            return ops;
        }

        // TODO: Find a way to not use instanceof here. Perhaps we should
        // create an Op collection builder class for Port that PortDirectory
        // class takes in as a member. Then we can invoke:
        // portDirectory.prepareDeleteOps();
        if (config instanceof PortDirectory.MaterializedRouterPortConfig) {
            return prepareDelete(id,
                    (PortDirectory.MaterializedRouterPortConfig) config);
        } else if (config instanceof PortDirectory.LogicalRouterPortConfig) {
            return prepareDelete(id,
                    (PortDirectory.LogicalRouterPortConfig) config);
        } else if (config instanceof PortDirectory.LogicalBridgePortConfig) {
            return prepareDelete(id,
                    (PortDirectory.LogicalBridgePortConfig) config);
        } else if (config instanceof PortDirectory.MaterializedBridgePortConfig) {
            return prepareDelete(id,
                    (PortDirectory.MaterializedBridgePortConfig) config);
        } else {
            throw new IllegalArgumentException("Unknown port type found.");
        }
    }

    public Set<UUID> listPortIDs(String path, Runnable watcher)
            throws StateAccessException {
        Set<UUID> result = new HashSet<UUID>();
        Set<String> portIds = getChildren(path, watcher);
        for (String portId : portIds) {
            result.add(UUID.fromString(portId));
        }
        return result;
    }

    /**
     * Gets a list of router port IDs for a given router
     *
     * @param routerId
     *            The ID of the router to find the routes of.
     * @param watcher
     *            The watcher to set on the changes to the ports for this
     *            router.
     * @return A list of router port IDs.
     * @throws StateAccessException
     */
    public Set<UUID> getRouterPortIDs(UUID routerId, Runnable watcher)
            throws StateAccessException {
        return listPortIDs(paths.getRouterPortsPath(routerId), watcher);
    }

    public Set<UUID> getRouterPortIDs(UUID routerId)
            throws StateAccessException {
        return getRouterPortIDs(routerId, null);
    }

    /**
     * Gets a list of bridge port IDs for a given bridge
     *
     * @param bridgeId
     *            The ID of the bridge to find the routes of.
     * @param watcher
     *            The watcher to set on the changes to the ports for this
     *            router.
     * @return A list of bridge port IDs.
     * @throws StateAccessException
     */
    public Set<UUID> getBridgePortIDs(UUID bridgeId, Runnable watcher)
            throws StateAccessException {
        return listPortIDs(paths.getBridgePortsPath(bridgeId), watcher);
    }

    public Set<UUID> getBridgePortIDs(UUID bridgeId)
            throws StateAccessException {
        return getBridgePortIDs(bridgeId, null);
    }

    /**
     * Get the set of IDs of a Bridge's logical ports.
     *
     * @param bridgeId
     *            The ID of the bridge whose logical port IDs to retrieve.
     * @param watcher
     *            The watcher to notify if the set of IDs changes.
     * @return A set of logical port IDs.
     * @throws StateAccessException
     *             If a data error occurs while accessing ZK.
     */
    public Set<UUID> getBridgeLogicalPortIDs(UUID bridgeId, Runnable watcher)
            throws StateAccessException {
        return listPortIDs(paths.getBridgeLogicalPortsPath(bridgeId),
                watcher);
    }

    public Set<UUID> getBridgeLogicalPortIDs(UUID bridgeId)
            throws StateAccessException {
        return getBridgeLogicalPortIDs(bridgeId, null);
    }

    /***
     * Deletes a port and its related data from the ZooKeeper directories
     * atomically.
     *
     * @param id
     *            ID of the port to delete.
     */
    public void delete(UUID id) throws StateAccessException {
        multi(prepareDelete(id));
    }

    public void link(UUID id, UUID peerId) throws StateAccessException {
        List<Op> ops = prepareLink(id, peerId);
        multi(ops);
    }

    public void unlink(UUID id) throws StateAccessException {
        List<Op> ops = prepareUnlink(id);
        multi(ops);
    }

    public Set<UUID> getPortGroupPortIds(UUID portGroupId)
            throws StateAccessException {

        String path = paths.getPortGroupPortsPath(portGroupId);
        Set<String> ids =  getChildren(path);
        Set<UUID> portIds = new HashSet<UUID>(ids.size());
        for (String id : ids) {
            portIds.add(UUID.fromString(id));
        }
        return portIds;

    }

}
