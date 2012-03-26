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
import org.apache.zookeeper.ZooDefs.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.layer3.Route;
import com.midokura.midolman.state.GreZkManager.GreKey;

/**
 * Class to manage the port ZooKeeper data.
 *
 * @version 1.6 08 Sept 2011
 * @author Ryu Ishimoto
 */
public class PortZkManager extends ZkManager {

    private final static Logger log = LoggerFactory
            .getLogger(PortZkManager.class);
    private final GreZkManager greZkManager;

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
        // Create a new GRE key. Hide this from outside.
        ZkNodeEntry<Integer, GreKey> gre = greZkManager.createGreKey();
        entry.value.greKey = gre.key;

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
            ZkNodeEntry<UUID, PortConfig> localPortEntry,
            ZkNodeEntry<UUID, PortConfig> peerPortEntry)
            throws ZkStateSerializationException {
        List<Op> ops = new ArrayList<Op>();

        if (localPortEntry.value
                instanceof PortDirectory.LogicalBridgePortConfig)
            ops.addAll(prepareBridgePortCreate(localPortEntry));
        else
            ops.addAll(prepareRouterPortCreate(localPortEntry));
        if (peerPortEntry.value
                instanceof PortDirectory.LogicalBridgePortConfig)
            ops.addAll(prepareBridgePortCreate(peerPortEntry));
        else
            ops.addAll(prepareRouterPortCreate(peerPortEntry));

        return ops;
    }

    private List<Op> prepareRouterPortDelete(ZkNodeEntry<UUID, PortConfig> entry)
            throws StateAccessException {
        List<Op> ops = new ArrayList<Op>();
        if (entry.value instanceof PortDirectory.MaterializedRouterPortConfig) {
            BgpZkManager bgpManager = new BgpZkManager(zk, pathManager
                    .getBasePath());
            ops.addAll(bgpManager.preparePortDelete(entry.key));
            String path = pathManager.getPortBgpPath(entry.key);
            log.debug("Preparing to delete: " + path);
            ops.add(Op.delete(path, -1));

            VpnZkManager vpnManager = new VpnZkManager(zk, pathManager
                                                       .getBasePath());
            ops.addAll(vpnManager.preparePortDelete(entry.key));
            path = pathManager.getPortVpnPath(entry.key);
            log.debug("Preparing to delete: {}", path);
            ops.add(Op.delete(path, -1));
        }
        RouteZkManager routeZkManager = new RouteZkManager(zk, pathManager
                .getBasePath());
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
        return ops;
    }

    private List<Op> prepareLinkDelete(ZkNodeEntry<UUID, PortConfig> entry)
            throws ZkStateSerializationException, StateAccessException {
        List<Op> ops;
        UUID peerId;
        if (entry.value instanceof PortDirectory.LogicalBridgePortConfig) {
            ops = prepareBridgePortDelete(entry);
            peerId = ((PortDirectory.LogicalBridgePortConfig) entry.value).peer_uuid;
        } else {
            ops = prepareRouterPortDelete(entry);
            peerId = ((PortDirectory.LogicalRouterPortConfig) entry.value).peer_uuid;
        }
        ZkNodeEntry<UUID, PortConfig> peer = get(peerId);
        if (peer.value instanceof PortDirectory.LogicalBridgePortConfig)
            ops.addAll(prepareBridgePortDelete(peer));
        else
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
    public List<Op> preparePortDelete(ZkNodeEntry<UUID, PortConfig> entry)
            throws StateAccessException {
        List<Op> res;
        if (entry.value instanceof PortDirectory.LogicalBridgePortConfig ||
                entry.value instanceof PortDirectory.LogicalRouterPortConfig) {
            res = prepareLinkDelete(entry);
        } else if (entry.value instanceof PortDirectory.BridgePortConfig) {
            res = prepareBridgePortDelete(entry);
        } else if (entry.value instanceof PortDirectory.MaterializedRouterPortConfig) {
            res = prepareRouterPortDelete(entry);
        } else {
            throw new IllegalArgumentException("Unsupported port type");
        }
        // Delete GRE
        GreKey gre = new GreKey(entry.key);
        res.addAll(greZkManager
                .prepareGreDelete(new ZkNodeEntry<Integer, GreKey>(
                        entry.value.greKey, gre)));
        return res;
    }

    /**
     * Performs an atomic update on the ZooKeeper to add a new port entry.
     *
     * @param port
     *            PortConfig object to add to the ZooKeeper directory.
     * @return The UUID of the newly created object.
     * @throws ZkStateSerializationException
     *             Serialization error occurred.
     */
    public UUID create(PortConfig port) throws StateAccessException,
            ZkStateSerializationException {
        UUID id = UUID.randomUUID();
        ZkNodeEntry<UUID, PortConfig> portNode =
                new ZkNodeEntry<UUID, PortConfig>(id, port);
        multi(preparePortCreate(portNode));
        return id;
    }

    public ZkNodeEntry<UUID, UUID> createLink(
            PortDirectory.LogicalRouterPortConfig localPort,
            PortDirectory.LogicalRouterPortConfig peerPort)
            throws StateAccessException, ZkStateSerializationException {
        localPort.peer_uuid = UUID.randomUUID();
        peerPort.peer_uuid = UUID.randomUUID();

        ZkNodeEntry<UUID, PortConfig> localPortEntry = new ZkNodeEntry<UUID, PortConfig>(
                peerPort.peer_uuid, localPort);
        ZkNodeEntry<UUID, PortConfig> peerPortEntry = new ZkNodeEntry<UUID, PortConfig>(
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
     * Gets a list of ZooKeeper port nodes belonging to a bridge with the given
     * ID.
     *
     * @param bridgeId
     *            The ID of the bridge to find the ports of.
     * @return A list of ZooKeeper port nodes.
     * @param watcher
     *            The watcher to set on the changes to the ports for this
     *            router.
     * @throws StateAccessException
     *             Serialization error occurred.
     */
    public List<ZkNodeEntry<UUID, PortConfig>> listBridgeLogicalPorts(
            UUID bridgeId, Runnable watcher) throws StateAccessException {
        return listPorts(
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
