/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.state;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.layer3.Route;
import com.midokura.midolman.state.GreZkManager.GreKey;

/**
 * Class to manage the port ZooKeeper data.
 */
public class PortZkManager extends ZkManager {

    private final static Logger log =
        LoggerFactory.getLogger(PortZkManager.class);
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

    private List<Op> prepareRouterPortCreate(
            ZkNodeEntry<UUID, PortConfig> portNode)
            throws ZkStateSerializationException {
        List<Op> ops = new ArrayList<Op>();
        try {
            ops.add(Op.create(pathManager.getPortPath(portNode.key),
                    serialize(portNode.value), Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT));
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not serialize PortConfig", e, PortConfig.class);
        }
        ops.add(Op.create(pathManager.getRouterPortPath(
                portNode.value.device_id, portNode.key), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        ops.add(Op.create(pathManager.getPortRoutesPath(portNode.key), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));

        if (portNode.value instanceof PortDirectory.MaterializedRouterPortConfig) {
            ops.add(Op.create(pathManager.getPortBgpPath(portNode.key), null,
                    Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
            ops.add(Op.create(pathManager.getPortVpnPath(portNode.key), null,
                    Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        }
        ops.addAll(filterZkManager.prepareCreate(portNode.key));
        return ops;
    }

    private List<Op> prepareBridgePortCreate(
            ZkNodeEntry<UUID, PortConfig> portNode)
            throws ZkStateSerializationException {
        List<Op> ops = new ArrayList<Op>();

        try {
            ops.add(Op.create(pathManager.getPortPath(portNode.key),
                    serialize(portNode.value), Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT));
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not serialize PortConfig", e, PortConfig.class);
        }

        String bridgePortPath =
                portNode.value instanceof PortDirectory.LogicalBridgePortConfig
                        ? pathManager.getBridgeLogicalPortPath(
                                portNode.value.device_id, portNode.key)
                        : pathManager.getBridgePortPath(
                                portNode.value.device_id, portNode.key);
        ops.add(Op.create(bridgePortPath, null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        ops.addAll(filterZkManager.prepareCreate(portNode.key));
        return ops;
    }

    public List<Op> preparePortCreate(UUID id, PortConfig portNode)
            throws StateAccessException {
        return preparePortCreate(new ZkNodeEntry<UUID, PortConfig>(id, portNode));
    }

    /**
     * Constructs a list of ZooKeeper update operations to perform when adding a
     * new port.
     *
     * @param entry
     *            ZooKeeper node representing a key-value entry of port UUID and
     *            PortConfig object.
     * @return A list of Op objects to represent the operations to perform.
     * @throws ZkStateSerializationException
     *             Serialization error occurred.
     */
    public List<Op> preparePortCreate(ZkNodeEntry<UUID, PortConfig> entry)
            throws StateAccessException {

        ZkNodeEntry<Integer, GreKey> gre = null;
        if (!(entry.value instanceof LogicalPortConfig)) {
            // Create a new GRE key. Hide this from outside.
            gre = greZkManager.createGreKey();
            entry.value.greKey = gre.key;
        }
        List<Op> ops = new ArrayList<Op>();

        if (entry.value instanceof PortDirectory.BridgePortConfig) {
            ops.addAll(prepareBridgePortCreate(entry));
        } else if (entry.value
                instanceof PortDirectory.RouterPortConfig) {
            ops.addAll(prepareRouterPortCreate(entry));
        } else {
            throw new IllegalArgumentException("Unsupported port type");
        }

        if (gre != null) {
            // Update GreKey to reference the port.
            gre.value.ownerId = entry.key;
            ops.addAll(greZkManager.prepareGreUpdate(gre));
        }
        return ops;
    }

    private List<Op> prepareRouterPortDelete(ZkNodeEntry<UUID, PortConfig> entry)
            throws StateAccessException {
        List<Op> ops = new ArrayList<Op>();
        if (entry.value instanceof PortDirectory.MaterializedRouterPortConfig) {
            ops.addAll(bgpManager.preparePortDelete(entry.key));
            String path = pathManager.getPortBgpPath(entry.key);
            log.debug("Preparing to delete: " + path);
            ops.add(Op.delete(path, -1));

            ops.addAll(vpnManager.preparePortDelete(entry.key));
            path = pathManager.getPortVpnPath(entry.key);
            log.debug("Preparing to delete: {}", path);
            ops.add(Op.delete(path, -1));
        } else if(entry.value instanceof PortDirectory.LogicalRouterPortConfig){
            // Update the peer
            PortDirectory.LogicalRouterPortConfig logicalPort =
                    (PortDirectory.LogicalRouterPortConfig) entry.value;
            if (logicalPort.peerId() != null) {
                ZkNodeEntry<UUID, PortConfig> peer = get(logicalPort.peerId());
                if (peer.value instanceof LogicalPortConfig) {
                    ((LogicalPortConfig) peer.value).setPeerId(null);
                    ops.addAll(preparePortUpdate(peer));
                }
            }
        }
        List<ZkNodeEntry<UUID, Route>> routes = routeZkManager.listPortRoutes(
                entry.key, null);
        for (ZkNodeEntry<UUID, Route> route : routes) {
            ops.addAll(routeZkManager.prepareRouteDelete(route));
        }
        String portRoutesPath = pathManager.getPortRoutesPath(entry.key);
        log.debug("Preparing to delete: " + portRoutesPath);
        ops.add(Op.delete(portRoutesPath, -1));

        String routerPortPath = pathManager.getRouterPortPath(
                entry.value.device_id, entry.key);
        log.debug("Preparing to delete: " + routerPortPath);
        ops.add(Op.delete(routerPortPath, -1));

        String portPath = pathManager.getPortPath(entry.key);
        log.debug("Preparing to delete: " + portPath);
        ops.add(Op.delete(portPath, -1));
        ops.addAll(filterZkManager.prepareDelete(entry.key));
        return ops;
    }

    /**
     * Constructs a list of operations to perform in a bridge port deletion.
     *
     * @param entry
     *            Port ZooKeeper entry to delete.
     * @return A list of Op objects representing the operations to perform.
     * @throws ZkStateSerializationException
     *             Serialization error occurred.
     */
    private List<Op> prepareBridgePortDelete(ZkNodeEntry<UUID, PortConfig> entry)
            throws StateAccessException {
        List<Op> ops = new ArrayList<Op>();
        String bridgePortPath =
                entry.value instanceof PortDirectory.LogicalBridgePortConfig
                        ? pathManager.getBridgeLogicalPortPath(
                        entry.value.device_id, entry.key)
                        : pathManager.getBridgePortPath(
                        entry.value.device_id, entry.key);
        ops.add(Op.delete(bridgePortPath, -1));
        ops.add(Op.delete(pathManager.getPortPath(entry.key), -1));
        ops.addAll(filterZkManager.prepareDelete(entry.key));
        return ops;
    }

    public List<Op> preparePortDelete(UUID id) throws StateAccessException,
            ZkStateSerializationException {
        return preparePortDelete(get(id));
    }

    /**
     * Constructs a list of operations to perform in a port deletion.
     *
     * @param entry
     *            Port ZooKeeper entry to delete.
     * @return A list of Op objects representing the operations to perform.
     * @throws ZkStateSerializationException
     *             Serialization error occurred.
     */
    public List<Op> preparePortDelete(ZkNodeEntry<UUID, PortConfig> entry)
            throws StateAccessException {
        List<Op> res;
        if (entry.value instanceof PortDirectory.BridgePortConfig) {
            res = prepareBridgePortDelete(entry);
        } else if (entry.value instanceof PortDirectory.RouterPortConfig) {
            res = prepareRouterPortDelete(entry);
        } else {
            throw new IllegalArgumentException("Unsupported port type");
        }

        if (!(entry.value instanceof LogicalPortConfig)) {
            res.addAll(greZkManager.prepareGreDelete(entry.value.greKey));
        }
        return res;
    }

    public List<Op> preparePortUpdate(ZkNodeEntry<UUID, PortConfig> entry)
            throws StateAccessException {
        List<Op> ops = new ArrayList<Op>();

        // For now, only logical port supported
        if (entry.value instanceof LogicalPortConfig) {
            try {
                ops.add(Op.setData(pathManager.getPortPath(entry.key),
                        serialize(entry.value), -1));
            } catch (IOException e) {
                throw new ZkStateSerializationException(
                        "Could not deserialize port " + entry.key
                        + " to PortConfig", e, PortConfig.class);
            }
        } else {
            throw new UnsupportedOperationException(
                    "Can only update logical port");
        }

        return ops;
    }

    /**
     * Performs an atomic update on the ZooKeeper to add a new port entry.
     *
     * @param port
     *            PortConfig object to add to the ZooKeeper directory.
     * @param id  UUID to use for the port.
     * @return The UUID of the newly created object.
     * @throws ZkStateSerializationException
     *             Serialization error occurred.
     */
    public UUID create(PortConfig port, UUID id) throws StateAccessException,
            ZkStateSerializationException {
        ZkNodeEntry<UUID, PortConfig> portNode =
                new ZkNodeEntry<UUID, PortConfig>(id, port);
        multi(preparePortCreate(portNode));
        return id;
    }

    public UUID create(PortConfig port) throws StateAccessException,
            ZkStateSerializationException {
        UUID id = UUID.randomUUID();
        return create(port, id);
    }

    public ZkNodeEntry<UUID, UUID> createLink(
            PortDirectory.LogicalRouterPortConfig localPort,
            PortDirectory.LogicalRouterPortConfig peerPort)
            throws StateAccessException, ZkStateSerializationException {
        localPort.peer_uuid = UUID.randomUUID();
        peerPort.peer_uuid = UUID.randomUUID();

        ZkNodeEntry<UUID, PortConfig> localPortEntry =
            new ZkNodeEntry<UUID, PortConfig>(peerPort.peer_uuid, localPort);
        ZkNodeEntry<UUID, PortConfig> peerPortEntry =
            new ZkNodeEntry<UUID, PortConfig>(localPort.peer_uuid, peerPort);

        List<Op> ops = preparePortCreate(localPortEntry);
        ops.addAll(preparePortCreate(peerPortEntry));

        multi(ops);
        return new ZkNodeEntry<UUID, UUID>(peerPort.peer_uuid,
                localPort.peer_uuid);
    }

    /**
     * Gets a ZooKeeper node entry key-value pair of a port with the given ID.
     *
     * @param id
     *            The ID of the port.
     * @return Port object found.
     * @throws ZkStateSerializationException
     *             Serialization error occurred.
     */
    public ZkNodeEntry<UUID, PortConfig> get(UUID id)
            throws StateAccessException, ZkStateSerializationException {
        return get(id, null);
    }

    /**
     * Gets a ZooKeeper node entry key-value pair of a port with the given ID
     * and sets a watcher on the node.
     *
     * @param id
     *            The ID of the port.
     * @param watcher
     *            The watcher that gets notified when there is a change in the
     *            node.
     * @return Port object found.
     * @throws ZkStateSerializationException
     *             Serialization error occurred.
     */
    public ZkNodeEntry<UUID, PortConfig> get(UUID id, Runnable watcher)
            throws StateAccessException, ZkStateSerializationException {
        byte[] data = get(pathManager.getPortPath(id), watcher);
        PortConfig config = null;
        try {
            config = deserialize(data, PortConfig.class);
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not deserialize port " + id + " to PortConfig", e,
                    PortConfig.class);
        }
        return new ZkNodeEntry<UUID, PortConfig>(id, config);
    }

    /**
     * Gets a list of ZooKeeper port nodes belonging under the directory path
     * specified.
     *
     * @param path
     *            The directory path of the parent node.
     * @param watcher
     *            The watcher to set on the changes to the ports for this port.
     * @return A list of ZooKeeper port nodes.
     * @throws ZkStateSerializationException
     *             Serialization error occurred.
     */
    public List<ZkNodeEntry<UUID, PortConfig>> listPorts(String path,
            Runnable watcher) throws StateAccessException,
            ZkStateSerializationException {
        List<ZkNodeEntry<UUID, PortConfig>> result = new ArrayList<ZkNodeEntry<UUID, PortConfig>>();
        Set<String> portIds = getChildren(path, watcher);
        for (String portId : portIds) {
            // For now, get each one.
            result.add(get(UUID.fromString(portId)));
        }
        return result;
    }

    public Set<UUID> listPortIDs(String path, Runnable watcher)
            throws StateAccessException {
        Set<UUID> result = new HashSet<UUID>();
        Set<String> portIds = getChildren(path, watcher);
        for (String portId : portIds) {
            // For now, get each one.
            result.add(UUID.fromString(portId));
        }
        return result;
    }

    /**
     * Gets a list of ZooKeeper port nodes belonging to a router with the given
     * ID.
     *
     * @param routerId
     *            The ID of the router to find the ports of.
     * @return A list of ZooKeeper port nodes.
     * @throws ZkStateSerializationException
     *             Serialization error occurred.
     */
    public List<ZkNodeEntry<UUID, PortConfig>> listRouterPorts(UUID routerId)
            throws StateAccessException, ZkStateSerializationException {
        return listRouterPorts(routerId, null);
    }

    /**
     * Gets a list of ZooKeeper port nodes belonging to a router with the given
     * ID.
     *
     * @param routerId
     *            The ID of the router to find the ports of.
     * @param watcher
     *            The watcher to set on the changes to the ports for this
     *            router.
     * @return A list of ZooKeeper route nodes.
     * @throws ZkStateSerializationException
     *             Serialization error occurred.
     */
    public List<ZkNodeEntry<UUID, PortConfig>> listRouterPorts(UUID routerId,
            Runnable watcher) throws StateAccessException,
            ZkStateSerializationException {
        return listPorts(pathManager.getRouterPortsPath(routerId), watcher);
    }

    /**
     * Gets a list of ZooKeeper port nodes belonging to a bridge with the given
     * ID.
     *
     * @param bridgeId
     *            The ID of the bridge to find the ports of.
     * @return A list of ZooKeeper port nodes.
     * @throws ZkStateSerializationException
     *             Serialization error occurred.
     */
    public List<ZkNodeEntry<UUID, PortConfig>> listBridgePorts(UUID bridgeId)
            throws StateAccessException {
        return listBridgePorts(bridgeId, null);
    }

    /**
     * Gets a list of ZooKeeper port nodes belonging to a bridge with the given
     * ID.
     *
     * @param bridgeId
     *            The ID of the bridge to find the routes of.
     * @param watcher
     *            The watcher to set on the changes to the ports for this
     *            router.
     * @return A list of ZooKeeper port nodes.
     * @throws ZkStateSerializationException
     *             Serialization error occurred.
     */
    public List<ZkNodeEntry<UUID, PortConfig>> listBridgePorts(UUID bridgeId,
            Runnable watcher) throws StateAccessException {
        return listPorts(pathManager.getBridgePortsPath(bridgeId), watcher);
    }

    /**
     * Get the set of IDs of a Bridge's logical ports.
     *
     * @param bridgeId
     *              The ID of the bridge whose logical port IDs to retrieve.
     * @param watcher
     *              The watcher to notify if the set of IDs changes.
     * @return  A set of logical port IDs.
     * @throws StateAccessException
     *              If a data error occurs while accessing ZK.
     */
    public Set<UUID> getBridgeLogicalPortIDs(UUID bridgeId, Runnable watcher)
        throws StateAccessException {
        return listPortIDs(
                pathManager.getBridgeLogicalPortsPath(bridgeId), watcher);
    }

    /**
     * Updates the PortConfig values with the given PortConfig object.
     *
     * @param entry
     *            PortConfig object to save.
     * @throws ZkStateSerializationException
     *             Serialization error occurred.
     */
    public void update(ZkNodeEntry<UUID, PortConfig> entry)
            throws StateAccessException, ZkStateSerializationException {
        // Update any version for now.
        byte[] data = null;
        try {
            data = serialize(entry.value);

        } catch (IOException e) {
            throw new ZkStateSerializationException("Could not serialize port "
                    + entry.key + " to PortConfig", e, PortConfig.class);
        }
        update(pathManager.getPortPath(entry.key), data);
    }

    /***
     * Deletes a port and its related data from the ZooKeeper directories
     * atomically.
     *
     * @param id
     *            ID of the port to delete.
     * @throws ZkStateSerializationException
     *             Serialization error occurred.
     */
    public void delete(UUID id) throws StateAccessException,
            ZkStateSerializationException {
        multi(preparePortDelete(id));
    }

}
