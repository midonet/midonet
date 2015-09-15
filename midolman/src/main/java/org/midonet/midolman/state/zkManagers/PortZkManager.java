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
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.google.common.base.Function;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.AbstractZkManager;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.DirectoryCallback;
import org.midonet.midolman.state.NoStatePathException;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.PortConfig;
import org.midonet.midolman.state.PortDirectory;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkManager;
import org.midonet.packets.IPv4Subnet;

/**
 * Class to manage the port ZooKeeper data.
 */
public class PortZkManager extends AbstractZkManager<UUID, PortConfig> {

    private final static Logger log =
            LoggerFactory.getLogger(PortZkManager.class);

    private final BgpZkManager bgpManager;
    private final ChainZkManager chainZkManager;
    private final FiltersZkManager filterZkManager;
    private final RouteZkManager routeZkManager;
    private final TunnelZkManager tunnelZkManager;
    private final TraceRequestZkManager traceReqZkManager;

    /**
     * Initializes a PortZkManager object with a ZooKeeper client and the root
     * path of the ZooKeeper directory.
     *
     * @param zk
     *         Zk data access class
     * @param paths
     *         PathBuilder class to construct ZK paths
     * @param serializer
     *         ZK data serialization class
     */
    public PortZkManager(ZkManager zk, PathBuilder paths,
                         Serializer serializer) {
        super(zk, paths, serializer);
        this.bgpManager = new BgpZkManager(zk, paths, serializer);
        this.chainZkManager = new ChainZkManager(zk, paths, serializer);
        this.filterZkManager = new FiltersZkManager(zk, paths, serializer);
        this.routeZkManager = new RouteZkManager(zk, paths, serializer);
        this.tunnelZkManager = new TunnelZkManager(zk, paths, serializer);
        this.traceReqZkManager = new TraceRequestZkManager(zk, paths, serializer);
    }

    public PortZkManager(Directory zk, String basePath, Serializer serializer) {
        this(new ZkManager(zk, basePath), new PathBuilder(basePath), serializer);
    }

    @Override
    protected String getConfigPath(UUID id) {
        return paths.getPortPath(id);
    }

    @Override
    protected Class<PortConfig> getConfigClass() {
        return PortConfig.class;
    }

    public <T extends PortConfig> T get(UUID id, Class<T> clazz)
            throws StateAccessException, SerializationException {
        return get(id, clazz, null);
    }

    public <T extends PortConfig> T get(UUID id, Class<T> clazz,
            Runnable watcher) throws StateAccessException,
            SerializationException {
        log.debug("Get PortConfig: " + id);
        byte[] data = zk.get(paths.getPortPath(id), watcher);
        return serializer.deserialize(data, clazz);
    }

