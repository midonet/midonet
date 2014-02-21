/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.midolman.state.zkManagers;

import java.util.*;

import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.AbstractZkManager;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkManager;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Class to manage the router ZooKeeper data.
 */
public class RouterZkManager extends AbstractZkManager {

    private final static Logger log = LoggerFactory
            .getLogger(RouterZkManager.class);

    public static class RouterConfig {

        public String name;
        public UUID inboundFilter;
        public UUID outboundFilter;
        public Map<String, String> properties = new HashMap<String, String>();

        public RouterConfig() {
            super();
        }

        public RouterConfig(UUID inboundFilter, UUID outboundFilter) {
            this.inboundFilter = inboundFilter;
            this.outboundFilter = outboundFilter;
        }

        public RouterConfig(String name, UUID inboundFilter, UUID outboundFilter) {
            this.name = name;
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
            int result = inboundFilter != null ? inboundFilter.hashCode() : 0;
            result = 31 * result
                    + (outboundFilter != null ? outboundFilter.hashCode() : 0);
            result = 31 * result + (name != null ? name.hashCode() : 0);
            return result;
        }
    }

    RouteZkManager routeZkManager;
    FiltersZkManager filterZkManager;
    PortZkManager portZkManager;
    ChainZkManager chainZkManager;

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
        chainZkManager = new ChainZkManager(zk, paths, serializer);
    }

    public RouterZkManager(Directory dir, String basePath,
                           Serializer serializer) {
        this(new ZkManager(dir), new PathBuilder(basePath), serializer);
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
        if (Objects.equals(config.inboundFilter, chainId)) {
            config.inboundFilter = null;
            dataChanged = true;
        }
        if (Objects.equals(config.outboundFilter, chainId)) {
            config.outboundFilter = null;
            dataChanged = true;
        }

        return dataChanged ?
                Collections.singletonList(Op.setData(paths.getRouterPath(id),
                        serializer.serialize(config), -1)) :
                Collections.<Op>emptyList();
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
        List<Op> ops = new ArrayList<Op>();
        ops.add(Op.create(paths.getRouterPath(id),
                serializer.serialize(config),
                Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT));

        if (config.inboundFilter != null) {
            ops.addAll(chainZkManager.prepareChainBackRefCreate(
                    config.inboundFilter, ResourceType.ROUTER, id));
        }
        if (config.outboundFilter != null) {
            ops.addAll(chainZkManager.prepareChainBackRefCreate(
                    config.outboundFilter, ResourceType.ROUTER, id));
        }

        ops.add(Op.create(paths.getRouterPortsPath(id), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        ops.add(Op.create(paths.getRouterRoutesPath(id), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        ops.add(Op.create(paths.getRouterRoutingTablePath(id), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        ops.add(Op.create(paths.getRouterArpTablePath(id), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        ops.addAll(filterZkManager.prepareCreate(id));
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
        Set<UUID> portIds = portZkManager.getRouterPortIDs(id);
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

        String routerPath = paths.getRouterPath(id);
        log.debug("Preparing to delete: " + routerPath);
        ops.add(Op.delete(routerPath, -1));
        ops.addAll(filterZkManager.prepareDelete(id));
        return ops;
    }

    public void update(UUID id, RouterConfig cfg) throws StateAccessException,
            SerializationException {
        List<Op> ops = new ArrayList<Op>();
        ops.addAll(prepareUpdate(id, cfg));
        if (ops.size() > 0) {
            zk.multi(ops);
        }
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
        String name1 = oldConfig.name;
        String name2 = config.name;
        if (name1 == null ? name2 != null : !name1.equals(name2))  {
            log.debug("The name of router {} changed from {} to {}",
                      new Object[] { id, name1, name2 });
            dataChanged = true;
        }
        if (dataChanged) {
            config.properties.clear();
            config.properties.putAll(oldConfig.properties);
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

    /**
     * Checks whether a router with the given ID exists.
     *
     * @param id
     *            Router ID to check
     * @return True if exists
     * @throws StateAccessException
     */
    public boolean exists(UUID id) throws StateAccessException {
        return zk.exists(paths.getRouterPath(id));
    }

    /**
     * Gets a RouterConfig object with the given ID.
     *
     * @param id
     *            The ID of the router.
     * @return RouterConfig object
     * @throws StateAccessException
     *             if deserialization of the Router's config failed, or if no
     *             Router with that ID could be found.
     */
    public RouterConfig get(UUID id) throws StateAccessException,
            SerializationException {
        return get(id, null);
    }

    /**
     * Gets a ZooKeeper node entry key-value pair of a router with the given ID
     * and sets a watcher for changes to the router's configuration.
     *
     * @param id
     *            The ID of the router.
     * @return RouterConfig object
     * @throws StateAccessException
     *             if deserialization of the Router's config failed, or if no
     *             Router with that ID could be found.
     */
    public RouterConfig get(UUID id, Runnable watcher)
            throws StateAccessException, SerializationException {
        byte[] data = zk.get(paths.getRouterPath(id), watcher);
        return serializer.deserialize(data, RouterConfig.class);
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
