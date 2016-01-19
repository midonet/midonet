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

import java.util.*;

import com.google.common.base.Objects;
import org.midonet.cluster.data.neutron.Router;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.AbstractZkManager;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.NoStatePathException;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkManager;

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Op;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkState;


/**
 * Class to manage the router ZooKeeper data.
 */
public class RouterZkManager
        extends AbstractZkManager<UUID, RouterZkManager.RouterConfig> {

    private final static Logger log = LoggerFactory
            .getLogger(RouterZkManager.class);

    public static class RouterConfig extends ConfigWithProperties {

        public String name;
        public boolean adminStateUp;
        public UUID inboundFilter;
        public UUID outboundFilter;
        public UUID loadBalancer;

        public RouterConfig() {
            super();
        }

        public RouterConfig(UUID inboundFilter,
                            UUID outboundFilter, UUID loadBalancer) {
            this.inboundFilter = inboundFilter;
            this.outboundFilter = outboundFilter;
            this.loadBalancer = loadBalancer;
        }

        public RouterConfig(String name, UUID inboundFilter,
                            UUID outboundFilter, UUID loadBalancer) {
            this.name = name;
            this.inboundFilter = inboundFilter;
            this.outboundFilter = outboundFilter;
            this.loadBalancer = loadBalancer;
        }

        public RouterConfig(Router router, UUID inboundFilter,
                            UUID outboundFilter) {
            this.adminStateUp = router.adminStateUp;
            this.name = router.name;
            this.inboundFilter = inboundFilter;
            this.outboundFilter = outboundFilter;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;

            RouterConfig that = (RouterConfig) o;

            if (!Objects.equal(inboundFilter, that.inboundFilter))
                return false;
            if (!Objects.equal(outboundFilter, that.outboundFilter))
                return false;
            if (!Objects.equal(loadBalancer, that.loadBalancer))
                return false;
            if (!Objects.equal(name, that.name))
                return false;
            if (adminStateUp != that.adminStateUp)
                return false;

            return true;
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(inboundFilter, outboundFilter, loadBalancer,
                                    name, Boolean.valueOf(adminStateUp));
        }
    }

    RouteZkManager routeZkManager;
    FiltersZkManager filterZkManager;
    PortZkManager portZkManager;
    ChainZkManager chainZkManager;
    LoadBalancerZkManager loadBalancerZkManager;
    TraceRequestZkManager traceReqZkManager;

    private List<Op> updateLoadBalancerAssociation(UUID routerId,
                                                   RouterConfig oldConfig,
                                                   RouterConfig newConfig)
            throws StateAccessException, SerializationException {
        List<Op> ops = new ArrayList<>();

        if (oldConfig != null && oldConfig.loadBalancer != null) {
            ops.addAll(loadBalancerZkManager.prepareSetRouterId(
                    oldConfig.loadBalancer, null));
        }
        if (newConfig != null && newConfig.loadBalancer != null) {
            ops.addAll(loadBalancerZkManager.prepareSetRouterId(
                    newConfig.loadBalancer, routerId));
        }
        return ops;
    }

    /**
     * Initializes a RouterZkManager object with a ZooKeeper client and the root
     * path of the ZooKeeper directory.
     *
     * @param zk
     *         Zk data access class
     * @param paths
     *         PathBuilder class to construct ZK paths
     * @param serializer
     *         ZK data serialization class
     */
    public RouterZkManager(ZkManager zk, PathBuilder paths,
                           Serializer serializer) {
        super(zk, paths, serializer);
        routeZkManager = new RouteZkManager(zk, paths, serializer);
        filterZkManager = new FiltersZkManager(zk, paths, serializer);
        portZkManager = new PortZkManager(zk, paths, serializer);
        loadBalancerZkManager = new LoadBalancerZkManager(zk, paths, serializer);
        chainZkManager = new ChainZkManager(zk, paths, serializer);
        traceReqZkManager = new TraceRequestZkManager(zk, paths, serializer);
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
        RouterConfig config = get(id);
        if (Objects.equal(config.inboundFilter, chainId)) {
            config.inboundFilter = null;
            dataChanged = true;
        }
        if (Objects.equal(config.outboundFilter, chainId)) {
            config.outboundFilter = null;
            dataChanged = true;
        }

        return dataChanged ?
                Collections.singletonList(Op.setData(paths.getRouterPath(id),
                        serializer.serialize(config), -1)) :
                Collections.<Op>emptyList();
    }

    public List<Op> prepareClearRefsToLoadBalancer(UUID id, UUID loadBalancerId)
            throws SerializationException, StateAccessException,
            IllegalArgumentException {

        assert(loadBalancerId != null && id != null);

        RouterConfig config = get(id);
        if (!Objects.equal(config.loadBalancer, loadBalancerId)) {
            log.warn("Attempted to delete reference from router ID " + id +
                     " to load balancer ID " + loadBalancerId + " but the" +
                     " router had a reference to a different load balancer," +
                     " ID " + config.loadBalancer);
            return Collections.<Op>emptyList();
        }

        config.loadBalancer = null;
        return Collections.singletonList(Op.setData(paths.getRouterPath(id),
                        serializer.serialize(config), -1));
    }

    public void prepareUpdateLoadBalancer(List<Op> ops, UUID routerId, UUID loadBalancerId)
        throws StateAccessException, SerializationException {
        RouterConfig oldConf = get(routerId);
        // This is meant to be called to simply update the load balancer id.
        // It does not go through any of the back ref or association updating.
        String msg = "The router load balancer must not already be set in" +
                     " a simple update";
        checkState(oldConf.loadBalancer == null, msg);
        oldConf.loadBalancer = loadBalancerId;
        ops.add(Op.setData(paths.getRouterPath(routerId),
                           serializer.serialize(oldConf), -1));
    }

    @Override
    protected String getConfigPath(UUID id) {
        return paths.getRouterPath(id);
    }

    @Override
    protected Class<RouterConfig> getConfigClass() {
        return RouterConfig.class;
    }

    /**
     * Constructs a list of ZooKeeper update operations to perform when adding a
     * new router.
     *
     * @param id
     *            Router ID
     * @param config
     *            RouterConfig object.
     * @return A list of Op objects to represent the operations to perform.
     */
    public List<Op> prepareRouterCreate(UUID id, RouterConfig config)
            throws StateAccessException, SerializationException {
        List<Op> ops = new ArrayList<>();
        ops.add(simpleCreateOp(id, config));

        if (config.inboundFilter != null) {
            ops.addAll(chainZkManager.prepareChainBackRefCreate(
                    config.inboundFilter, ResourceType.ROUTER, id));
        }
        if (config.outboundFilter != null) {
            ops.addAll(chainZkManager.prepareChainBackRefCreate(
                    config.outboundFilter, ResourceType.ROUTER, id));
        }

        ops.addAll(zk.getPersistentCreateOps(
                paths.getRouterPortsPath(id),
                paths.getRouterRoutesPath(id),
                paths.getRouterRoutingTablePath(id),
                paths.getRouterArpTablePath(id)));

        ops.addAll(filterZkManager.prepareCreate(id));

        if (config.loadBalancer != null) {
            ops.addAll(updateLoadBalancerAssociation(id, null, config));
        }

        return ops;
    }

    /**
     * Constructs a list of operations to perform in a router deletion.
     *
     * @param id
     *            The ID of a virtual router to delete.
     * @return A list of Op objects representing the operations to perform.
     * @throws SerializationException
     *             Serialization error occurred.
     * @throws org.midonet.midolman.state.StateAccessException
     */
    public List<Op> prepareRouterDelete(UUID id) throws StateAccessException,
            SerializationException {
        List<Op> ops = new ArrayList<Op>();

        // delete any trace requests for device
        traceReqZkManager.deleteForDevice(id);

        RouterConfig config = get(id);
        if (config.inboundFilter != null) {
            ops.addAll(chainZkManager.prepareChainBackRefDelete(
                    config.inboundFilter, ResourceType.ROUTER, id));
        }
        if (config.outboundFilter != null) {
            ops.addAll(chainZkManager.prepareChainBackRefDelete(
                    config.outboundFilter, ResourceType.ROUTER, id));
        }

        // Get routes delete ops.
        List<UUID> routeIds = routeZkManager.listRouterRoutes(id, null);
        for (UUID routeId : routeIds) {
            ops.addAll(routeZkManager.prepareRouteDelete(routeId));
        }
        String routesPath = paths.getRouterRoutesPath(id);
        log.debug("Preparing to delete: " + routesPath);
        ops.add(Op.delete(routesPath, -1));

        // Get ports delete ops
        Collection<UUID> portIds = portZkManager.getRouterPortIDs(id);
        for (UUID portId : portIds) {
            ops.addAll(portZkManager.prepareDelete(portId));
        }

        String portsPath = paths.getRouterPortsPath(id);
        log.debug("Preparing to delete: " + portsPath);
        ops.add(Op.delete(portsPath, -1));

        // Delete routing table
        String routingTablePath = paths.getRouterRoutingTablePath(id);
        log.debug("Preparing to delete: " + routingTablePath);
        ops.add(Op.delete(routingTablePath, -1));

        // Delete ARP table (and any ARP entries found).
        String arpTablePath = paths.getRouterArpTablePath(id);
        for (String ipStr : zk.getChildren(arpTablePath, null)) {
            ops.add(Op.delete(arpTablePath + "/" + ipStr, -1));
        }

        log.debug("Preparing to delete: " + arpTablePath);
        ops.add(Op.delete(arpTablePath, -1));

        if (config.loadBalancer != null) {
            ops.addAll(updateLoadBalancerAssociation(id, config, null));
        }

        String routerPath = paths.getRouterPath(id);
        log.debug("Preparing to delete: " + routerPath);
        ops.add(Op.delete(routerPath, -1));
        ops.addAll(filterZkManager.prepareDelete(id));
        return ops;
    }

    public void update(UUID id, RouterConfig cfg) throws StateAccessException,
            SerializationException {
        List<Op> ops = prepareUpdate(id, cfg);
        zk.multi(ops);
    }

    /**
     * Construct a list of ZK operations needed to update the configuration of a
     * router.
     *
     * @param id
     *            ID of the router to update
     * @param config
     *            the new router configuration.
     * @return The ZK operation required to update the router.
     * @throws SerializationException
     *             if the RouterConfig could not be serialized.
     */
    public List<Op> prepareUpdate(UUID id, RouterConfig config)
            throws StateAccessException, SerializationException {
        List<Op> ops = new ArrayList<Op>();
        RouterConfig oldConfig = get(id);
        // Have the inbound or outbound filter changed?
        boolean dataChanged = false;
        UUID id1 = oldConfig.inboundFilter;
        UUID id2 = config.inboundFilter;
        if (id1 == null ? id2 != null : !id1.equals(id2)) {
            log.debug("The inbound filter of router {} changed from {} to {}",
                    new Object[] { id, id1, id2 });
            dataChanged = true;
        }

        id1 = oldConfig.outboundFilter;
        id2 = config.outboundFilter;
        if (id1 == null ? id2 != null : !id1.equals(id2)) {
            log.debug("The outbound filter of router {} changed from {} to {}",
                    new Object[] { id, id1, id2 });
            dataChanged = true;
        }

        // Has the loadBalancer changed?
        id1 = oldConfig.loadBalancer;
        id2 = config.loadBalancer;
        if (id1 == null ? id2 != null : !id1.equals(id2)) {
            log.debug("The loadBalancer of router {} changed from {} to {}",
                    new Object[] { id, id1, id2 });
            dataChanged = true;
        }

        // Has the name changed?
        String name1 = oldConfig.name;
        String name2 = config.name;
        if (name1 == null ? name2 != null : !name1.equals(name2))  {
            log.debug("The name of router {} changed from {} to {}",
                      new Object[] { id, name1, name2 });
            dataChanged = true;
        }

        if (config.adminStateUp != oldConfig.adminStateUp) {
            log.debug("The admin state of router {} changed from {} to {}",
                    new Object[] { id, oldConfig.adminStateUp,
                            config.adminStateUp });
            dataChanged = true;
        }

        if (dataChanged) {
            config.properties.clear();
            config.properties.putAll(oldConfig.properties);
            if (!Objects.equal(config.loadBalancer, oldConfig.loadBalancer)) {
                ops.addAll(
                    updateLoadBalancerAssociation(id, oldConfig, config));
            }
            ops.add(Op.setData(paths.getRouterPath(id),
                    serializer.serialize(config), -1));
        }

        if (dataChanged) {
            ops.addAll(chainZkManager.prepareUpdateFilterBackRef(
                            ResourceType.ROUTER, oldConfig.inboundFilter,
                            config.inboundFilter, oldConfig.outboundFilter,
                            config.outboundFilter, id));
        }
        return ops;
    }

    /**
     * Performs an atomic update on the ZooKeeper to add a new router entry.
     *
     * @return The UUID of the newly created object.
     * @throws StateAccessException
     */
    public UUID create() throws StateAccessException, SerializationException {
        UUID id = UUID.randomUUID();
        zk.multi(prepareRouterCreate(id, new RouterConfig()));
        return id;
    }

    /***
     * Deletes a router and its related data from the ZooKeeper directories
     * atomically.
     *
     * @param id
     *            ID of the router to delete.
     * @throws SerializationException
     *             Serialization error occurred.
     * @throws StateAccessException
     */
    public void delete(UUID id) throws SerializationException,
            StateAccessException {
        zk.multi(prepareRouterDelete(id));
    }

    public Directory getRoutingTableDirectory(UUID routerId)
            throws StateAccessException {
        return zk.getSubDirectory(paths.getRouterRoutingTablePath(routerId));
    }

    public Directory getArpTableDirectory(UUID routerId)
            throws StateAccessException {
        return zk.getSubDirectory(paths.getRouterArpTablePath(routerId));
    }
}
