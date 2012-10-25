/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midonet.cluster;

import com.midokura.midolman.guice.zookeeper.ZKConnectionProvider;
import com.midokura.midolman.layer3.Route;
import com.midokura.midolman.state.*;
import com.midokura.midolman.state.zkManagers.RouteZkManager;
import com.midokura.midolman.state.zkManagers.RouterZkManager;
import com.midokura.midonet.cluster.client.ArpCache;
import com.midokura.midonet.cluster.client.RouterBuilder;
import com.midokura.packets.IntIPv4;
import com.midokura.packets.MAC;
import com.midokura.util.eventloop.Reactor;
import com.midokura.util.functors.Callback1;
import com.midokura.util.functors.Callback2;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.*;


public class ClusterRouterManager extends ClusterManager<RouterBuilder> {

    @Inject
    RouterZkManager routerMgr;

    @Inject
    RouteZkManager routeManager;

    @Inject
    @Named(ZKConnectionProvider.DIRECTORY_REACTOR_TAG)
    Reactor reactorLoop;

    @Inject
    ZkConfigSerializer serializer;

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
        log.info("Updating configuration for router {}", id);
        RouterBuilder builder = getBuilder(id);

        if(builder == null){
            log.error("Null builder for router {}", id.toString());
            return;
        }

        RouterZkManager.RouterConfig config = null;
        try {
            config = routerMgr.get(id, watchRouter(id));
        } catch (StateAccessException e) {
            log.error("Cannot retrieve the configuration for bridge {}",
                      id, e);
            return;
        }

