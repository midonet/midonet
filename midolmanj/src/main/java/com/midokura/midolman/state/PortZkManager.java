/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.state;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.state.GreZkManager.GreKey;

/**
 * Class to manage the port ZooKeeper data.
 */
public class PortZkManager extends ZkManager {

    private final static Logger log = LoggerFactory
            .getLogger(PortZkManager.class);
    private final GreZkManager greZkManager;
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
        greZkManager = new GreZkManager(zk, basePath);
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
        byte[] data = get(pathManager.getPortPath(id), watcher);
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
        return exists(pathManager.getPortPath(id));
    }

    private List<Op> prepareRouterPortCreate(UUID id,
            PortDirectory.RouterPortConfig config) throws StateAccessException {
        List<Op> ops = new ArrayList<Op>();

        ops.add(Op.create(pathManager.getPortPath(id),
                serializer.serialize(config), Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT));
        ops.add(Op.create(pathManager.getRouterPortPath(config.device_id, id),
                null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        ops.add(Op.create(pathManager.getPortRoutesPath(id), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));

        ops.addAll(filterZkManager.prepareCreate(id));

        return ops;
    }

    public List<Op> prepareCreate(UUID id,
            PortDirectory.MaterializedRouterPortConfig config)
            throws StateAccessException {

        // Create a new GRE key. Hide this from outside.
        int greKey = greZkManager.createGreKey();
        config.greKey = greKey;

        // Add common router port create operations
        List<Op> ops = prepareRouterPortCreate(id, config);

        // Add materialized port specific operations.
        ops.add(Op.create(pathManager.getPortBgpPath(id), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        ops.add(Op.create(pathManager.getPortVpnPath(id), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));

        // Update GreKey to reference the port.
        GreKey gre = new GreKey(id);
        ops.addAll(greZkManager.prepareGreUpdate(greKey, gre));

        return ops;
    }

    public List<Op> prepareCreate(UUID id,
            PortDirectory.LogicalRouterPortConfig config)
            throws StateAccessException {

        // Add common router port create operations
        return prepareRouterPortCreate(id, config);
    }

    private List<Op> prepareBridgePortCreate(UUID id,
            PortDirectory.BridgePortConfig config)
                    throws StateAccessException {

        List<Op> ops = new ArrayList<Op>();

        ops.add(Op.create(pathManager.getPortPath(id),
                serializer.serialize(config), Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT));

        ops.addAll(filterZkManager.prepareCreate(id));

        return ops;
    }

    public List<Op> prepareCreate(UUID id,
            PortDirectory.MaterializedBridgePortConfig config)
            throws StateAccessException {

        // Create a new GRE key. Hide this from outside.
        int greKey = greZkManager.createGreKey();
        config.greKey = greKey;

        // Add common bridge port create operations
        List<Op> ops = prepareBridgePortCreate(id, config);

        // Add materialized bridge port specific operations.
        ops.add(Op.create(pathManager.getBridgePortPath(config.device_id, id),
                null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));

        // Update GreKey to reference the port.
        GreKey gre = new GreKey(id);
        ops.addAll(greZkManager.prepareGreUpdate(greKey, gre));

        return ops;
    }

    public List<Op> prepareCreate(UUID id,
            PortDirectory.LogicalBridgePortConfig config)
            throws StateAccessException {

        // Add common bridge port create operations
        List<Op> ops = prepareBridgePortCreate(id, config);

        // Add logical bridge port specific operations.
        ops.add(Op.create(
                pathManager.getBridgeLogicalPortPath(config.device_id, id),
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
        } else if (config instanceof
                PortDirectory.MaterializedBridgePortConfig) {
            return prepareCreate(id,
                    (PortDirectory.MaterializedBridgePortConfig) config);
        } else {
            throw new IllegalArgumentException("Unknown port type found.");
        }
    }

    public UUID create(PortDirectory.MaterializedBridgePortConfig port,
            UUID id)
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

    public List<Op> prepareUpdate(UUID id, PortConfig config)
            throws StateAccessException {
        List<Op> ops = new ArrayList<Op>();

        // Currently, the peerId of logical port can be updated from our API.
        // However, there is no need to add such restriction here.
        ops.add(Op.setData(pathManager.getPortPath(id),
                serializer.serialize(config), -1));

        return ops;
    }

    public void update(UUID id, PortConfig port) throws StateAccessException {
        multi(prepareUpdate(id, port));
    }

    public void update(Map<UUID, PortConfig> ports)
            throws StateAccessException {

        List<Op> ops = new ArrayList<Op>();
        for(Map.Entry<UUID, PortConfig> port : ports.entrySet()) {
            ops.addAll(prepareUpdate(port.getKey(), port.getValue()));
        }
        multi(ops);
    }

    private List<Op> prepareRouterPortDelete(UUID id,
            PortDirectory.RouterPortConfig config) throws StateAccessException {
        List<Op> ops = new ArrayList<Op>();

        List<UUID> routeIds = routeZkManager.listPortRoutes(id, null);
        for (UUID routeId : routeIds) {
            ops.addAll(routeZkManager.prepareRouteDelete(routeId));
        }
        String portRoutesPath = pathManager.getPortRoutesPath(id);
        log.debug("Preparing to delete: " + portRoutesPath);
        ops.add(Op.delete(portRoutesPath, -1));

        String routerPortPath = pathManager.getRouterPortPath(config.device_id,
                id);
        log.debug("Preparing to delete: " + routerPortPath);
        ops.add(Op.delete(routerPortPath, -1));

        String portPath = pathManager.getPortPath(id);
        log.debug("Preparing to delete: " + portPath);
        ops.add(Op.delete(portPath, -1));
        ops.addAll(filterZkManager.prepareDelete(id));

        return ops;
    }

    private List<Op> prepareBridgePortDelete(UUID id)
            throws StateAccessException {

        // Common operations for deleting logical and materialized
        // bridge ports
        List<Op> ops = new ArrayList<Op>();
        ops.add(Op.delete(pathManager.getPortPath(id), -1));
        ops.addAll(filterZkManager.prepareDelete(id));
        return ops;
    }

    public List<Op> prepareDelete(UUID id,
            PortDirectory.MaterializedRouterPortConfig config)
            throws StateAccessException {

        // Add materialized router port specific operations
        List<Op> ops = new ArrayList<Op>();
        ops.addAll(bgpManager.preparePortDelete(id));
        String path = pathManager.getPortBgpPath(id);
        log.debug("Preparing to delete: " + path);
        ops.add(Op.delete(path, -1));

        ops.addAll(vpnManager.preparePortDelete(id));
        path = pathManager.getPortVpnPath(id);
        log.debug("Preparing to delete: {}", path);
        ops.add(Op.delete(path, -1));

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
            PortDirectory.LogicalRouterPortConfig peer = get(config.peerId(),
                    PortDirectory.LogicalRouterPortConfig.class);
            peer.setPeerId(null);
            ops.addAll(prepareUpdate(config.peerId(), peer));
        }

        // Get common router port deletion operations
        ops.addAll(prepareRouterPortDelete(id, config));

        return ops;
    }

    public List<Op> prepareDelete(UUID id,
            PortDirectory.MaterializedBridgePortConfig config)
            throws StateAccessException {

        List<Op> ops = new ArrayList<Op>();
        ops.add(Op.delete(pathManager.getBridgePortPath(config.device_id, id),
                -1));
        ops.addAll(prepareBridgePortDelete(id));

        // Delete the GRE key
        ops.addAll(greZkManager.prepareGreDelete(config.greKey));

        return ops;
    }

    public List<Op> prepareDelete(UUID id,
            PortDirectory.LogicalBridgePortConfig config)
            throws StateAccessException {

        List<Op> ops = new ArrayList<Op>();
        ops.add(Op.delete(
                pathManager.getBridgeLogicalPortPath(config.device_id, id), -1));
        ops.addAll(prepareBridgePortDelete(id));

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
        } else if (config instanceof
                PortDirectory.MaterializedBridgePortConfig) {
            return prepareDelete(id,
                    (PortDirectory.MaterializedBridgePortConfig) config);
        } else {
            throw new IllegalArgumentException("Unknown port type found.");
        }
    }

    public void link(UUID localPortId,
            PortDirectory.LogicalRouterPortConfig localPort, UUID peerPortId,
            PortDirectory.LogicalRouterPortConfig peerPort)
            throws StateAccessException {

        localPort.setPeerId(peerPortId);
        peerPort.setPeerId(localPortId);

        List<Op> ops = prepareUpdate(localPortId, localPort);
        ops.addAll(prepareUpdate(peerPortId, peerPort));
        multi(ops);
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
        return listPortIDs(pathManager.getRouterPortsPath(routerId), watcher);
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
        return listPortIDs(pathManager.getBridgePortsPath(bridgeId), watcher);
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
        return listPortIDs(pathManager.getBridgeLogicalPortsPath(bridgeId),
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

}
