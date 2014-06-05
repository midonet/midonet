/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package org.midonet.cluster;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.inject.Inject;
import javax.inject.Named;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.midonet.cluster.client.ArpCache;
import org.midonet.cluster.client.RouterBuilder;
import org.midonet.midolman.guice.zookeeper.ZKConnectionProvider;
import org.midonet.midolman.layer3.Route;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.ArpCacheEntry;
import org.midonet.midolman.state.ArpTable;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.DirectoryCallback;
import org.midonet.midolman.state.NoStatePathException;
import org.midonet.midolman.state.ReplicatedSet;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.zkManagers.RouteZkManager;
import org.midonet.midolman.state.zkManagers.RouterZkManager;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.MAC;
import org.midonet.util.eventloop.Reactor;
import org.midonet.util.functors.Callback3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClusterRouterManager extends ClusterManager<RouterBuilder> {

    @Inject
    RouterZkManager routerMgr;

    @Inject
    RouteZkManager routeManager;

    @Inject
    @Named(ZKConnectionProvider.DIRECTORY_REACTOR_TAG)
    Reactor reactorLoop;

    @Inject
    Serializer serializer;

    Map<UUID, Set<Route>> mapPortIdToRoutes =
        new HashMap<UUID, Set<Route>>();

    Map<UUID, ReplicatedRouteSet> mapRouterIdToRoutes = new HashMap<UUID, ReplicatedRouteSet>();

    Map<UUID, PortRoutesCallback> portIdCallback = new HashMap<UUID, PortRoutesCallback>();
    Map<UUID, PortRoutesWatcher> portIdWatcher = new HashMap<UUID, PortRoutesWatcher>();

    Map<UUID, Set<Route>> routeGraveyard = new HashMap<UUID, Set<Route>>();

    private static final Logger log =
         LoggerFactory.getLogger(ClusterRouterManager.class);

    /**
     * Get the conf for a router.
     * @param id
     * @param isUpdate
     * @return
     */
    public void getRouterConf(final UUID id, final boolean isUpdate) {

        log.debug("Updating configuration for router {}", id);
        RouterBuilder builder = getBuilder(id);

        if (builder == null) {
            log.error("Null builder for router {}", id.toString());
            return;
        }

        RouterZkManager.RouterConfig config = null;
        ArpTable arpTable = null;
        ReplicatedRouteSet routeSet = null;

        try {
            if (!isUpdate) {
                arpTable = new ArpTable(routerMgr.getArpTableDirectory(id));
                arpTable.setConnectionWatcher(connectionWatcher);
                routeSet = new ReplicatedRouteSet(
                            routerMgr.getRoutingTableDirectory(id),
                            CreateMode.EPHEMERAL, builder);
                routeSet.setConnectionWatcher(connectionWatcher);
                mapRouterIdToRoutes.put(id, routeSet);
            }
            /* NOTE(guillermo) this the last zk-related call in this block
             * so that the watcher is not added in an undefined state.
             * We would not want to add the watcher (with update=true) and
             * then find that the ZK calls for the rest of the data fail. */
             config = routerMgr.get(id, watchRouter(id, true));
        } catch (NoStatePathException e) {
            log.debug("Router {} has been deleted", id);
        } catch (StateAccessException e) {
            if (routeSet != null)
                mapRouterIdToRoutes.remove(id);
            log.warn("Cannot retrieve the configuration for router {}", id, e);
            connectionWatcher.handleError(id.toString(), watchRouter(id, isUpdate), e);
            return;
        } catch (SerializationException e) {
            log.error("Could not deserialize router config {}", id, e);
            return;
        }

        if (config == null) {
            log.warn("Received null router config for {}", id);
            return;
        }

        builder.setAdminStateUp(config.adminStateUp)
               .setInFilter(config.inboundFilter)
               .setOutFilter(config.outboundFilter)
               .setLoadBalancer(config.loadBalancer);

        if (!isUpdate) {
            builder.setArpCache(new ArpCacheImpl(arpTable));
            arpTable.start();
            // note that the following may trigger a call to builder.build()
            // it should be the last call in the !isUpdate code path.
            routeSet.start();
            log.debug("Started ARP Table for router {}", id);
            log.debug("Started Routing Table for router {}", id);
        }

        if (isUpdate) // Not first time we're building
            builder.build();
        // else no need to build - the ReplicatedRouteSet will build.
        log.debug("Update configuration for router {}", id);
        log.debug("Added watcher for router {}", id);
    }

    Runnable watchRouter(final UUID id, final boolean isUpdate) {
        return new Runnable() {
            @Override
            public void run() {
                // return fast and update later
                getRouterConf(id, isUpdate);
            }
        };
    }

    @Override
    protected void getConfig(UUID id) {
        getRouterConf(id, false);
    }

    public void updateRoutesBecauseLocalPortChangedStatus(UUID routerId, UUID portId,
                                                          boolean active){
        log.debug("Port {} of router {} became active {}",
                new Object[]{portId, routerId, active});
        try {
            if (active) {
                if(mapPortIdToRoutes.containsKey(portId)){
                    log.error("The cluster client has already requested the routes for this port");
                }
            }
            handlePortRoutes(routerId, portId, active);
        } catch (Exception e) {
            log.error("Error when trying to get routes for port {} that became " +
                          "active locally", portId, e);
        }
    }


    class PortRoutesWatcher extends Directory.DefaultTypedWatcher {
        UUID routeId;
        UUID portId;
        private boolean cancelled = false;

        PortRoutesWatcher(UUID routeId, UUID portId) {
            this.routeId = routeId;
            this.portId = portId;
        }

        @Override
        public void run() {
            if (!isCancelled()) {
                try {
                    handlePortRoutes(routeId, portId, true);
                } catch (KeeperException e) {
                    log.error("Got exception when running watcher for the routes of " +
                            "port {}", portId.toString(), e);
                    connectionWatcher.handleError(portId.toString(), this, e);
                } catch (StateAccessException e) {
                    log.error("Got exception when running watcher for the routes of " +
                            "port {}", portId.toString(), e);
                    connectionWatcher.handleError(portId.toString(), this, e);
                }
            }
        }

        public void cancel() {
            this.cancelled = true;
        }

        public boolean isCancelled() {
            return cancelled;
        }

        public void reenable() {
            this.cancelled = true;
        }

    }

    /**
     * This method handles the routes <-> port relation when there is a change in a port state.
     * @param routerId
     * @param portId
     * @throws StateAccessException
     * @throws KeeperException
     */
    private void handlePortRoutes(UUID routerId, UUID portId, boolean active)
            throws StateAccessException, KeeperException {

        Set<Route> oldRoutes = mapPortIdToRoutes.get(portId);
        log.debug("Old routes: {}", oldRoutes);

        if(oldRoutes == null){
            mapPortIdToRoutes.put(portId, new HashSet<Route>());
        }

        if (!active) {
            // if port is down, just cancel these routes.
            // this won't allow routes to be retrieved or updated from this port.
            log.debug("Cancelling the port callbacks for port {}", portId);
            portIdCallback.get(portId).cancel();
            portIdWatcher.get(portId).cancel();
            // clean the router's routing table
            // and set the local routes cache to empty.
            updateRoutingTableAfterGettingRoutes(routerId, portId, Collections.<Route>emptySet());
        } else {
            // installing callbacks (or if they were cancelled, reenabling them).
            log.debug("Adding callbacks for port {}", portId);
            portIdCallback.put(portId, new PortRoutesCallback(routerId, portId));
            portIdWatcher.put(portId, new PortRoutesWatcher(routerId, portId));

            // lists all the routes for a given portId, handle them with the callback
            // and install the watcher in zk.
            routeManager.listPortRoutesAsync(portId,
                    portIdCallback.get(portId),
                    portIdWatcher.get(portId));
        }
    }

    class GetRoutesCallback extends
                           DirectoryCallback.DirectoryCallbackLogErrorAndTimeout<Set<Route>> {
        UUID routerId;
        UUID portId;

        GetRoutesCallback(UUID routerId, UUID portId, String itemInfo, Logger log) {
            super(itemInfo, log);
            this.routerId = routerId;
            this.portId = portId;
        }


        @Override
        public void onSuccess(Set<Route> routes) {
            log.debug("GetRoutesCallback success, got {} routes {}",
                      routes.size(), routes);
            updateRoutingTableAfterGettingRoutes(routerId, portId, routes);
        }
    }

    /**
     * This method does a diff between the received routes and the old routes (the ones
     * contained in local).
     * @param routerId
     * @param portId
     * @param newRoutes
     */
    private void updateRoutingTableAfterGettingRoutes(final UUID routerId,
                  final UUID portId, final Set<Route> newRoutes) {

        Set<Route> oldRoutes = mapPortIdToRoutes.get(portId);
        Directory dir = null;
        try {
            dir = routerMgr.getRoutingTableDirectory(routerId);
        } catch (StateAccessException e) {
            log.error("Error when trying to get the routing table for router {}",
                    routerId, e);
            // TODO(guillermo) should we handleError() here?
            return;
        }
        log.debug("Updating routes for port {} of router {}. Old routes {} " +
            "New routes {}",
            new Object[] {portId, routerId, oldRoutes, newRoutes});
        RouteEncoder encoder = new RouteEncoder();

        Set<Route> removedRoutes = new HashSet<Route>(oldRoutes);
        removedRoutes.removeAll(newRoutes);
        Set<Route> addedRoutes = new HashSet<Route>(newRoutes);
        addedRoutes.removeAll(oldRoutes);

        for(Route routeToAdd: addedRoutes){
            String path = "/" + encoder.encode(routeToAdd);
            dir.asyncAdd(path, null, CreateMode.EPHEMERAL);
            log.debug("Added new route for port {} in router {}, route {}",
                      new Object[]{portId, routerId, routeToAdd});
        }

        for(Route routeToRemove: removedRoutes){
            String path = "/" + encoder.encode(routeToRemove);
            dir.asyncDelete(path);
            log.debug("Deleted route for port {} in router {}, route {}",
                      new Object[]{portId, routerId, routeToRemove});
        }

        mapPortIdToRoutes.put(portId, newRoutes);
    }

    class PortRoutesCallback extends RetryCallback<Set<UUID>> {

        UUID routerId;
        UUID portId;
        private boolean cancelled = false;

        PortRoutesCallback(UUID routerId, UUID portId) {
            log.debug("Creating a PortRouteCallback for: "+routerId.toString() + " - " + portId.toString());
            this.routerId = routerId;
            this.portId = portId;
        }

        @Override
        protected String describe() {
            return "PortRoutes:" + portId;
        }

        @Override
        protected Runnable makeRetry() {
            return new Runnable() {
                @Override
                public void run() {
                    routeManager.listPortRoutesAsync(portId,
                            portIdCallback.get(portId),
                            portIdWatcher.get(portId));
                }
            };
        }

        @Override
        public void onSuccess(Set<UUID> uuids) {
            if (!isCancelled()) {
                log.debug("PortRoutesCallback success, received {} routes: {} " +
                        "for port {}",
                        new Object[]{uuids.size(), uuids, portId});

                Set<Route> oldRoutes = mapPortIdToRoutes.get(portId);

                if (uuids.equals(oldRoutes)){
                    log.debug("No change in the routes, nothing to do for port {}", portId);
                    return;
                }

                // if the routes in zk are different from the routes contained in local update them
                // asynchronously.
                routeManager.asyncMultiRoutesGet(uuids,
                        new GetRoutesCallback(routerId,
                                portId,
                                "get routes for port"
                                        + portId.toString(),
                                log));
            }
        }

        @Override
        public void onTimeout() {
            if (!isCancelled()) {
                log.error("Callback timeout when trying to get routes for port {}",
                        portId);
                connectionWatcher.handleTimeout(makeRetry());
            }
        }

        @Override
        public void onError(KeeperException e) {
            if (!isCancelled()) {
                if (e instanceof KeeperException.NoNodeException) {
                    // If we get a NoStatePathException it means the someone removed
                    // the port routes. Remove all routes
                    updateRoutingTableAfterGettingRoutes(routerId, portId,
                            Collections.<Route>emptySet());
                } else {
                    connectionWatcher.handleError(portId.toString(), makeRetry(), e);
                    log.error("Callback error when trying to get routes for port {}",
                            portId, e);
                }
            }
        }

        public void cancel() {
            this.cancelled = true;
        }

        public boolean isCancelled() {
            return this.cancelled;
        }

        public void reenable() {
            this.cancelled = false;
        }
    }

    class RouteEncoder {

        protected String encode(Route rt) {
            //TODO(dmd): this is slightly ghetto
            try {
                return new String(serializer.serialize(rt));
            } catch (SerializationException e) {
                log.error("Could not serialize route {}", rt, e);
                return null;
            }
        }

        protected Route decode(String str) {
            try {
                return serializer.deserialize(str.getBytes(), Route.class);
            } catch (SerializationException e) {
                log.error("Could not deserialize route {}", str, e);
                return null;
            }
        }
    }

    class ReplicatedRouteSet extends ReplicatedSet<Route> {
        RouteEncoder encoder = new RouteEncoder();

        public ReplicatedRouteSet(Directory d, CreateMode mode,
                                  RouterBuilder builder) {
            super(d, mode);
            if(builder != null)
                this.addWatcher(new RouteWatcher(builder));
        }

        @Override
        protected String encode(Route item) {
            return encoder.encode(item);
        }

        @Override
        protected Route decode(String str) {
            return encoder.decode(str);
        }
    }

    private class RouteWatcher implements ReplicatedSet.Watcher<Route> {

        RouterBuilder builder;

        private RouteWatcher(RouterBuilder routerBuilder) {
            builder = routerBuilder;
        }

        @Override
        public void process(Collection<Route> added,
                            Collection<Route> removed) {
            for (Route rt : removed) {
                builder.removeRoute(rt);
            }
            for (Route rt : added) {
                builder.addRoute(rt);
            }
            log.debug("RouteWatcher - Routes added {}, routes removed {}. " +
                          "Notifying builder",
                      added, removed);
            builder.build();
        }
    }


    class ArpCacheImpl implements ArpCache,
            ArpTable.Watcher<IPv4Addr, ArpCacheEntry> {

        ArpTable arpTable;
        private final Set<Callback3<IPv4Addr, MAC, MAC>> listeners =
                        new LinkedHashSet<Callback3<IPv4Addr, MAC, MAC>>();

        ArpCacheImpl(ArpTable arpTable) {
            this.arpTable = arpTable;
            this.arpTable.addWatcher(this);
        }

        @Override
        public void processChange(IPv4Addr key, ArpCacheEntry oldV,
                                               ArpCacheEntry newV) {
            if (oldV == null && newV == null)
                return;
            if (newV != null && oldV != null) {
                if (newV.macAddr == null && oldV.macAddr == null)
                    return;
                if (newV.macAddr != null && oldV.macAddr != null &&
                        newV.macAddr.equals(oldV.macAddr)) {
                    return;
                }
            }

            synchronized (listeners) {
                for (Callback3<IPv4Addr, MAC, MAC> cb: listeners) {
                    cb.call(key,
                            (oldV != null) ? oldV.macAddr : null,
                            (newV != null) ? newV.macAddr : null);
                }
            }
        }

        @Override
        public ArpCacheEntry get(final IPv4Addr ipAddr) {
            // It's ok to do a synchronous get on the map because it only
            // queries local state (doesn't go remote like the other calls.
            return arpTable.get(ipAddr);
        }

        @Override
        public void add(final IPv4Addr ipAddr, final ArpCacheEntry entry) {
            reactorLoop.submit(new Runnable() {

                @Override
                public void run() {
                    try {
                        arpTable.put(ipAddr, entry);
                    } catch (Exception e) {
                        log.error("Failed adding ARP entry. IP: {} MAC: {}",
                                  new Object[]{ipAddr, entry, e});
                    }
                }});
        }

        @Override
        public void remove(final IPv4Addr ipAddr) {
            reactorLoop.submit(new Runnable() {

                @Override
                public void run() {
                    try {
                        arpTable.removeIfOwner(ipAddr);
                    } catch (Exception e) {
                        log.error("Could not remove Arp entry for IP: {}",
                                  ipAddr, e);
                    }
                }});
        }

        @Override
        public void notify(Callback3<IPv4Addr, MAC, MAC> cb) {
            synchronized (listeners) {
                listeners.add(cb);
            }
        }

        @Override
        public void unsubscribe(Callback3<IPv4Addr, MAC, MAC> cb) {
            synchronized (listeners) {
                listeners.remove(cb);
            }
        }


    }
}
