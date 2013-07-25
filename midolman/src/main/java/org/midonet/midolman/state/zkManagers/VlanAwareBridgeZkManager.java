/*
 * Copyright 2012 Midokura Pte. Ltd.
 */
package org.midonet.midolman.state.zkManagers;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs.Ids;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.AbstractZkManager;
import org.midonet.midolman.state.ZkManager;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.StateAccessException;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Set;

import static org.slf4j.LoggerFactory.getLogger;

/**
 * Class to manage the bridge ZooKeeper data.
 */
public class VlanAwareBridgeZkManager extends AbstractZkManager {

    private final static Logger log = getLogger(VlanAwareBridgeZkManager.class);

    public static class VlanBridgeConfig {

        public VlanBridgeConfig() {
            super();
        }

        private int tunnelKey;
        private String name;
        private Map<String, String> properties = new HashMap<String, String>();

        public void setTunnelKey(int tunnelKey) {
            this.tunnelKey = tunnelKey;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        public int getTunnelKey() {
            return tunnelKey;
        }

        /**
         * WARNING: gives the actual map, not a copy.
         */
        public Map<String, String> getProperties() {
            return this.properties;
        }

        /**
         * WARNING: references the given instance, no copies are made.
         */
        public void setProperties(Map<String, String> properties) {
            this.properties = properties;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;

            VlanBridgeConfig that = (VlanBridgeConfig) o;

            if (tunnelKey != that.tunnelKey)
                return false;
            if (name != null ? !name.equals(that.name) : that.name != null)
                return false;

            return true;
        }

        @Override
        public int hashCode() {
            int result = tunnelKey;
            result = 31 * result + (name != null ? name.hashCode() : 0);
            return result;
        }

        @Override
        public String toString() {
            return "VlanBridgeconfig{" + "tunnelKey=" + tunnelKey +
                   ", name=" + name + "}";
        }
    }

    private TunnelZkManager tunnelZkManager;
    private PortZkManager portZkManager;

    /**
     * Initializes a VLANAwareBridgeZkManager object with a ZooKeeper client and
     * the root path of the ZooKeeper directory.
     *
     * @param zk
     *         Zk data access class
     * @param paths
     *         PathBuilder class to construct ZK paths
     * @param serializer
     *         ZK data serialization class
     */
    public VlanAwareBridgeZkManager(ZkManager zk, PathBuilder paths,
                                    Serializer serializer) {
        super(zk, paths, serializer);
        this.tunnelZkManager = new TunnelZkManager(zk, paths, serializer);
        this.portZkManager = new PortZkManager(zk, paths, serializer);
    }

    public VlanAwareBridgeZkManager(Directory dir, String basePath,
                                    Serializer serializer) {
        this(new ZkManager(dir), new PathBuilder(basePath), serializer);
    }

    /**
     * Constructs a list of ZooKeeper update operations to perform when adding a
     * new vlan bridge.
     *
     * @param id ID of the bridge.
     * @param config
     * @return A list of Op objects to represent the operations to perform.
     * @throws StateAccessException Error accessing ZooKeeper.
     */
    public List<Op> prepareVlanBridgeCreate(UUID id, VlanBridgeConfig config)
            throws StateAccessException, SerializationException {

        // Create a new Tunnel key. Hide this from outside.
        int tunnelKeyId = tunnelZkManager.createTunnelKeyId();
        config.tunnelKey = tunnelKeyId;

        List<Op> ops = new ArrayList<Op>();
        ops.add(Op.create(paths.getVlanBridgePath(id),
                serializer.serialize(config),
                Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT));

        ops.add(Op.create(paths.getVlanBridgeTrunkPortsPath(id), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));

        ops.add(Op.create(paths.getVlanBridgeLogicalPortsPath(id), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));

        // Add a port-set for this bridge
        ops.add(Op.create(paths.getPortSetPath(id),
                          null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));

        // Update TunnelKey to reference the bridge.
        TunnelZkManager.TunnelKey tunnelKey = new TunnelZkManager.TunnelKey(id);
        ops.addAll(tunnelZkManager.prepareTunnelUpdate(tunnelKeyId, tunnelKey));
        return ops;
    }

