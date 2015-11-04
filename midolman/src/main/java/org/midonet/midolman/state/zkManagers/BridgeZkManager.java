/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.midolman.state.zkManagers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.apache.zookeeper.Op;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.cluster.WatchableZkManager;
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
        extends AbstractZkManager<UUID, BridgeZkManager.BridgeConfig>
        implements WatchableZkManager<UUID, BridgeZkManager.BridgeConfig> {

    private final static Logger log = LoggerFactory
            .getLogger(BridgeZkManager.class);

    /**
     * Thrown in response to an invalid (i.e., user-requested) update
     * to a bridge's VxLanPortId property.
     */
    public static class VxLanPortIdUpdateException extends Exception {
        private static final long serialVersionUID = 1L;
    }

    public static class BridgeConfig extends ConfigWithProperties {
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
        public List<UUID> vxLanPortIds = new ArrayList<>(0);
        public String name;
        public boolean adminStateUp;
        public boolean disableAntiSpoof;

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
                    Objects.equals(vxLanPortIds, that.vxLanPortIds) &&
                    Objects.equals(disableAntiSpoof, that.disableAntiSpoof) &&
                    Objects.equals(name, that.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(tunnelKey, adminStateUp, inboundFilter,
                                outboundFilter, vxLanPortId, vxLanPortIds,
                                name, disableAntiSpoof);
        }

        @Override
        public String toString() {
            return "BridgeConfig{tunnelKey=" + tunnelKey +
                   ", inboundFilter=" + inboundFilter +
                   ", outboundFilter=" + outboundFilter +
                   ", vxLanPortId=" + vxLanPortId +
                   ", vxLanPortIds=" + vxLanPortIds +
                   ", name=" + name +
                   ", adminStateUp=" + adminStateUp +
                   ", disableAntiSpoof=" + disableAntiSpoof + '}';
        }
    }

    private FiltersZkManager filterZkManager;
    private TunnelZkManager tunnelZkManager;
    private PortZkManager portZkManager;
    private ChainZkManager chainZkManager;
    private TraceRequestZkManager traceReqZkManager;

    /**
     * Initializes a BridgeZkManager object with a ZooKeeper client and the root
     * path of the ZooKeeper directory.
     */
    public BridgeZkManager(ZkManager zk, PathBuilder paths,
                           Serializer serializer) {
        super(zk, paths, serializer);
        filterZkManager = new FiltersZkManager(zk, paths, serializer);
        tunnelZkManager = new TunnelZkManager(zk, paths, serializer);
        portZkManager = new PortZkManager(zk, paths, serializer);
        chainZkManager = new ChainZkManager(zk, paths, serializer);
        traceReqZkManager = new TraceRequestZkManager(zk, paths, serializer);
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
                paths.getBridgeVlansPath(id)));

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
     * This operation assumes that validations have been performed before the
     * call. These include for example modifications on the vxlan port id that
     * is not allowed to be made directly by the user.
     *
     * @param id ID of the bridge to update
     * @param newConfig the new bridge configuration.
     * @return The ZK operation required to update the bridge.
     * @throws org.midonet.midolman.serialization.SerializationException
     *             if the BridgeConfig could not be serialized.
     */
    public List<Op> prepareUpdate(UUID id, BridgeConfig newConfig)
            throws StateAccessException, SerializationException {
        BridgeConfig oldConfig = get(id);
        // Have the name, inbound or outbound filter changed?
        boolean dataChanged = false;

        if (!Objects.equals(oldConfig.name, newConfig.name)) {
            log.debug("The name of bridge {} changed from {} to {}",
                      id, oldConfig.name, newConfig.name);
            dataChanged = true;
        }

        if (!Objects.equals(oldConfig.disableAntiSpoof,
                            newConfig.disableAntiSpoof)) {
            log.debug("The disable-anti-spoofing of bridge " +
                      "{} changed from {} to {}",
                      id, oldConfig.disableAntiSpoof,
                      newConfig.disableAntiSpoof);
            dataChanged = true;
        }

        UUID id1 = oldConfig.inboundFilter;
        UUID id2 = newConfig.inboundFilter;
        if (id1 == null ? id2 != null : !id1.equals(id2)) {
            log.debug("The inbound filter of bridge {} changed from {} to {}",
                      id, id1, id2);
            dataChanged = true;
        }
        id1 = oldConfig.outboundFilter;
        id2 = newConfig.outboundFilter;
        if (id1 == null ? id2 != null : !id1.equals(id2)) {
            log.debug("The outbound filter of bridge {} changed from {} to {}",
                      id, id1, id2);
            dataChanged = true;
        }

        if (newConfig.adminStateUp != oldConfig.adminStateUp) {
            log.debug("The admin state of bridge {} changed from {} to {}",
                      id, oldConfig.adminStateUp, newConfig.adminStateUp);
            dataChanged = true;
        }

        if (!Objects.equals(oldConfig.vxLanPortId, newConfig.vxLanPortId)) {
            log.debug("VTEP bindings on bridge {} changed, port to VTEP changes"
                      + " from {} to {}", id, oldConfig.vxLanPortId,
                      newConfig.vxLanPortId);
            dataChanged = true;
        }

        if (!Objects.equals(oldConfig.vxLanPortIds, newConfig.vxLanPortIds)) {
            log.debug("VTEP bindings on bridge {} changed, ports to VTEP were "
                      + " {}, now: {}", id, oldConfig.vxLanPortIds,
                      newConfig.vxLanPortIds);
            dataChanged = true;
        }

        List<Op> ops = new ArrayList<>();
        if (dataChanged) {
            // Update the midolman data. Don't change the Bridge's GRE-key.
            newConfig.tunnelKey = oldConfig.tunnelKey;
            ops.add(simpleUpdateOp(id, newConfig));
            ops.addAll(chainZkManager.prepareUpdateFilterBackRef(
                    ResourceType.BRIDGE,
                    oldConfig.inboundFilter,
                    newConfig.inboundFilter,
                    oldConfig.outboundFilter,
                    newConfig.outboundFilter,
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

        // delete any trace requests for device
        traceReqZkManager.deleteForDevice(id);

        if (config.inboundFilter != null) {
            ops.addAll(chainZkManager.prepareChainBackRefDelete(
                    config.inboundFilter, ResourceType.BRIDGE, id));
        }
        if (config.outboundFilter != null) {
            ops.addAll(chainZkManager.prepareChainBackRefDelete(
                    config.outboundFilter, ResourceType.BRIDGE, id));
        }

        if (config.vxLanPortIds != null && !config.vxLanPortIds.isEmpty()) {
            throw new IllegalStateException("A Bridge cannot be deleted if it"
                                            + " has bindings to a VTEP.");
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

    public void update(UUID id, BridgeConfig newCfg)
            throws StateAccessException, SerializationException {
        List<Op> ops = prepareUpdate(id, newCfg);
        if (!ops.isEmpty()) {
            zk.multi(ops);
        }
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

    @Override
    public List<UUID> getAndWatchIdList(Runnable watcher)
        throws StateAccessException {
        return getUuidList(paths.getBridgesPath(), watcher);
    }
}
