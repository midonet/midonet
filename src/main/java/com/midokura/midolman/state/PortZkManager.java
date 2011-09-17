/*
 * @(#)PortZkManager        1.6 11/09/08
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.state;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooDefs.Ids;

import com.midokura.midolman.layer3.Route;
import com.midokura.midolman.state.PortDirectory.BridgePortConfig;
import com.midokura.midolman.state.PortDirectory.LogicalRouterPortConfig;
import com.midokura.midolman.state.PortDirectory.MaterializedRouterPortConfig;
import com.midokura.midolman.state.PortDirectory.PortConfig;
import com.midokura.midolman.state.PortDirectory.RouterPortConfig;

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
     *            ZooKeeper object.
     * @param basePath
     *            The root path.
     */
    public PortZkManager(ZooKeeper zk, String basePath) {
        super(zk, basePath);
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
    public List<Op> preparePortCreate(ZkNodeEntry<UUID, PortConfig> portNode)
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

        if (portNode.value instanceof RouterPortConfig) {
            ops.add(Op.create(pathManager.getRouterPortPath(
                    portNode.value.device_id, portNode.key), null,
                    Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
            ops.add(Op.create(pathManager.getPortRoutesPath(portNode.key),
                    null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));

            if (portNode.value instanceof MaterializedRouterPortConfig) {
                ops.add(Op.create(pathManager.getPortBgpPath(portNode.key),
                        null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));

            }
        } else if (portNode.value instanceof BridgePortConfig) {
            ops.add(Op.create(pathManager.getBridgePortPath(
                    portNode.value.device_id, portNode.key), null,
                    Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        } else {
            throw new IllegalArgumentException("Unrecognized port type.");
        }

        return ops;
    }

    /**
     * Performs an atomic update on the ZooKeeper to add a new port entry.
     * 
     * @param route
     *            PortConfig object to add to the ZooKeeper directory.
     * @return The UUID of the newly created object.
     * @throws ZkStateSerializationException
     *             Serialization error occurred.
     * @throws KeeperException
     *             ZooKeeper error occurred.
     * @throws InterruptedException
     *             ZooKeeper was unresponsive.
     */
    public UUID create(PortConfig port) throws IOException, KeeperException,
            InterruptedException, ZkStateSerializationException {
        UUID id = UUID.randomUUID();
        ZkNodeEntry<UUID, PortConfig> portNode = new ZkNodeEntry<UUID, PortConfig>(
                id, port);
        zk.multi(preparePortCreate(portNode));
        return id;
    }

    /**
     * Gets a ZooKeeper node entry key-value pair of a port with the given ID.
     * 
     * @param id
     *            The ID of the port.
     * @return Port object found.
     * @throws ZkStateSerializationException
     *             Serialization error occurred.
     * @throws KeeperException
     *             ZooKeeper error occurred.
     * @throws InterruptedException
     *             ZooKeeper was unresponsive.
     */
    public ZkNodeEntry<UUID, PortConfig> get(UUID id) throws KeeperException,
            InterruptedException, ZkStateSerializationException {
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
     * @throws KeeperException
     *             ZooKeeper error occurred.
     * @throws InterruptedException
     *             ZooKeeper was unresponsive.
     */
    public ZkNodeEntry<UUID, PortConfig> get(UUID id, Runnable watcher)
            throws KeeperException, InterruptedException,
            ZkStateSerializationException {
        byte[] data = getData(pathManager.getPortPath(id), watcher);
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
     * @throws KeeperException
     *             ZooKeeper error occurred.
     * @throws InterruptedException
     *             ZooKeeper was unresponsive.
     */
    public List<ZkNodeEntry<UUID, PortConfig>> listPorts(String path,
            Runnable watcher) throws KeeperException, InterruptedException,
            ZkStateSerializationException {
        List<ZkNodeEntry<UUID, PortConfig>> result = new ArrayList<ZkNodeEntry<UUID, PortConfig>>();
        List<String> portIds = getChildren(path, watcher);
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
     * @throws KeeperException
     *             ZooKeeper error occurred.
     * @throws InterruptedException
     *             ZooKeeper was unresponsive.
     */
    public List<ZkNodeEntry<UUID, PortConfig>> listRouterPorts(UUID routerId)
            throws KeeperException, InterruptedException,
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
     * @throws KeeperException
     *             ZooKeeper error occurred.
     * @throws InterruptedException
     *             ZooKeeper was unresponsive.
     */
    public List<ZkNodeEntry<UUID, PortConfig>> listRouterPorts(UUID routerId,
            Runnable watcher) throws KeeperException, InterruptedException,
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
     * @throws KeeperException
     *             ZooKeeper error occurred.
     * @throws InterruptedException
     *             ZooKeeper was unresponsive.
     */
    public List<ZkNodeEntry<UUID, PortConfig>> listBridgePorts(UUID bridgeId)
            throws KeeperException, InterruptedException,
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
     * @throws KeeperException
     *             ZooKeeper error occurred.
     * @throws InterruptedException
     *             ZooKeeper was unresponsive.
     */
    public List<ZkNodeEntry<UUID, PortConfig>> listBridgePorts(UUID bridgeId,
            Runnable watcher) throws KeeperException, InterruptedException,
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
     * @throws KeeperException
     *             ZooKeeper error occurred.
     * @throws InterruptedException
     *             ZooKeeper was unresponsive.
     */
    public void update(ZkNodeEntry<UUID, PortConfig> entry)
            throws KeeperException, InterruptedException,
            ZkStateSerializationException {
        // Update any version for now.
        try {
            zk.setData(pathManager.getPortPath(entry.key),
                    serialize(entry.value), -1);

        } catch (IOException e) {
            throw new ZkStateSerializationException("Could not serialize port "
                    + entry.key + " to PortConfig", e, PortConfig.class);
        }
    }

    /**
     * Constructs a list of operations to perform in a router port deletion.
     * 
     * @param entry
     *            Port ZooKeeper entry to delete.
     * @return A list of Op objects representing the operations to perform.
     * @throws ZkStateSerializationException
     *             Serialization error occurred.
     * @throws KeeperException
     *             ZooKeeper error occurred.
     * @throws InterruptedException
     *             ZooKeeper was unresponsive.
     */
    public List<Op> prepareRouterPortDelete(ZkNodeEntry<UUID, PortConfig> entry)
            throws KeeperException, InterruptedException,
            ZkStateSerializationException {
        List<Op> ops = new ArrayList<Op>();

        if (entry.value instanceof RouterPortConfig) {
            RouteZkManager routeZk = new RouteZkManager(zk, basePath);
            List<ZkNodeEntry<UUID, Route>> routes = routeZk.listPortRoutes(
                    entry.key, null);
            for (ZkNodeEntry<UUID, Route> route : routes) {
                ops.addAll(routeZk.prepareRouteDelete(route));
            }
        }
        ops.add(Op.delete(pathManager.getRouterPortPath(entry.value.device_id,
                entry.key), -1));
        ops.add(Op.delete(pathManager.getPortPath(entry.key), -1));

        if (entry.value instanceof LogicalRouterPortConfig) {
            UUID peerPortId = ((LogicalRouterPortConfig) entry.value).peer_uuid;
            // For logical router ports, we need to delete the peer Port.
            if (peerPortId != null) {
                ZkNodeEntry<UUID, PortConfig> peerPortEntry = get(peerPortId);
                if (peerPortEntry != null) {
                    ops.addAll(prepareRouterPortDelete(peerPortEntry));
                }
            }
        }
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
     * @throws KeeperException
     *             ZooKeeper error occurred.
     * @throws InterruptedException
     *             ZooKeeper was unresponsive.
     */
    public List<Op> prepareBridgePortDelete(ZkNodeEntry<UUID, PortConfig> entry)
            throws KeeperException, InterruptedException {
        List<Op> ops = new ArrayList<Op>();
        ops.add(Op.delete(pathManager.getBridgePortPath(entry.value.device_id,
                entry.key), -1));
        ops.add(Op.delete(pathManager.getPortPath(entry.key), -1));
        return ops;
    }

    /**
     * Constructs a list of operations to perform in a port deletion.
     * 
     * @param entry
     *            Port ZooKeeper entry to delete.
     * @return A list of Op objects representing the operations to perform.
     * @throws ZkStateSerializationException
     *             Serialization error occurred.
     * @throws KeeperException
     *             ZooKeeper error occurred.
     * @throws InterruptedException
     *             ZooKeeper was unresponsive.
     */
    public List<Op> preparePortDelete(ZkNodeEntry<UUID, PortConfig> entry)
            throws KeeperException, InterruptedException,
            ZkStateSerializationException {
        if (entry.value instanceof BridgePortConfig) {
            return prepareBridgePortDelete(entry);
        } else {
            return prepareRouterPortDelete(entry);
        }
    }

    /***
     * Deletes a port and its related data from the ZooKeeper directories
     * atomically.
     * 
     * @param id
     *            ID of the port to delete.
     * @throws ZkStateSerializationException
     *             Serialization error occurred.
     * @throws KeeperException
     *             ZooKeeper error occurred.
     * @throws InterruptedException
     *             ZooKeeper was unresponsive.
     */
    public void delete(UUID id) throws InterruptedException, KeeperException,
            ZkStateSerializationException {
        this.zk.multi(preparePortDelete(get(id)));
    }

}
