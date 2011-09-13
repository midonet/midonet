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
import com.midokura.midolman.state.PortDirectory.MaterializedRouterPortConfig;
import com.midokura.midolman.state.PortDirectory.PortConfig;
import com.midokura.midolman.state.PortDirectory.RouterPortConfig;

/**
 * This class was created to handle multiple ops feature in Zookeeper.
 * 
 * @version 1.6 08 Sept 2011
 * @author Ryu Ishimoto
 */
public class PortZkManager extends ZkManager {

    /**
     * PortZkManager constructor.
     * 
     * @param zk
     *            Zookeeper object.
     * @param basePath
     *            Directory to set as the base.
     */
    public PortZkManager(ZooKeeper zk, String basePath) {
        super(zk, basePath);
    }

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
        } else if (portNode.value instanceof BridgePortConfig) {
            ops.add(Op.create(pathManager.getBridgePortPath(
                    portNode.value.device_id, portNode.key), null,
                    Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        } else {
            throw new IllegalArgumentException("Unrecognized port type.");
        }

        return ops;
    }

    public UUID create(PortConfig port) throws IOException, KeeperException,
            InterruptedException, ZkStateSerializationException {
        UUID id = UUID.randomUUID();
        ZkNodeEntry<UUID, PortConfig> portNode = new ZkNodeEntry<UUID, PortConfig>(
                id, port);
        zk.multi(preparePortCreate(portNode));
        return id;
    }

    public ZkNodeEntry<UUID, PortConfig> get(UUID id) throws KeeperException,
            InterruptedException, ZkStateSerializationException {
        byte[] data = zk.getData(pathManager.getPortPath(id), null, null);
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

    public List<ZkNodeEntry<UUID, PortConfig>> listPorts(String path)
            throws KeeperException, InterruptedException,
            ZkStateSerializationException {
        List<ZkNodeEntry<UUID, PortConfig>> result = new ArrayList<ZkNodeEntry<UUID, PortConfig>>();
        List<String> portIds = zk.getChildren(path, null);
        for (String portId : portIds) {
            // For now, get each one.
            result.add(get(UUID.fromString(portId)));
        }
        return result;
    }

    public List<ZkNodeEntry<UUID, PortConfig>> listRouterPorts(UUID routerId)
            throws KeeperException, InterruptedException,
            ZkStateSerializationException {
        return listPorts(pathManager.getRouterPortsPath(routerId));
    }

    public List<ZkNodeEntry<UUID, PortConfig>> listBridgePorts(UUID bridgeId)
            throws KeeperException, InterruptedException,
            ZkStateSerializationException {
        return listPorts(pathManager.getBridgePortsPath(bridgeId));
    }

    public void update(ZkNodeEntry<UUID, PortConfig> entry) throws IOException,
            KeeperException, InterruptedException,
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

    public List<Op> prepareRouterPortDelete(
            ZkNodeEntry<UUID, PortConfig> entry) throws KeeperException,
            InterruptedException, IOException {
        List<Op> ops = new ArrayList<Op>();
        
        if (entry.value instanceof MaterializedRouterPortConfig) {
            RouteZkManager routeZk = new RouteZkManager(zk, basePath);
            List<ZkNodeEntry<UUID, Route>> routes = routeZk
                    .listPortRoutes(entry.key);
            for (ZkNodeEntry<UUID, Route> route : routes) {
                ops.addAll(routeZk.getPortRouteDeleteOps(entry.key, route.key));
            }
        }
        ops.add(Op.delete(pathManager.getRouterPortPath(entry.value.device_id,
                entry.key), -1));
        ops.add(Op.delete(pathManager.getPortPath(entry.key), -1));
        return ops;
    }

    public List<Op> prepareLogicalRouterPortDelete(
            ZkNodeEntry<UUID, PortConfig> entry) throws KeeperException,
            InterruptedException, IOException {
        List<Op> ops = new ArrayList<Op>();
        ops.add(Op.delete(pathManager.getRouterPortPath(entry.value.device_id,
                entry.key), -1));
        
        ops.add(Op.delete(pathManager.getPortPath(entry.key), -1));
        return ops;
    }

    public List<Op> prepareBridgePortDelete(ZkNodeEntry<UUID, PortConfig> entry)
            throws KeeperException, InterruptedException, IOException {
        List<Op> ops = new ArrayList<Op>();
        ops.add(Op.delete(pathManager.getBridgePortPath(entry.value.device_id,
                entry.key), -1));
        ops.add(Op.delete(pathManager.getPortPath(entry.key), -1));
        return ops;
    }

    public List<Op> preparePortDelete(ZkNodeEntry<UUID, PortConfig> entry)
            throws KeeperException, InterruptedException, IOException {
        if (entry.value instanceof BridgePortConfig) {
            return prepareBridgePortDelete(entry);
        } else {
            return prepareRouterPortDelete(entry);
        }
    }

    public void delete(UUID id) throws InterruptedException, KeeperException,
            ZkStateSerializationException, IOException {
        this.zk.multi(preparePortDelete(get(id)));
    }

}