    /**
     * Construct a list of ZK operations needed to update the configuration of a
     * vlan bridge.
     *
     * @param id ID of the bridge to update
     * @param config the new bridge configuration.
     * @return The ZK operation required to update the bridge.
     * @throws StateAccessException the VlanBridgeconfig could not be serialized.
     */
    public Op prepareUpdate(UUID id, VlanBridgeConfig config)
            throws StateAccessException, SerializationException {
        VlanBridgeConfig oldConfig = get(id);
        // Have the name, inbound or outbound filter changed?
        boolean dataChanged = false;

        if ((oldConfig.name == null && config.name != null) ||
                (oldConfig.name != null && config.name == null) ||
                !oldConfig.name.equals(config.name)) {
            log.debug("The name of vlan bridge {} changed from {} to {}",
                    new Object[]{id, oldConfig.name, config.name});
            dataChanged = true;
        }

        if (dataChanged) {
            // Update the midolman data. Don't change the Vlan Bridge's GRE-key.
            config.tunnelKey = oldConfig.tunnelKey;
            return Op.setData(paths.getVlanBridgePath(id),
                    serializer.serialize(config), -1);
        }
        return null;
    }

    public List<Op> prepareVlanBridgeDelete(UUID id)
            throws StateAccessException, SerializationException {
        return prepareVlanBridgeDelete(id, get(id));
    }

    /**
     * @return A list of Op objects representing the operations to perform.
     * @throws StateAccessException
     */
    public List<Op> prepareVlanBridgeDelete(UUID id, VlanBridgeConfig config)
            throws StateAccessException, SerializationException {
        List<Op> ops = new ArrayList<Op>();
        // Delete the ports.
        Set<UUID> portIds = portZkManager.getVlanBridgeTrunkPortIDs(id);
        for (UUID portId : portIds) {
            ops.addAll(portZkManager.prepareDelete(portId));
        }

        portIds = portZkManager.getVlanBridgeLogicalPortIDs(id);
        for (UUID portId : portIds) {
            ops.addAll(portZkManager.prepareDelete(portId));
        }

        ops.add(Op.delete(paths.getVlanBridgeTrunkPortsPath(id), -1));
        ops.add(Op.delete(paths.getVlanBridgeLogicalPortsPath(id), -1));

        // Delete GRE
        ops.addAll(tunnelZkManager.prepareTunnelDelete(config.tunnelKey));

        // Delete this bridge's port-set
        ops.addAll(zk.getRecursiveDeleteOps(paths.getPortSetPath(id)));

        // Delete the bridge
        ops.add(Op.delete(paths.getVlanBridgePath(id), -1));

        return ops;
    }

    /**
     * Performs an atomic update on the ZooKeeper to add a new bridge entry.
     *
     * @param bridge Vlan aware Bridge object to add to the ZooKeeper directory.
     * @return The UUID of the newly created object.
     * @throws StateAccessException Serialization error occurred.
     */
    public UUID create(VlanBridgeConfig bridge) throws StateAccessException,
            SerializationException {
        UUID id = UUID.randomUUID();
        zk.multi(prepareVlanBridgeCreate(id, bridge));
        return id;
    }

    /**
     * Checks whether a vlan aware bridge with the given ID exists.
     *
     * @param id Vlan Aware Bridge ID to check
     * @return True if exists
     * @throws StateAccessException
     */
    public boolean exists(UUID id) throws StateAccessException {
        return zk.exists(paths.getVlanBridgePath(id));
    }

    public void update(UUID id, VlanBridgeConfig cfg)
            throws StateAccessException, SerializationException {
        Op op = prepareUpdate(id, cfg);
        if (null != op) {
            List<Op> ops = new ArrayList<Op>();
            ops.add(op);
            zk.multi(ops);
        }
    }

    /**
     * Gets a ZooKeeper node entry key-value pair of a bridge with the given ID.
     *
     * @param id The ID of the bridge.
     * @return Bridge object found.
     */
    public VlanBridgeConfig get(UUID id)
            throws StateAccessException, SerializationException {
        return get(id, null);
    }

    /**
     * Gets a ZooKeeper node entry key-value pair of a vlan aware bridge with
     * the given ID and sets a watcher on the node.
     *
     * @param id The ID of the vlan aware bridge.
     * @param watcher The watcher that gets notified when there is a change in
     *                the node.
     * @return Route object found.
     */
    public VlanBridgeConfig get(UUID id, Runnable watcher)
            throws StateAccessException, SerializationException {
        byte[] data = zk.get(paths.getVlanBridgePath(id), watcher);
        return serializer.deserialize(data, VlanBridgeConfig.class);
    }

    /***
     * Deletes a bridge and its related data from the ZooKeeper directories
     * atomically.
     *
     * @param id ID of the bridge to delete.
     */
    public void delete(UUID id)
            throws StateAccessException, SerializationException {
        zk.multi(prepareVlanBridgeDelete(id));
    }

}