        if (!isUpdate) {
            try {
                ArpTable arpTable = new ArpTable(
                    routerMgr.getArpTableDirectory(id));
                arpTable.start();
                builder.setArpCache(new ArpCacheImpl(arpTable));
            } catch (StateAccessException e) {
                log.error(
                    "Error retrieving ArpTable for router {}",
                    id, e);
            }
            try {
                ReplicatedRouteSet routeSet = new ReplicatedRouteSet(
                    routerMgr.getRoutingTableDirectory(id),
                    CreateMode.EPHEMERAL, builder);
                mapRouterIdToRoutes.put(id, routeSet);
                routeSet.start();
                log.debug("Started Routing Table for router {}", id);
            } catch (StateAccessException e) {
                log.error("Couldn't retrieve the RoutingTableDirectory", e);
            }
        }
        builder.setInFilter(config.inboundFilter)
            .setOutFilter(config.outboundFilter);
        if (isUpdate) // Not first time we're building
            builder.build();
        // else no need to build - the ReplicatedRouteSet will build.
        log.info("Update configuration for router {}", id);
    }

    Runnable watchRouter(final UUID id) {
        return new Runnable() {
            @Override
            public void run() {
                // return fast and update later
                getRouterConf(id, true);
                log.info("Added watcher for router {}", id);
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
            new Object[] {portId, routerId, active} );
        try {

            if(mapPortIdToRoutes.containsKey(portId)){
                log.error("The cluster client has already requested the routes for this port");
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
                } catch (Exception e) {
                    log.error("Got exception when running watcher for the routes of " +
                            "port {}", portId.toString(), e);
                }
            } else {
               log.info("Port has been cancelled: No routes watching for you.");
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
            log.info("(MM)Cancelling the port callbacks for port {}", portId);
            portIdCallback.get(portId).cancel();
            portIdWatcher.get(portId).cancel();
            // clean the router's routing table
            // and set the local routes cache to empty.
            updateRoutingTableAfterGettingRoutes(routerId, portId, Collections.<Route>emptySet());
        } else {
            // installing callbacks (or if they were cancelled, reenabling them).
            log.info("(MM) Adding callbacks for port {}", portId);
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
        public void onSuccess(Result<Set<Route>> data) {
            log.debug("(MM)GetRoutesCallback success, got {} routes {}",
                      data.getData().size(), data.getData());
            updateRoutingTableAfterGettingRoutes(routerId, portId,
                                                 data.getData());
        }
    }

    /**
     * This method does a diff between the received routes and the old routes (the ones
     * contained in local).
     * @param routerId
     * @param portId
     * @param newRoutes
     */
    private void updateRoutingTableAfterGettingRoutes(UUID routerId, UUID portId,
                                                      Set<Route> newRoutes){

        Set<Route> oldRoutes = mapPortIdToRoutes.get(portId);
        Directory dir = null;
        try {
            dir = routerMgr.getRoutingTableDirectory(routerId);
        } catch (StateAccessException e) {
            log.error("Error when trying to get the routing table for router {}",
                      routerId, e);
            return;
        }
        log.debug("(MM)Updating routes for port {} of router {}. Old routes {} " +
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


    class PortRoutesCallback implements DirectoryCallback<Set<UUID>>{

        UUID routerId;
        UUID portId;
        private boolean cancelled = false;

        PortRoutesCallback(UUID routerId, UUID portId) {
            log.info("Creating a PortRouteCallback for: "+routerId.toString() + " - " + portId.toString());
            this.routerId = routerId;
            this.portId = portId;
        }

        @Override
        public void onSuccess(Result<Set<UUID>> data) {
            if (!isCancelled()) {
                log.debug("(MM)PortRoutesCallback success, received {} routes: {} " +
                        "for port {}",
                        new Object[]{data.getData().size(), data.getData(), portId});

                Set<Route> oldRoutes = mapPortIdToRoutes.get(portId);

                if (data.getData().equals(oldRoutes)){
                    log.debug("No change in the routes, nothing to do for port {}", portId);
                    return;
                }

                // if the routes in zk are different from the routes contained in local update them
                // asynchronously.
                routeManager.asyncMultiRoutesGet(data.getData(),
                        new GetRoutesCallback(routerId,
                                portId,
                                "get routes for port"
                                        + portId.toString(),
                                log));
            } else {
                log.info("(MM)Port has been cancelled: No routes back for you.");
            }
        }

        @Override
        public void onTimeout() {
            if (!isCancelled()) {
                log.error("Callback timeout when trying to get routes for port {}",
                        portId);
            }
        }

        @Override
        public void onError(KeeperException e) {
            if (!isCancelled()) {
                log.info("ONERROR: port disappeared.");
                if (e instanceof KeeperException.NoNodeException) {
                    // If we get a NoStatePathException it means the someone removed
                    // the port routes. Remove all routes
                    updateRoutingTableAfterGettingRoutes(routerId, portId,
                            Collections.<Route>emptySet());
                }
                else {
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
            } catch (ZkStateSerializationException e) {
                log.warn("Encoding route {}", rt, e);
                return null;
            }
        }

        protected Route decode(String str) {
            try {
                return serializer.deserialize(str.getBytes(), Route.class);
            } catch (ZkStateSerializationException e) {
                log.warn("Decoding route {}", str, e);
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
            ArpTable.Watcher<IntIPv4, ArpCacheEntry> {

        ArpTable arpTable;
        private final Set<Callback2<IntIPv4, MAC>> listeners =
                        new LinkedHashSet<Callback2<IntIPv4, MAC>>();

        ArpCacheImpl(ArpTable arpTable) {
            this.arpTable = arpTable;
            this.arpTable.addWatcher(this);
        }

        @Override
        public void processChange(IntIPv4 key, ArpCacheEntry oldV,
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
                for (Callback2<IntIPv4, MAC> cb: listeners)
                    cb.call(key, (newV != null) ? newV.macAddr : null);
            }
        }

        @Override
        public void get(final IntIPv4 ipAddr,
                        final Callback1<ArpCacheEntry> cb,
                        final Long expirationTime) {
            // It's ok to do a synchronous get on the map because it only
            // queries local state (doesn't go remote like the other calls.
            cb.call(arpTable.get(ipAddr));
        }

        @Override
        public void add(final IntIPv4 ipAddr, final ArpCacheEntry entry) {
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
        public void remove(final IntIPv4 ipAddr) {
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
        public void notify(Callback2<IntIPv4, MAC> cb) {
            synchronized (listeners) {
                listeners.add(cb);
            }
        }

        @Override
        public void unsubscribe(Callback2<IntIPv4, MAC> cb) {
            synchronized (listeners) {
                listeners.remove(cb);
            }
        }


    }
}
