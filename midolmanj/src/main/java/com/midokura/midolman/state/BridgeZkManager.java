/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.state;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.state.GreZkManager.GreKey;

/**
 * Class to manage the bridge ZooKeeper data.
 */
public class BridgeZkManager extends ZkManager {

    private final static Logger log = LoggerFactory
            .getLogger(BridgeZkManager.class);

    public static class BridgeConfig {

        public BridgeConfig() {
            super();
        }

        public BridgeConfig(UUID inboundFilter, UUID outboundFilter) {
            super();
            this.inboundFilter = inboundFilter;
            this.outboundFilter = outboundFilter;
        }

        // TODO: Make this private with a getter.
        public int greKey; // Only set in prepareBridgeCreate
        public UUID inboundFilter;
        public UUID outboundFilter;

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;

            BridgeConfig that = (BridgeConfig) o;

            if (greKey != that.greKey)
                return false;
            if (inboundFilter != null ? !inboundFilter
                    .equals(that.inboundFilter) : that.inboundFilter != null)
                return false;
            if (outboundFilter != null ? !outboundFilter
                    .equals(that.outboundFilter) : that.outboundFilter != null)
                return false;

            return true;
        }

        @Override
        public int hashCode() {
            int result = greKey;
            result = 31 * result
                    + (inboundFilter != null ? inboundFilter.hashCode() : 0);
            result = 31 * result
                    + (outboundFilter != null ? outboundFilter.hashCode() : 0);
            return result;
        }

