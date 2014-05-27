/*
 * Copyright (c) 2012-2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.midolman.state.zkManagers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.apache.zookeeper.Op;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.cluster.data.Bridge;
import org.midonet.cluster.data.neutron.Network;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.AbstractZkManager;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkManager;


/**
 * Class to manage the bridge ZooKeeper data.
 */
public class BridgeZkManager
        extends AbstractZkManager<UUID, BridgeZkManager.BridgeConfig> {

    private final static Logger log = LoggerFactory
            .getLogger(BridgeZkManager.class);

    /**
     * Thrown in response to an invalid (i.e., user-requested) update
     * to a bridge's VxLanPortId property.
     */
    public static class VxLanPortIdUpdateException extends Exception {
        private static final long serialVersionUID = 1L;
    }

    public static class BridgeConfig extends BaseConfig
                                     implements TaggableConfig {
        public BridgeConfig() {
            super();
        }

        public BridgeConfig(String name, UUID inboundFilter, UUID outboundFilter) {
            super();
            this.name = name;
            this.inboundFilter = inboundFilter;
            this.outboundFilter = outboundFilter;
        }

        public BridgeConfig(Network network) {
            super();
            this.name = network.name;
            this.adminStateUp = network.adminStateUp;
        }

        // TODO: Make this private with a getter.
        public int tunnelKey; // Only set in prepareBridgeCreate
        public UUID inboundFilter;
        public UUID outboundFilter;
        public UUID vxLanPortId;
        public String name;
        public boolean adminStateUp;
        public Map<String, String> properties = new HashMap<>();

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;

            BridgeConfig that = (BridgeConfig) o;

            return tunnelKey == that.tunnelKey &&
                    adminStateUp == that.adminStateUp &&
                    Objects.equals(inboundFilter, that.inboundFilter) &&
                    Objects.equals(outboundFilter, that.outboundFilter) &&
                    Objects.equals(vxLanPortId, that.vxLanPortId) &&
                    Objects.equals(name, that.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(tunnelKey, adminStateUp, inboundFilter,
                                outboundFilter, vxLanPortId, name);
        }

        @Override
        public String toString() {
            return "BridgeConfig{tunnelKey=" + tunnelKey +
                   ", inboundFilter=" + inboundFilter +
                   ", outboundFilter=" + outboundFilter +
                   ", vxLanPortId=" + vxLanPortId +
                   ", name=" + name +
                   ", adminStateUp=" + adminStateUp + '}';
        }
    }

    private FiltersZkManager filterZkManager;
    private TunnelZkManager tunnelZkManager;
    private PortZkManager portZkManager;
    private ChainZkManager chainZkManager;

    /**
     * Initializes a BridgeZkManager object with a ZooKeeper client and the root
     * path of the ZooKeeper directory.
     *
     * @param zk
     *         Zk data access class
     * @param paths
     *         PathBuilder class to construct ZK paths
     * @param serializer
     *         ZK data serialization class
     */
    public BridgeZkManager(ZkManager zk, PathBuilder paths,
                           Serializer serializer) {
        super(zk, paths, serializer);
        filterZkManager = new FiltersZkManager(zk, paths, serializer);
        tunnelZkManager = new TunnelZkManager(zk, paths, serializer);
        portZkManager = new PortZkManager(zk, paths, serializer);
        chainZkManager = new ChainZkManager(zk, paths, serializer);
    }

    @Override
    protected String getConfigPath(UUID id) {
        return paths.getBridgePath(id);
    }

    @Override
    protected Class<BridgeConfig> getConfigClass() {
        return BridgeConfig.class;
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
     * @throws org.midonet.midolman.serialization.SerializationException
     *             Serialization error occurred.
     * @throws StateAccessException
     *             Error accessing ZooKeeper.
     */
    public List<Op> prepareBridgeCreate(UUID id, BridgeConfig config)
            throws StateAccessException, SerializationException {

        // Create a new Tunnel key. Hide this from outside.
        int tunnelKeyId = tunnelZkManager.createTunnelKeyId();
        config.tunnelKey = tunnelKeyId;

        List<Op> ops = new ArrayList<>();
        ops.add(simpleCreateOp(id, config));

        if (config.inboundFilter != null) {
            ops.addAll(chainZkManager.prepareChainBackRefCreate(
                    config.inboundFilter, ResourceType.BRIDGE, id));
        }
        if (config.outboundFilter != null) {
            ops.addAll(chainZkManager.prepareChainBackRefCreate(
                    config.outboundFilter, ResourceType.BRIDGE, id));
        }

        ops.addAll(zk.getPersistentCreateOps(
                paths.getBridgePortsPath(id),
                paths.getBridgeLogicalPortsPath(id),
                paths.getBridgeDhcpPath(id),
                paths.getBridgeDhcpV6Path(id),
                paths.getBridgeMacPortsPath(id, Bridge.UNTAGGED_VLAN_ID),
                paths.getBridgeIP4MacMapPath(id),
                paths.getBridgeTagsPath(id),
                paths.getBridgeVlansPath(id),
                paths.getPortSetPath(id)));

        // Update TunnelKey to reference the bridge.
        TunnelZkManager.TunnelKey tunnelKey = new TunnelZkManager.TunnelKey(id);
        ops.addAll(tunnelZkManager.prepareTunnelUpdate(tunnelKeyId, tunnelKey));

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
     * @param userUpdate
     *            Pass true if the user has explicitly requested this
     *            bridge update. Controls whether certain updates are
     *            allowed.
     * @return The ZK operation required to update the bridge.
     * @throws org.midonet.midolman.serialization.SerializationException
     *             if the BridgeConfig could not be serialized.
     */
    public List<Op> prepareUpdate(UUID id, BridgeConfig config,
                                  boolean userUpdate)
            throws StateAccessException, SerializationException,
            VxLanPortIdUpdateException {
        BridgeConfig oldConfig = get(id);
        // Have the name, inbound or outbound filter changed?
        boolean dataChanged = false;

        if (!Objects.equals(oldConfig.name, config.name)) {
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

        if (config.adminStateUp != oldConfig.adminStateUp) {
            log.debug("The admin state of bridge {} changed from {} to {}",
                    new Object[] { id, oldConfig.adminStateUp,
                                   config.adminStateUp });
            dataChanged = true;
        }

        if (!Objects.equals(config.vxLanPortId, oldConfig.vxLanPortId)) {
            // Users cannot set vxLanPortId directly.
            if (userUpdate)
                throw new VxLanPortIdUpdateException();
            log.debug("The vxLanPortId of bridge{} changed from {} to {}",
                    new Object[]{id, oldConfig.vxLanPortId, config.vxLanPortId});
            dataChanged = true;
        }

        List<Op> ops = new ArrayList<>();
        if (dataChanged) {
            // Update the midolman data. Don't change the Bridge's GRE-key.
            config.tunnelKey = oldConfig.tunnelKey;
            ops.add(simpleUpdateOp(id, config));
            ops.addAll(chainZkManager.prepareUpdateFilterBackRef(
                    ResourceType.BRIDGE,
                    oldConfig.inboundFilter,
                    config.inboundFilter,
                    oldConfig.outboundFilter,
                    config.outboundFilter,
                    id));
        }
        return ops;
    }

    public List<Op> prepareBridgeDelete(UUID id) throws StateAccessException,
            SerializationException {
        return prepareBridgeDelete(id, get(id));
    }

    /**
     * Constructs a list of operations to perform in a bridge deletion.
     *
     * @return A list of Op objects representing the operations to perform.
     * @throws org.midonet.midolman.serialization.SerializationException
     *             Serialization error occurred.
     */
    public List<Op> prepareBridgeDelete(UUID id, BridgeConfig config)
            throws StateAccessException, SerializationException {
        List<Op> ops = new ArrayList<>();

        if (config.inboundFilter != null) {
            ops.addAll(chainZkManager.prepareChainBackRefDelete(
                    config.inboundFilter, ResourceType.BRIDGE, id));
        }
        if (config.outboundFilter != null) {
            ops.addAll(chainZkManager.prepareChainBackRefDelete(
                    config.outboundFilter, ResourceType.BRIDGE, id));
        }

        // Delete the ports.
        Collection<UUID> portIds = portZkManager.getBridgePortIDs(id);
        for (UUID portId : portIds) {
            ops.addAll(portZkManager.prepareDelete(portId));
        }

        portIds = portZkManager.getBridgeLogicalPortIDs(id);
        for (UUID portId : portIds) {
            ops.addAll(portZkManager.prepareDelete(portId));
        }

        ops.add(Op.delete(paths.getBridgePortsPath(id), -1));
        ops.add(Op.delete(paths.getBridgeLogicalPortsPath(id), -1));
        ops.addAll(zk.getRecursiveDeleteOps(paths.getBridgeDhcpPath(id)));
        ops.addAll(zk.getRecursiveDeleteOps(paths.getBridgeDhcpV6Path(id)));
        ops.addAll(zk.getRecursiveDeleteOps(
                paths.getBridgeMacPortsPath(id, Bridge.UNTAGGED_VLAN_ID)));
        // The bridge may have been created before the tagging was added.
        if (zk.exists(paths.getBridgeTagsPath(id))) {
            ops.addAll(zk.getRecursiveDeleteOps(paths.getBridgeTagsPath(id)));
        }
        // The bridge may have been created before the ARP feature was added.
        if (zk.exists(paths.getBridgeIP4MacMapPath(id)))
            ops.addAll(zk.getRecursiveDeleteOps(
                    paths.getBridgeIP4MacMapPath(id)));

        if (zk.exists(paths.getBridgeVlansPath(id))){
            ops.add(Op.delete(paths.getBridgeVlansPath(id), -1));
        }

        // Delete GRE
        ops.addAll(tunnelZkManager.prepareTunnelDelete(config.tunnelKey));

        // Delete this bridge's port-set
        ops.addAll(zk.getRecursiveDeleteOps(paths.getPortSetPath(id)));

        // Delete the bridge
        ops.add(Op.delete(paths.getBridgePath(id), -1));

        ops.addAll(filterZkManager.prepareDelete(id));

        return ops;
    }

    /**
     * removes any reference to the given chainId from the inbound/outbound
     * filters of the bridge identified by id. Called when the chain is
     * deleted.
     */
    public List<Op> prepareClearRefsToChains(UUID id, UUID chainId)
            throws SerializationException, StateAccessException,
            IllegalArgumentException {
        if (chainId == null || id == null) {
            throw new IllegalArgumentException(
                    "chainId and id both must not be valid " +
                            "resource references");
        }
        boolean dataChanged = false;
        BridgeConfig config = get(id);
        if (Objects.equals(config.inboundFilter, chainId)) {
            config.inboundFilter = null;
            dataChanged = true;
        }
        if (Objects.equals(config.outboundFilter, chainId)) {
            config.outboundFilter = null;
            dataChanged = true;
        }

        return dataChanged ?
                Collections.singletonList(Op.setData(paths.getBridgePath(id),
                        serializer.serialize(config), -1)) :
                Collections.<Op>emptyList();
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
    public UUID create(BridgeConfig bridge) throws StateAccessException,
            SerializationException {
        UUID id = UUID.randomUUID();
        zk.multi(prepareBridgeCreate(id, bridge));
        return id;
    }

    /**
     * Ensures the bridge has a VLANs directory.
     * For backwards compatibility with pre-1.2
     */
    public void ensureBridgeHasVlanDirectory(UUID id)
            throws StateAccessException {
        if(!zk.exists(paths.getBridgeVlansPath(id))) {
            zk.addPersistent(paths.getBridgeVlansPath(id), null);
        }
    }

    public void update(UUID id, BridgeConfig cfg, boolean userUpdate)
            throws StateAccessException, SerializationException,
            VxLanPortIdUpdateException {
        List<Op> ops = prepareUpdate(id, cfg, userUpdate);
        if (!ops.isEmpty())
            zk.multi(ops);
    }

    /***
     * Deletes a bridge and its related data from the ZooKeeper directories
     * atomically.
     *
     * @param id
     *            ID of the bridge to delete.
     */
    public void delete(UUID id) throws StateAccessException,

            SerializationException {
        zk.multi(prepareBridgeDelete(id));
    }

    // This method creates the directory if it doesn't already exist,
    // because bridges may have been created before the ARP feature was added.
    public Directory getIP4MacMapDirectory(UUID id)
            throws StateAccessException {
        String path = paths.getBridgeIP4MacMapPath(id);
        if (exists(id) && !zk.exists(path))
            zk.addPersistent(path, null);

        return zk.getSubDirectory(path);
    }


    public Directory getMacPortMapDirectory(UUID id, short vlanId)
            throws StateAccessException{
        return zk.getSubDirectory(paths.getBridgeMacPortsPath(id, vlanId));
    }

    public short[] getVlanIds(UUID id)
            throws StateAccessException {
        ensureBridgeHasVlanDirectory(id);
        Collection<String> children =
                zk.getChildren(paths.getBridgeVlansPath(id));
        short[] vlanIds = new short[children.size()];
        int i = 0;
        for (String child : children) {
            try {
                // Don't change this to vlanIds[i++] = ..., because i will get
                // incremented even when parseShort() throws an exception.
                vlanIds[i] = Short.parseShort(child);
                i++;
            } catch (NumberFormatException ex) {
                // Log a warning and ignore it.
                log.error("Ignoring invalid VLAN ID '{}' for bridge {}. VLAN " +
                          "table in Zookeeper is corrupt.", child, id);
            }
        }

        // i != children.size() only if parseShort() threw an exception above,
        // which only happens if Zookeeper is corrupt.
        return (i == children.size()) ? vlanIds : Arrays.copyOf(vlanIds, i);
    }

    public boolean hasVlanMacTable(UUID id, short vlanId)
            throws StateAccessException {
        return zk.exists(paths.getBridgeMacPortsPath(id, vlanId));
    }
}