    private void addToPortGroupsOps(List<Op> ops, UUID id,
            Set<UUID> portGroupIds) throws StateAccessException {
        for (UUID portGroupId : portGroupIds) {

            // Check to make sure that the port group path exists.
            String pgPath =  paths.getPortGroupPortsPath(portGroupId);
            if (!zk.exists(pgPath)) {
                throw new IllegalArgumentException("Invalid port group " +
                        "passed in: " + portGroupId);
            }

            ops.add(Op.create(
                paths.getPortGroupPortPath(portGroupId, id), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        }
    }

    private void deleteFromPortGroupsOps(List<Op> ops, UUID id,
            Set<UUID> portGroupIds) {
        for (UUID portGroupId : portGroupIds) {
            ops.add(Op.delete(
                    paths.getPortGroupPortPath(portGroupId, id), -1));
        }
    }

    /**
     * This should be called for new ports that do not have routes associated
     * already.  This method does only linking, and does not move routes to the
     * routing table.
     */
    private void prepareLink(List<Op> ops, UUID id, UUID peerId,
                             PortConfig port, PortConfig peerPort,
                             boolean portExists, boolean peerPortExists)
            throws SerializationException, StateAccessException {

        port.setPeerId(peerId);
        peerPort.setPeerId(id);

        ops.add(Op.setData(paths.getPortPath(id),
                serializer.serialize(port), -1));
        ops.add(Op.setData(paths.getPortPath(peerId),
                serializer.serialize(peerPort), -1));

        //When a bridge port becomes an interior port, move the port
        // reference in the bridge to the logical-ports folder.
        if(port instanceof PortDirectory.BridgePortConfig) {
            ops.add(Op.delete(paths.getBridgePortPath(port.device_id, id),
                    -1));
            ops.add(Op.create(paths.getBridgeLogicalPortPath(
                            port.device_id, id), null,
                    Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        } //do nothing for router port
        if(peerPort instanceof PortDirectory.BridgePortConfig) {
            ops.add(Op.delete(paths.getBridgePortPath(peerPort.device_id,
                    peerId), -1));
            ops.add(Op.create(paths.getBridgeLogicalPortPath(
                            peerPort.device_id, peerId), null,
                    Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        } //do nothing for peer router port

        // Copy all the routes in port route directory to routing table.
        // If the port doesn't exist. The only route in this case is the local
        // route.  Just create that route without accessing ZK.
        if(port instanceof PortDirectory.RouterPortConfig) {

            if (portExists) {
                ops.addAll(routeZkManager.prepareCreatePortRoutesInTable(id));
            } else {
                routeZkManager.prepareCreatePortRouteInTable(
                        ops, id, (PortDirectory.RouterPortConfig) port);
            }

        }
        if(peerPort instanceof PortDirectory.RouterPortConfig) {

            if (peerPortExists) {
                ops.addAll(
                        routeZkManager.prepareCreatePortRoutesInTable(peerId));
            } else {
                routeZkManager.prepareCreatePortRouteInTable(
                        ops, peerId, (PortDirectory.RouterPortConfig) peerPort);
            }
        }
    }

    public void prepareLink(List<Op> ops, UUID id, UUID peerId)
            throws StateAccessException, SerializationException {

        PortConfig port = get(id);

        if (port.isUnplugged()) {
            PortConfig peerPort = get(peerId);
            if (!peerPort.isUnplugged()) {
                throw new IllegalArgumentException(
                        "peerId is not an unplugged port:" + id.toString());
            }

            prepareLink(ops, id, peerId, port, peerPort, true, true);
        } else {
            throw new IllegalArgumentException(
                    "Port 'id' is not an unplugged port: " + id);
        }
    }

    public List<Op> prepareUnlink(UUID id) throws StateAccessException,
            SerializationException {

        List<Op> ops = new ArrayList<>();
        PortConfig port = get(id);
        if (!port.isInterior()) {
            // If not linked, do nothing
            return ops;
        }

        UUID peerId = port.getPeerId();
        PortConfig peerPort = get(peerId);
        port.setPeerId(null);
        peerPort.setPeerId(null);

        // When an interior bridge port is unlinked,
        // move the port reference from the logical-ports/
        // folder to the ports/ folder
        if(port instanceof PortDirectory.BridgePortConfig) {
            ops.add(Op.delete(paths.getBridgeLogicalPortPath(
                    port.device_id, id), -1));
            ops.add(Op.create(paths.getBridgePortPath(port.device_id, id),
                    null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        }
        if(peerPort instanceof PortDirectory.BridgePortConfig) {
            ops.add(Op.delete(paths.getBridgeLogicalPortPath(
                    peerPort.device_id, peerId), -1));
            ops.add(Op.create(paths.getBridgePortPath(
                    peerPort.device_id, peerId),
                    null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        }

        // Delete routes from routing table for ports
        if(port instanceof PortDirectory.RouterPortConfig) {
            ops.addAll(routeZkManager.prepareDeletePortRoutesFromTable(id));
        }
        if(peerPort instanceof PortDirectory.RouterPortConfig) {
            ops.addAll(routeZkManager.prepareDeletePortRoutesFromTable(peerId));
        }

        ops.add(Op.setData(paths.getPortPath(id),
                serializer.serialize(port), -1));
        ops.add(Op.setData(paths.getPortPath(peerId),
                serializer.serialize(peerPort), -1));

        return ops;
    }

    public void prepareDelete(List<Op> ops, PortConfig cfg, boolean deletePeer)
            throws SerializationException, StateAccessException {

        if (deletePeer && cfg.isInterior()) {
            ops.addAll(prepareDelete(cfg.getPeerId()));

            // Set peer to null so that the next prepareDelete call does not
            // try to delete the peer port again.
            cfg.setPeerId(null);
        }

        ops.addAll(prepareDelete(cfg.id, cfg));
    }

    public void prepareDelete(List<Op> ops,
                              List<PortDirectory.RouterPortConfig> cfgs,
                              boolean deletePeer)
            throws SerializationException, StateAccessException {
        for (PortDirectory.RouterPortConfig cfg : cfgs) {
            prepareDelete(ops, cfg, deletePeer);
        }
    }

    public List<PortDirectory.RouterPortConfig> getRouterPorts(
            UUID routerId, List<IPv4Subnet> subs)
            throws SerializationException, StateAccessException {

        // Get the ports of this router
        List<UUID> portIds = getRouterPortIDs(routerId);
        List<PortDirectory.RouterPortConfig> cfgs = new ArrayList<>();
        for (UUID portId : portIds) {
            PortDirectory.RouterPortConfig rpCfg =
                    (PortDirectory.RouterPortConfig) get(portId);

            for (IPv4Subnet sub : subs) {
                if (rpCfg.hasSubnet(sub)) {
                    cfgs.add(rpCfg);
                    break;
                }
            }
        }
        return cfgs;
    }

    public List<Op> prepareClearRefsToChains(UUID id, UUID chainId)
            throws SerializationException, StateAccessException,
            IllegalArgumentException {
        if (chainId == null || id == null) {
            throw new IllegalArgumentException(
                    "chainId and id both must not be valid " +
                    "resource references");
        }
        boolean dataChanged = false;
        PortConfig config = get(id);
        if (Objects.equals(config.inboundFilter, chainId)) {
            config.inboundFilter = null;
            dataChanged = true;
        }
        if (Objects.equals(config.outboundFilter, chainId)) {
            config.outboundFilter = null;
            dataChanged = true;
        }
        return dataChanged ?
                Collections.singletonList(Op.setData(paths.getPortPath(id),
                        serializer.serialize(config), -1)) :
                Collections.<Op>emptyList();
    }

    public List<Op> prepareUpdate(UUID id, PortConfig config)
            throws StateAccessException, SerializationException {
        List<Op> ops = new ArrayList<Op>();

        // Get the old port config so that we can find differences created from
        // this update that requires other ZK directories to be updated.
        PortConfig oldConfig = get(id);

        ops.addAll(chainZkManager.prepareUpdateFilterBackRef(
                ResourceType.PORT, oldConfig.inboundFilter, config.inboundFilter,
                oldConfig.outboundFilter, config.outboundFilter, id));

        // Copy over only the fields that can be updated.
        // portAddr is not among them, otherwise we would have to update the
        // LOCAL routes too.
        oldConfig.inboundFilter = config.inboundFilter;
        oldConfig.outboundFilter = config.outboundFilter;
        oldConfig.properties = config.properties;
        oldConfig.adminStateUp = config.adminStateUp;

        ops.add(Op.setData(paths.getPortPath(id),
                           serializer.serialize(oldConfig), -1));

        return ops;
    }

    public void update(UUID id, PortConfig port) throws StateAccessException,
            SerializationException {
        zk.multi(prepareUpdate(id, port));
    }

    private List<Op> prepareRouterPortDelete(UUID id,
            PortDirectory.RouterPortConfig config) throws StateAccessException,
            SerializationException {
        List<Op> ops = new ArrayList<Op>();

        List<UUID> routeIds = routeZkManager.listPortRoutes(id, null);
        for (UUID routeId : routeIds) {
            ops.addAll(routeZkManager.prepareUnlinkedPortRouteDelete(routeId));
        }
        String portRoutesPath = paths.getPortRoutesPath(id);
        log.debug("Preparing to delete: " + portRoutesPath);
        ops.add(Op.delete(portRoutesPath, -1));

        String routerPortPath = paths.getRouterPortPath(config.device_id,
                id);
        log.debug("Preparing to delete: " + routerPortPath);
        ops.add(Op.delete(routerPortPath, -1));

        String portPath = paths.getPortPath(id);
        log.debug("Preparing to delete: " + portPath);
        ops.add(Op.delete(portPath, -1));
        ops.addAll(filterZkManager.prepareDelete(id));

        // Remove the reference of this port from the port groups
        if (config.portGroupIDs != null) {
            deleteFromPortGroupsOps(ops, id, config.portGroupIDs);
        }

        return ops;
    }


    private List<Op> prepareBridgePortDelete(UUID id,
            PortDirectory.BridgePortConfig config) throws StateAccessException {

        // Common operations for deleting logical and materialized
        // bridge ports
        List<Op> ops = new ArrayList<Op>();
        ops.add(Op.delete(paths.getPortPath(id), -1));
        ops.addAll(filterZkManager.prepareDelete(id));

        // Remove the reference of this port from the port groups
        if (config.portGroupIDs != null) {
            deleteFromPortGroupsOps(ops, id, config.portGroupIDs);
        }

        return ops;
    }

    private List<Op> prepareBridgeVlanDelete(UUID id,
            Short vlanId, PortDirectory.BridgePortConfig config)
            throws StateAccessException {

        // Delete
        List<Op> ops = new ArrayList<Op>();

        // The bridge may have been created before the per-VLAN MAC learning
        // feature was added.
        String vlanPath = paths.getBridgeVlanPath(id, vlanId);
        if (zk.exists(vlanPath)) {
            ops.addAll(zk.getRecursiveDeleteOps(vlanPath));
        }
        return ops;
    }

    public List<Op> prepareDelete(UUID id, PortConfig config)
            throws SerializationException, StateAccessException {
        List<Op> ops = new ArrayList<Op>();

        // delete any trace requests for device
        traceReqZkManager.deleteForDevice(id);

        String activePath = paths.getPortActivePath(id);
        try {
            Set<String> hosts = zk.getChildren(activePath);
            for (String host: hosts)
                ops.add(zk.getDeleteOp(activePath + "/" + host));
        } catch (NoStatePathException e) {
            // expected
        }
        if (zk.exists(activePath)) {
            ops.add(zk.getDeleteOp(activePath));
        }

        if (config.inboundFilter != null) {
            ops.addAll(chainZkManager.prepareChainBackRefDelete(
                    config.inboundFilter, ResourceType.PORT, id));
        }
        if (config.outboundFilter != null) {
            ops.addAll(chainZkManager.prepareChainBackRefDelete(
                config.outboundFilter, ResourceType.PORT, id));
        }

        if (config instanceof PortDirectory.RouterPortConfig) {
            ops.addAll(prepareDelete(id,
                    (PortDirectory.RouterPortConfig) config));
        } else if (config instanceof PortDirectory.BridgePortConfig) {
            ops.addAll(prepareDelete(id,
                    (PortDirectory.BridgePortConfig) config));
        } else if (config instanceof PortDirectory.VxLanPortConfig) {
            ops.addAll(prepareDelete(id,
                    (PortDirectory.VxLanPortConfig) config));
        } else {
            throw new IllegalArgumentException("Unknown port type found.");
        }
        return ops;
    }

    private List<Op> prepareDelete(UUID id,
        PortDirectory.RouterPortConfig config)
        throws StateAccessException, SerializationException {

        List<Op> ops = new ArrayList<Op>();
        if(config.isExterior()) {
            String hostVrnPath =
                    paths.getHostVrnPortMappingPath(config.getHostId(), id);
            ops.add(Op.delete(hostVrnPath, -1));
        } else if (config.isInterior()) {
            ops.addAll(prepareUnlink(id));
        }

        // Delete bgp path for all port types
        ops.addAll(bgpManager.preparePortDelete(id));
        String path = paths.getPortBgpPath(id);
        log.debug("Preparing to delete: " + path);
        ops.add(Op.delete(path, -1));

        if(config.tunnelKey != 0) {
            ops.addAll(tunnelZkManager.prepareTunnelDelete(config.tunnelKey));
        }

        // Get common router port deletion operations
        ops.addAll(prepareRouterPortDelete(id, config));

        return ops;
    }

    private List<Op> prepareDelete(UUID id, PortDirectory.VxLanPortConfig cfg)
        throws StateAccessException, SerializationException
    {
        List<Op> ops = new ArrayList<>();
        // TODO: I'm not 100% sure that we will indeed add a binding, check with
        // Hugo if this is necessary, so far LocalDataClientImpl does not create
        // that binding by itself.
        // String path = paths.getHostVrnPortMappingPath(cfg.getHostId(), id);
        // ops.add(Op.delete(path, -1));
        ops.add(Op.delete(paths.getBridgePortPath(cfg.device_id, id), -1));
        ops.add(Op.delete(paths.getVxLanPortIdPath(cfg.id), -1));
        ops.add(Op.delete(paths.getPortPath(id), -1));
        return ops;
    }

    private List<Op> prepareDelete(UUID id,
        PortDirectory.BridgePortConfig config)
        throws StateAccessException, SerializationException {

        List<Op> ops = new ArrayList<>();

        if (config.isExterior()) {
            // Remove the reference from the port interface mapping
            String path = paths.getHostVrnPortMappingPath(
                    config.getHostId(), id);
            ops.add(Op.delete(path, -1));
        } else if(config.isInterior()) {
            ops.addAll(prepareUnlink(id));
        }

        if (config.getVlanId() != null) {
            ops.addAll(prepareBridgeVlanDelete(config.device_id,
                config.getVlanId(), config));
        }
        if (config.tunnelKey != 0) {
            ops.addAll(tunnelZkManager.prepareTunnelDelete(config.tunnelKey));
        }

        // The port is already unlinked by now, so the port reference
        // will be in the ports folder.
        ops.add(Op.delete(paths.getBridgePortPath(config.device_id, id), -1));

        ops.addAll(prepareBridgePortDelete(id, config));

        return ops;
    }

    public List<Op> prepareDelete(UUID id) throws StateAccessException,
            SerializationException {
        List<Op> ops = new ArrayList<Op>();

        PortConfig config = get(id);
        if (config == null) {
            return ops;
        }

        ops.addAll(prepareDelete(id, config));
        return ops;
    }

    public List<UUID> listPortIDs(String path, Runnable watcher)
            throws StateAccessException {
        return getUuidList(path, watcher);
    }

    public PortDirectory.RouterPortConfig findFirstRouterPortMatch(
            UUID routerId,
            Function<PortDirectory.RouterPortConfig, Boolean> matcher)
            throws StateAccessException, SerializationException {
        List<UUID> ids = getRouterPortIDs(routerId);
        for (UUID id : ids) {
            PortDirectory.RouterPortConfig cfg =
                    (PortDirectory.RouterPortConfig) get(id);
            if (matcher.apply(cfg)) {
                return cfg;
            }
         }

        return null;
    }

    public boolean isActivePort(UUID portId) throws StateAccessException {
        String path = paths.getPortActivePath(portId);
        log.debug("checking port liveness");
        return zk.exists(path) && (zk.getChildren(path).size() > 0);
    }

    public PortDirectory.RouterPortConfig findFirstRouterPortMatchFromBridge(
            UUID bridgeId,
            Function<PortConfig, Boolean> matcher)
            throws StateAccessException, SerializationException {
        List<UUID> ids = getBridgeLogicalPortIDs(bridgeId);
        for (UUID id : ids) {
            PortDirectory.BridgePortConfig cfg =
                    (PortDirectory.BridgePortConfig) get(id);
            if (cfg.peerId == null) {
                continue;
            }

            PortConfig pCfg = get(cfg.peerId);
            if (matcher.apply(pCfg)) {
                return (PortDirectory.RouterPortConfig) pCfg;
            }
        }

        return null;
    }

    /**
     * Gets a list of router port IDs for a given router
     *
     * @param routerId
     *            The ID of the router to find the routes of.
     * @param watcher
     *            The watcher to set on the changes to the ports for this
     *            router.
     * @return A list of router port IDs.
     * @throws StateAccessException
     */
    public List<UUID> getRouterPortIDs(UUID routerId, Runnable watcher)
            throws StateAccessException {
        return listPortIDs(paths.getRouterPortsPath(routerId), watcher);
    }

    public List<UUID> getRouterPortIDs(UUID routerId)
            throws StateAccessException {
        return getRouterPortIDs(routerId, null);
    }

    /**
     * Gets a list of bridge port IDs for a given bridge
     *
     * @param bridgeId
     *            The ID of the bridge to find the routes of.
     * @param watcher
     *            The watcher to set on the changes to the ports for this
     *            router.
     * @return A list of bridge port IDs.
     * @throws StateAccessException
     */
    public List<UUID> getBridgePortIDs(UUID bridgeId, Runnable watcher)
            throws StateAccessException {
        return listPortIDs(paths.getBridgePortsPath(bridgeId), watcher);
    }

    public List<UUID> getBridgePortIDs(UUID bridgeId)
            throws StateAccessException {
        return getBridgePortIDs(bridgeId, null);
    }

    public void getVxLanPortIdsAsync(DirectoryCallback<Set<UUID>> callback,
                                     Directory.TypedWatcher watcher)
            throws StateAccessException {
        getUUIDSetAsync(paths.getVxLanPortIdsPath(), callback, watcher);
    }

    /**
     * Get the set of IDs of a Bridge's logical ports.
     *
     * @param bridgeId
     *            The ID of the bridge whose logical port IDs to retrieve.
     * @param watcher
     *            The watcher to notify if the set of IDs changes.
     * @return A set of logical port IDs.
     * @throws StateAccessException
     *             If a data error occurs while accessing ZK.
     */
    public List<UUID> getBridgeLogicalPortIDs(UUID bridgeId, Runnable watcher)
            throws StateAccessException {
        return listPortIDs(paths.getBridgeLogicalPortsPath(bridgeId),
                watcher);
    }

    public List<UUID> getBridgeLogicalPortIDs(UUID bridgeId)
            throws StateAccessException {
        return getBridgeLogicalPortIDs(bridgeId, null);
    }

    /***
     * Deletes a port and its related data from the ZooKeeper directories
     * atomically.
     */
    public void delete(UUID id) throws StateAccessException,
            SerializationException {
        zk.multi(prepareDelete(id));
    }

    public void link(UUID id, UUID peerId) throws StateAccessException,
            SerializationException {
        List<Op> ops = new ArrayList<>();
        prepareLink(ops, id, peerId);
        zk.multi(ops);
    }

    public void unlink(UUID id) throws StateAccessException,
            SerializationException {
        List<Op> ops = prepareUnlink(id);
        zk.multi(ops);
    }

    public Set<UUID> getPortGroupPortIds(UUID portGroupId)
            throws StateAccessException {

        String path = paths.getPortGroupPortsPath(portGroupId);
        Set<String> ids =  zk.getChildren(path);
        Set<UUID> portIds = new HashSet<>(ids.size());
        for (String id : ids) {
            portIds.add(UUID.fromString(id));
        }
        return portIds;

    }

}
