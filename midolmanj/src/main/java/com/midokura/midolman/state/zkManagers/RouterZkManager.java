/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.state.zkManagers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkManager;
import com.midokura.midolman.state.ZkStateSerializationException;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Class to manage the router ZooKeeper data.
 */
public class RouterZkManager extends ZkManager {

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

    /**
     * Initializes a RouterZkManager object with a ZooKeeper client and the root
     * path of the ZooKeeper directory.
     *
     * @param zk
     *            Directory object.
     * @param basePath
     *            The root path.
     */
    public RouterZkManager(Directory zk, String basePath) {
        super(zk, basePath);
        routeZkManager = new RouteZkManager(zk, basePath);
        filterZkManager = new FiltersZkManager(zk, basePath);
        portZkManager = new PortZkManager(zk, basePath);

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
            throws ZkStateSerializationException {
        List<Op> ops = new ArrayList<Op>();
        ops.add(Op.create(pathManager.getRouterPath(id),
                serializer.serialize(config), Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT));

        ops.add(Op.create(pathManager.getRouterPortsPath(id), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        ops.add(Op.create(pathManager.getRouterRoutesPath(id), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        ops.add(Op.create(pathManager.getRouterRoutingTablePath(id), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        ops.add(Op.create(pathManager.getRouterArpTablePath(id), null,
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
     * @throws ZkStateSerializationException
     *             Serialization error occurred.
     * @throws com.midokura.midolman.state.StateAccessException
     */
    public List<Op> prepareRouterDelete(UUID id) throws StateAccessException {
        List<Op> ops = new ArrayList<Op>();

        // Get routes delete ops.
        List<UUID> routeIds = routeZkManager.listRouterRoutes(id, null);
        for (UUID routeId : routeIds) {
            ops.addAll(routeZkManager.prepareRouteDelete(routeId));
        }
        String routesPath = pathManager.getRouterRoutesPath(id);
        log.debug("Preparing to delete: " + routesPath);
        ops.add(Op.delete(routesPath, -1));

        // Get ports delete ops
        Set<UUID> portIds = portZkManager.getRouterPortIDs(id);
        for (UUID portId : portIds) {
            ops.addAll(portZkManager.prepareDelete(portId));
        }

        String portsPath = pathManager.getRouterPortsPath(id);
        log.debug("Preparing to delete: " + portsPath);
        ops.add(Op.delete(portsPath, -1));

        // Delete routing table
        String routingTablePath = pathManager.getRouterRoutingTablePath(id);
        log.debug("Preparing to delete: " + routingTablePath);
        ops.add(Op.delete(routingTablePath, -1));

        // Delete ARP table
        String arpTablePath = pathManager.getRouterArpTablePath(id);
        log.debug("Preparing to delete: " + arpTablePath);
        ops.add(Op.delete(arpTablePath, -1));

        String routerPath = pathManager.getRouterPath(id);
        log.debug("Preparing to delete: " + routerPath);
        ops.add(Op.delete(routerPath, -1));
        ops.addAll(filterZkManager.prepareDelete(id));
        return ops;
    }

    public void update(UUID id, RouterConfig cfg) throws StateAccessException {
        Op op = prepareUpdate(id, cfg);
        if (null != op) {
            List<Op> ops = new ArrayList<Op>();
            ops.add(op);
            multi(ops);
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
     * @throws ZkStateSerializationException
     *             if the RouterConfig could not be serialized.
     */
    public Op prepareUpdate(UUID id, RouterConfig config)
            throws StateAccessException {
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
        if (dataChanged) {
            return Op.setData(pathManager.getRouterPath(id),
                    serializer.serialize(config), -1);
        }
        return null;
    }

    /**
     * Performs an atomic update on the ZooKeeper to add a new router entry.
     *
     * @return The UUID of the newly created object.
     * @throws StateAccessException
     */
    public UUID create() throws StateAccessException {
        UUID id = UUID.randomUUID();
        multi(prepareRouterCreate(id, new RouterConfig()));
        return id;
    }

    /***
     * Deletes a router and its related data from the ZooKeeper directories
     * atomically.
     *
     * @param id
     *            ID of the router to delete.
     * @throws ZkStateSerializationException
     *             Serialization error occurred.
     * @throws StateAccessException
     */
    public void delete(UUID id) throws ZkStateSerializationException,
            StateAccessException {
        multi(prepareRouterDelete(id));
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
        return exists(pathManager.getRouterPath(id));
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
    public RouterConfig get(UUID id) throws StateAccessException {
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
            throws StateAccessException {
        byte[] data = get(pathManager.getRouterPath(id), watcher);
        return serializer.deserialize(data, RouterConfig.class);
    }

    public Directory getRoutingTableDirectory(UUID routerId)
            throws StateAccessException {
        return getSubDirectory(pathManager.getRouterRoutingTablePath(routerId));
    }

    public Directory getArpTableDirectory(UUID routerId)
            throws StateAccessException {
        return getSubDirectory(pathManager.getRouterArpTablePath(routerId));
    }
}