        @Override
        public String toString() {
            return "BridgeConfig{" + "greKey=" + greKey + ", inboundFilter="
                    + inboundFilter + ", outboundFilter=" + outboundFilter
                    + '}';
        }
    }

    private PortSetMap portSetMap;
    private FiltersZkManager filterZkManager;
    private GreZkManager greZkManager;
    private PortZkManager portZkManager;

    /**
     * Initializes a BridgeZkManager object with a ZooKeeper client and the root
     * path of the ZooKeeper directory.
     *
     * @param zk
     *            ZooKeeper object.
     * @param basePath
     *            The root path.
     */
    public BridgeZkManager(Directory zk, String basePath)
            throws StateAccessException {
        super(zk, basePath);
        this.portSetMap = new PortSetMap(zk, basePath);
        this.filterZkManager = new FiltersZkManager(zk, basePath);
        this.greZkManager = new GreZkManager(zk, basePath);
        this.portZkManager = new PortZkManager(zk, basePath);
    }

    public List<Op> prepareBridgeCreate(UUID id, BridgeConfig bridgeNode)
            throws StateAccessException {
        return prepareBridgeCreate(new ZkNodeEntry<UUID, BridgeConfig>(id,
                bridgeNode));
    }

    /**
     * Constructs a list of ZooKeeper update operations to perform when adding a
     * new bridge.
     *
     * @param bridgeNode
     *            ZooKeeper node representing a key-value entry of Bridge UUID
     *            and BridgeConfig object.
     * @return A list of Op objects to represent the operations to perform.
     * @throws ZkStateSerializationException
     *             Serialization error occurred.
     * @throws StateAccessException
     *             Error accessing ZooKeeper.
     */
    public List<Op> prepareBridgeCreate(
            ZkNodeEntry<UUID, BridgeConfig> bridgeNode)
            throws StateAccessException {

        // Create a new GRE key. Hide this from outside.
        int greKey = greZkManager.createGreKey();
        bridgeNode.value.greKey = greKey;

        List<Op> ops = new ArrayList<Op>();
        ops.add(Op.create(pathManager.getBridgePath(bridgeNode.key),
                serializer.serialize(bridgeNode.value), Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT));

        ops.add(Op.create(pathManager.getBridgePortsPath(bridgeNode.key), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));

        ops.add(Op.create(
                pathManager.getBridgeLogicalPortsPath(bridgeNode.key), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));

        ops.add(Op.create(pathManager.getBridgeDhcpPath(bridgeNode.key), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));

        ops.add(Op.create(pathManager.getBridgeMacPortsPath(bridgeNode.key),
                null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));

        ops.add(Op.create(
                pathManager.getBridgePortLocationsPath(bridgeNode.key), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));

        // Add a port-set for this bridge
        ops.add(portSetMap.preparePortSetCreate(bridgeNode.key));

        // Update GreKey to reference the bridge.
        GreKey gre = new GreKey(bridgeNode.key);
        ops.addAll(greZkManager.prepareGreUpdate(greKey, gre));

        ops.addAll(filterZkManager.prepareCreate(bridgeNode.key));
        return ops;
    }

    /**
     * Construct a list of ZK operations needed to update the configuration of a
     * bridge.
     *
     * @param id
     *            ID of the bridge to update
     * @param config
     *            the new bridge configuration.
     * @return The ZK operation required to update the bridge.
     * @throws ZkStateSerializationException
     *             if the BridgeConfig could not be serialized.
     */
    public Op prepareUpdate(UUID id, BridgeConfig config)
            throws StateAccessException {
        BridgeConfig oldConfig = get(id).value;
        // Have the inbound or outbound filter changed?
        boolean dataChanged = false;
        UUID id1 = oldConfig.inboundFilter;
        UUID id2 = config.inboundFilter;
        if (id1 == null ? id2 != null : !id1.equals(id2)) {
            log.debug("The inbound filter of bridge {} changed from {} to {}",
                    new Object[] { id, id1, id2 });
            dataChanged = true;
        }
        id1 = oldConfig.outboundFilter;
        id2 = config.outboundFilter;
        if (id1 == null ? id2 != null : !id1.equals(id2)) {
            log.debug("The outbound filter of bridge {} changed from {} to {}",
                    new Object[] { id, id1, id2 });
            dataChanged = true;
        }
        if (dataChanged) {
            // Update the midolman data. Don't change the Bridge's GRE-key.
            config.greKey = oldConfig.greKey;
            return Op.setData(pathManager.getBridgePath(id),
                    serializer.serialize(config), -1);
        }
        return null;
    }

    public List<Op> prepareBridgeDelete(UUID id) throws StateAccessException {
        return prepareBridgeDelete(get(id));
    }

    /**
     * Constructs a list of operations to perform in a bridge deletion.
     *
     * @param entry
     *            Bridge ZooKeeper entry to delete.
     * @return A list of Op objects representing the operations to perform.
     * @throws ZkStateSerializationException
     *             Serialization error occurred.
     * @throws KeeperException
     *             ZooKeeper error occurred.
     * @throws InterruptedException
     *             ZooKeeper was unresponsive.
     */
    public List<Op> prepareBridgeDelete(ZkNodeEntry<UUID, BridgeConfig> entry)
            throws StateAccessException {
        List<Op> ops = new ArrayList<Op>();
        // Delete the ports.
        Set<UUID> portIds = portZkManager.getBridgePortIDs(entry.key);
        for (UUID portId : portIds) {
            ops.addAll(portZkManager.prepareDelete(portId));
        }

        portIds = portZkManager.getBridgeLogicalPortIDs(entry.key);
        for (UUID portId : portIds) {
            ops.addAll(portZkManager.prepareDelete(portId));
        }

        ops.add(Op.delete(pathManager.getBridgePortsPath(entry.key), -1));
        ops.add(Op.delete(pathManager.getBridgeLogicalPortsPath(entry.key), -1));
        ops.addAll(getRecursiveDeleteOps(pathManager
                .getBridgeDhcpPath(entry.key)));
        ops.add(Op.delete(pathManager.getBridgeMacPortsPath(entry.key), -1));
        ops.add(Op.delete(pathManager.getBridgePortLocationsPath(entry.key), -1));

        // Delete GRE
        ops.addAll(greZkManager.prepareGreDelete(entry.value.greKey));

        // Delete this bridge's port-set
        ops.add(portSetMap.preparePortSetDelete(entry.key));

        // Delete the bridge
        ops.add(Op.delete(pathManager.getBridgePath(entry.key), -1));

        ops.addAll(filterZkManager.prepareDelete(entry.key));

        return ops;
    }

    /**
     * Performs an atomic update on the ZooKeeper to add a new bridge entry.
     *
     * @param bridge
     *            Bridge object to add to the ZooKeeper directory.
     * @return The UUID of the newly created object.
     * @throws ZkStateSerializationException
     *             Serialization error occurred.
     * @throws KeeperException
     *             ZooKeeper error occurred.
     * @throws InterruptedException
     *             ZooKeeper was unresponsive.
     */
    public UUID create(BridgeConfig bridge) throws StateAccessException,
            ZkStateSerializationException {
        UUID id = UUID.randomUUID();
        ZkNodeEntry<UUID, BridgeConfig> bridgeNode = new ZkNodeEntry<UUID, BridgeConfig>(
                id, bridge);
        multi(prepareBridgeCreate(bridgeNode));
        return id;
    }

    public void update(UUID id, BridgeConfig cfg) throws StateAccessException {
        Op op = prepareUpdate(id, cfg);
        if (null != op) {
            List<Op> ops = new ArrayList<Op>();
            ops.add(op);
            multi(ops);
        }
    }

    /**
     * Gets a ZooKeeper node entry key-value pair of a bridge with the given ID.
     *
     * @param id
     *            The ID of the bridge.
     * @return Bridge object found.
     * @throws ZkStateSerializationException
     *             Serialization error occurred.
     */
    public ZkNodeEntry<UUID, BridgeConfig> get(UUID id)
            throws StateAccessException {
        return get(id, null);
    }

    /**
     * Gets a ZooKeeper node entry key-value pair of a bridge with the given ID
     * and sets a watcher on the node.
     *
     * @param id
     *            The ID of the bridge.
     * @param watcher
     *            The watcher that gets notified when there is a change in the
     *            node.
     * @return Route object found.
     * @throws ZkStateSerializationException
     *             Serialization error occurred.
     */
    public ZkNodeEntry<UUID, BridgeConfig> get(UUID id, Runnable watcher)
            throws StateAccessException, ZkStateSerializationException {
        byte[] data = get(pathManager.getBridgePath(id), watcher);
        BridgeConfig config = serializer.deserialize(data, BridgeConfig.class);
        return new ZkNodeEntry<UUID, BridgeConfig>(id, config);
    }

    /***
     * Deletes a bridge and its related data from the ZooKeeper directories
     * atomically.
     *
     * @param id
     *            ID of the bridge to delete.
     * @throws ZkStateSerializationException
     *             Serialization error occurred.
     */
    public void delete(UUID id) throws StateAccessException,
            ZkStateSerializationException {
        multi(prepareBridgeDelete(id));
    }

}
