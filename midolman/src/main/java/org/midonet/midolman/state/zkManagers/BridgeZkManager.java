/*
 * Copyright 2012 Midokura Pte. Ltd.
 */
package org.midonet.midolman.state.zkManagers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooDefs.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkManager;

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

        public BridgeConfig(String name, UUID inboundFilter, UUID outboundFilter) {
            super();
            this.name = name;
            this.inboundFilter = inboundFilter;
            this.outboundFilter = outboundFilter;
        }

        // TODO: Make this private with a getter.
        public int tunnelKey; // Only set in prepareBridgeCreate
        public UUID inboundFilter;
        public UUID outboundFilter;
        public String name;
        public Map<String, String> properties = new HashMap<String, String>();

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;

            BridgeConfig that = (BridgeConfig) o;

            if (tunnelKey != that.tunnelKey)
                return false;
            if (inboundFilter != null ? !inboundFilter
                    .equals(that.inboundFilter) : that.inboundFilter != null)
                return false;
            if (outboundFilter != null ? !outboundFilter
                    .equals(that.outboundFilter) : that.outboundFilter != null)
                return false;
            if (name != null ? !name.equals(that.name) : that.name != null)
                return false;

            return true;
        }

        @Override
        public int hashCode() {
            int result = tunnelKey;
            result = 31 * result
                    + (inboundFilter != null ? inboundFilter.hashCode() : 0);
            result = 31 * result
                    + (outboundFilter != null ? outboundFilter.hashCode() : 0);
            result = 31 * result
                    + (name != null ? name.hashCode() : 0);
            return result;
        }

        @Override
        public String toString() {
            return "BridgeConfig{" + "tunnelKey=" + tunnelKey + ", inboundFilter="
                    + inboundFilter + ", outboundFilter=" + outboundFilter
                    + ", name=" + name + '}';
        }
    }

    private FiltersZkManager filterZkManager;
    private TunnelZkManager tunnelZkManager;
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
        this.filterZkManager = new FiltersZkManager(zk, basePath);
        this.tunnelZkManager = new TunnelZkManager(zk, basePath);
        this.portZkManager = new PortZkManager(zk, basePath);
    }

    /**
     * Constructs a list of ZooKeeper update operations to perform when adding a
     * new bridge.
     *
     * @param id
     *            ID of the bridge.
     * @param config
     *            BridgeConfig object
     * @return A list of Op objects to represent the operations to perform.
     * @throws org.midonet.midolman.state.ZkStateSerializationException
     *             Serialization error occurred.
     * @throws StateAccessException
     *             Error accessing ZooKeeper.
     */
    public List<Op> prepareBridgeCreate(UUID id, BridgeConfig config)
            throws StateAccessException {

        // Create a new Tunnel key. Hide this from outside.
        int tunnelKey = tunnelZkManager.createTunnelKey();
        config.tunnelKey = tunnelKey;

        List<Op> ops = new ArrayList<Op>();
        ops.add(Op.create(paths.getBridgePath(id),
                serializer.serialize(config), Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT));

        ops.add(Op.create(paths.getBridgePortsPath(id), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));

        ops.add(Op.create(paths.getBridgeLogicalPortsPath(id), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));

        ops.add(Op.create(paths.getBridgeDhcpPath(id), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));

        ops.add(Op.create(paths.getBridgeMacPortsPath(id), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));

        ops.add(Op.create(paths.getBridgeIP4MacMapPath(id), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));

        // Add a port-set for this bridge
        ops.add(Op.create(paths.getPortSetPath(id),
                          null, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));

        // Update TunnelKey to reference the bridge.
        TunnelZkManager.TunnelKey tunnel = new TunnelZkManager.TunnelKey(id);
        ops.addAll(tunnelZkManager.prepareTunnelUpdate(tunnelKey, tunnel));

        ops.addAll(filterZkManager.prepareCreate(id));
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
     * @throws org.midonet.midolman.state.ZkStateSerializationException
     *             if the BridgeConfig could not be serialized.
     */
    public Op prepareUpdate(UUID id, BridgeConfig config)
            throws StateAccessException {
        BridgeConfig oldConfig = get(id);
        // Have the name, inbound or outbound filter changed?
        boolean dataChanged = false;

        if ((oldConfig.name == null && config.name != null) ||
                (oldConfig.name != null && config.name == null) ||
                !oldConfig.name.equals(config.name)) {
            log.debug("The name of bridge {} changed from {} to {}",
                    new Object[]{id, oldConfig.name, config.name});
            dataChanged = true;
        }

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
            config.tunnelKey = oldConfig.tunnelKey;
            return Op.setData(paths.getBridgePath(id),
                    serializer.serialize(config), -1);
        }
        return null;
    }

    public List<Op> prepareBridgeDelete(UUID id) throws StateAccessException {
        return prepareBridgeDelete(id, get(id));
    }

    /**
     * Constructs a list of operations to perform in a bridge deletion.
     *
     * @return A list of Op objects representing the operations to perform.
     * @throws org.midonet.midolman.state.ZkStateSerializationException
     *             Serialization error occurred.
     */
    public List<Op> prepareBridgeDelete(UUID id, BridgeConfig config)
            throws StateAccessException {
        List<Op> ops = new ArrayList<Op>();
        // Delete the ports.
        Set<UUID> portIds = portZkManager.getBridgePortIDs(id);
        for (UUID portId : portIds) {
            ops.addAll(portZkManager.prepareDelete(portId));
        }

        portIds = portZkManager.getBridgeLogicalPortIDs(id);
        for (UUID portId : portIds) {
            ops.addAll(portZkManager.prepareDelete(portId));
        }

        ops.add(Op.delete(paths.getBridgePortsPath(id), -1));
        ops.add(Op.delete(paths.getBridgeLogicalPortsPath(id), -1));
        ops.addAll(getRecursiveDeleteOps(paths.getBridgeDhcpPath(id)));
        ops.addAll(getRecursiveDeleteOps(paths.getBridgeMacPortsPath(id)));
        // The bridge may have been created before the ARP feature was added.
        if (exists(paths.getBridgeIP4MacMapPath(id)))
            ops.addAll(getRecursiveDeleteOps(paths.getBridgeIP4MacMapPath(id)));

        // Delete GRE
        ops.addAll(tunnelZkManager.prepareTunnelDelete(config.tunnelKey));

        // Delete this bridge's port-set
        ops.addAll(getRecursiveDeleteOps(paths.getPortSetPath(id)));

        // Delete the bridge
        ops.add(Op.delete(paths.getBridgePath(id), -1));

        ops.addAll(filterZkManager.prepareDelete(id));

        return ops;
    }

    /**
     * Performs an atomic update on the ZooKeeper to add a new bridge entry.
     *
     * @param bridge
     *            Bridge object to add to the ZooKeeper directory.
     * @return The UUID of the newly created object.
     * @throws StateAccessException
     *             Serialization error occurred.
     */
    public UUID create(BridgeConfig bridge) throws StateAccessException {
        UUID id = UUID.randomUUID();
        multi(prepareBridgeCreate(id, bridge));
        return id;
    }

    /**
     * Checks whether a bridge with the given ID exists.
     *
     * @param id
     *            Bridge ID to check
     * @return True if exists
     * @throws StateAccessException
     */
    public boolean exists(UUID id) throws StateAccessException {
        return exists(paths.getBridgePath(id));
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
     */
    public BridgeConfig get(UUID id) throws StateAccessException {
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
     */
    public BridgeConfig get(UUID id, Runnable watcher)
            throws StateAccessException {
        byte[] data = get(paths.getBridgePath(id), watcher);
        return serializer.deserialize(data, BridgeConfig.class);
    }

    /***
     * Deletes a bridge and its related data from the ZooKeeper directories
     * atomically.
     *
     * @param id
     *            ID of the bridge to delete.
     */
    public void delete(UUID id) throws StateAccessException {
        multi(prepareBridgeDelete(id));
    }

    // This method creates the directory if it doesn't already exist,
    // because bridges may have been created before the ARP feature was added.
    public Directory getIP4MacMapDirectory(UUID id)
            throws StateAccessException {
        String path = paths.getBridgeIP4MacMapPath(id);
        if (exists(id) && !exists(path))
            addPersistent(path, null);
        try {
            return zk.getSubDirectory(path);
        } catch (KeeperException e) {
            throw new StateAccessException(e);
        }
    }

    public Directory getMacPortMapDirectory(UUID id)
        throws StateAccessException{
        try {
            return zk.getSubDirectory(paths.getBridgeMacPortsPath(id));
        } catch (KeeperException e) {
            throw new StateAccessException(e);
        }
    }
}
