/*
 * @(#)PortZkManager        1.6 11/09/08
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.state;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooDefs.Ids;

import com.midokura.midolman.layer3.Route;
import com.midokura.midolman.util.ShortUUID;

/**
 * Class to manage the port ZooKeeper data.
 * 
 * @version 1.6 08 Sept 2011
 * @author Ryu Ishimoto
 */
public class PortZkManager extends ZkManager {

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
    }

    public PortZkManager(ZooKeeper zk, String basePath) {
        this(new ZkDirectory(zk, "", null), basePath);
    }

    private List<Op> prepareRouterPortCreate(
            ZkNodeEntry<UUID, PortDirectory.PortConfig> portNode)
            throws ZkStateSerializationException {
        List<Op> ops = new ArrayList<Op>();
        try {
            ops.add(Op.create(pathManager.getPortPath(portNode.key),
                    serialize(portNode.value), Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT));
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not serialize PortConfig", e,
                    PortDirectory.PortConfig.class);
        }
        ops.add(Op.create(pathManager.getRouterPortPath(
                portNode.value.device_id, portNode.key), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        ops.add(Op.create(pathManager.getPortRoutesPath(portNode.key), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));

        if (portNode.value instanceof PortDirectory.MaterializedRouterPortConfig) {
            ops.add(Op.create(pathManager.getPortBgpPath(portNode.key), null,
                    Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        }
        return ops;
    }

    private List<Op> prepareBridgePortCreate(
            ZkNodeEntry<UUID, PortDirectory.PortConfig> portNode)
            throws ZkStateSerializationException {
        List<Op> ops = new ArrayList<Op>();

        try {
            ops.add(Op.create(pathManager.getPortPath(portNode.key),
                    serialize(portNode.value), Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT));
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not serialize PortConfig", e,
                    PortDirectory.PortConfig.class);
        }

        ops.add(Op.create(pathManager.getBridgePortPath(
                portNode.value.device_id, portNode.key), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        return ops;
    }

    public List<Op> preparePortCreate(UUID id, PortDirectory.PortConfig portNode)
            throws ZkStateSerializationException {
        return preparePortCreate(new ZkNodeEntry<UUID, PortDirectory.PortConfig>(
                id, portNode));
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
    public List<Op> preparePortCreate(
            ZkNodeEntry<UUID, PortDirectory.PortConfig> entry)
            throws ZkStateSerializationException {
        if (entry.value instanceof PortDirectory.BridgePortConfig) {
            return prepareBridgePortCreate(entry);
        } else if (entry.value instanceof PortDirectory.MaterializedRouterPortConfig) {
            return prepareRouterPortCreate(entry);
        } else if (entry.value instanceof PortDirectory.LogicalRouterPortConfig) {
            throw new IllegalArgumentException(
                    "A single logical port cannot be created.");
        } else {
            throw new IllegalArgumentException("Unsupported port type");
        }
    }

    public List<Op> preparePortCreateLink(
            ZkNodeEntry<UUID, PortDirectory.PortConfig> localPortEntry,
            ZkNodeEntry<UUID, PortDirectory.PortConfig> peerPortEntry)
            throws ZkStateSerializationException {
        List<Op> ops = new ArrayList<Op>();

        ops.addAll(prepareRouterPortCreate(localPortEntry));
        ops.addAll(prepareRouterPortCreate(peerPortEntry));

        return ops;
    }

    private List<Op> prepareRouterPortDelete(
            ZkNodeEntry<UUID, PortDirectory.PortConfig> entry)
            throws StateAccessException, ZkStateSerializationException {
        List<Op> ops = new ArrayList<Op>();
        if (entry.value instanceof PortDirectory.MaterializedRouterPortConfig) {
            ops.add(Op.delete(pathManager.getPortBgpPath(entry.key), -1));
        }
        RouteZkManager routeZkManager = new RouteZkManager(zk, pathManager
                .getBasePath());
        List<ZkNodeEntry<UUID, Route>> routes = routeZkManager.listPortRoutes(
                entry.key, null);
        for (ZkNodeEntry<UUID, Route> route : routes) {
            ops.addAll(routeZkManager.prepareRouteDelete(route));
        }
        ops.add(Op.delete(pathManager.getRouterPortPath(entry.value.device_id,
                entry.key), -1));
        ops.add(Op.delete(pathManager.getPortPath(entry.key), -1));
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
    private List<Op> prepareBridgePortDelete(
            ZkNodeEntry<UUID, PortDirectory.PortConfig> entry)
            throws StateAccessException {
        List<Op> ops = new ArrayList<Op>();
        ops.add(Op.delete(pathManager.getBridgePortPath(entry.value.device_id,
                entry.key), -1));
        ops.add(Op.delete(pathManager.getPortPath(entry.key), -1));
        return ops;
    }

    private List<Op> prepareLinkDelete(
            ZkNodeEntry<UUID, PortDirectory.PortConfig> entry)
            throws ZkStateSerializationException, StateAccessException {
        List<Op> ops = prepareRouterPortDelete(entry);
        UUID peerId = ((PortDirectory.LogicalRouterPortConfig) entry.value).peer_uuid;
        ZkNodeEntry<UUID, PortDirectory.PortConfig> peer = get(peerId);
        ops.addAll(prepareRouterPortDelete(peer));
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
    public List<Op> preparePortDelete(
            ZkNodeEntry<UUID, PortDirectory.PortConfig> entry)
            throws StateAccessException, ZkStateSerializationException {
        if (entry.value instanceof PortDirectory.BridgePortConfig) {
            return prepareBridgePortDelete(entry);
        } else if (entry.value instanceof PortDirectory.MaterializedRouterPortConfig) {
            return prepareRouterPortDelete(entry);
        } else if (entry.value instanceof PortDirectory.LogicalRouterPortConfig) {
            return prepareLinkDelete(entry);
        } else {
            throw new IllegalArgumentException("Unsupported port type");
        }
    }

    /**
     * Performs an atomic update on the ZooKeeper to add a new port entry.
     * 
     * @param route
     *            PortConfig object to add to the ZooKeeper directory.
     * @return The UUID of the newly created object.
     * @throws ZkStateSerializationException
     *             Serialization error occurred.
     */
    public UUID create(PortDirectory.PortConfig port)
            throws StateAccessException, ZkStateSerializationException {
        // TODO(pino) - port UUIDs should be created using a sequential
        // persistent
        // create in a ZK directory.
        UUID id = ShortUUID.generate32BitUUID();
        ZkNodeEntry<UUID, PortDirectory.PortConfig> portNode = new ZkNodeEntry<UUID, PortDirectory.PortConfig>(
                id, port);
        multi(preparePortCreate(portNode));
        return id;
    }

    public ZkNodeEntry<UUID, UUID> createLink(
            PortDirectory.LogicalRouterPortConfig localPort,
            PortDirectory.LogicalRouterPortConfig peerPort)
            throws StateAccessException, ZkStateSerializationException {
        localPort.peer_uuid = ShortUUID.generate32BitUUID();
        peerPort.peer_uuid = ShortUUID.generate32BitUUID();

        ZkNodeEntry<UUID, PortDirectory.PortConfig> localPortEntry = new ZkNodeEntry<UUID, PortDirectory.PortConfig>(
                peerPort.peer_uuid, localPort);
        ZkNodeEntry<UUID, PortDirectory.PortConfig> peerPortEntry = new ZkNodeEntry<UUID, PortDirectory.PortConfig>(
                localPort.peer_uuid, peerPort);
        multi(preparePortCreateLink(localPortEntry, peerPortEntry));
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
    public ZkNodeEntry<UUID, PortDirectory.PortConfig> get(UUID id)
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
     * @return Route object found.
     * @throws ZkStateSerializationException
     *             Serialization error occurred.
     */
    public ZkNodeEntry<UUID, PortDirectory.PortConfig> get(UUID id,
            Runnable watcher) throws StateAccessException,
            ZkStateSerializationException {
        byte[] data = get(pathManager.getPortPath(id), watcher);
        PortDirectory.PortConfig config = null;
        try {
            config = deserialize(data, PortDirectory.PortConfig.class);
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not deserialize port " + id + " to PortConfig", e,
                    PortDirectory.PortConfig.class);
        }
        return new ZkNodeEntry<UUID, PortDirectory.PortConfig>(id, config);
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
    public List<ZkNodeEntry<UUID, PortDirectory.PortConfig>> listPorts(
            String path, Runnable watcher) throws StateAccessException,
            ZkStateSerializationException {
        List<ZkNodeEntry<UUID, PortDirectory.PortConfig>> result = new ArrayList<ZkNodeEntry<UUID, PortDirectory.PortConfig>>();
        Set<String> portIds = getChildren(path, watcher);
        for (String portId : portIds) {
            // For now, get each one.
            result.add(get(UUID.fromString(portId)));
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
    public List<ZkNodeEntry<UUID, PortDirectory.PortConfig>> listRouterPorts(
            UUID routerId) throws StateAccessException,
            ZkStateSerializationException {
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
    public List<ZkNodeEntry<UUID, PortDirectory.PortConfig>> listRouterPorts(
            UUID routerId, Runnable watcher) throws StateAccessException,
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
    public List<ZkNodeEntry<UUID, PortDirectory.PortConfig>> listBridgePorts(
            UUID bridgeId) throws StateAccessException,
            ZkStateSerializationException {
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
    public List<ZkNodeEntry<UUID, PortDirectory.PortConfig>> listBridgePorts(
            UUID bridgeId, Runnable watcher) throws StateAccessException,
            ZkStateSerializationException {
        return listPorts(pathManager.getBridgePortsPath(bridgeId), watcher);
    }

    /**
     * Updates the PortConfig values with the given PortConfig object.
     * 
     * @param entry
     *            PortConfig object to save.
     * @throws ZkStateSerializationException
     *             Serialization error occurred.
     */
    public void update(ZkNodeEntry<UUID, PortDirectory.PortConfig> entry)
            throws StateAccessException, ZkStateSerializationException {
        // Update any version for now.
        byte[] data = null;
        try {
            data = serialize(entry.value);

        } catch (IOException e) {
            throw new ZkStateSerializationException("Could not serialize port "
                    + entry.key + " to PortConfig", e,
                    PortDirectory.PortConfig.class);
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
