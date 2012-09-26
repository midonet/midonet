/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midonet.cluster;

import java.util.Collection;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.guice.zookeeper.ZKConnectionProvider;
import com.midokura.midolman.layer3.Route;
import com.midokura.midolman.state.ArpCacheEntry;
import com.midokura.midolman.state.ArpTable;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.DirectoryCallback;
import com.midokura.midolman.state.ReplicatedSet;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkConfigSerializer;
import com.midokura.midolman.state.ZkStateSerializationException;
import com.midokura.midolman.state.zkManagers.RouteZkManager;
import com.midokura.midolman.state.zkManagers.RouterZkManager;
import com.midokura.midonet.cluster.client.ArpCache;
import com.midokura.midonet.cluster.client.RouterBuilder;
import com.midokura.packets.IntIPv4;
import com.midokura.packets.MAC;
import com.midokura.util.eventloop.Reactor;
import com.midokura.util.functors.Callback1;
import com.midokura.util.functors.Callback2;


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

    private static final Logger log =
         LoggerFactory.getLogger(ClusterRouterManager.class);

    /**
     * Get the conf for a router.
     * @param id
     * @param isUpdate
     * @return
     */
    public Runnable getRouterConf(final UUID id, final boolean isUpdate) {
        return new Runnable() {
            @Override
            public void run() {
                log.info("Updating configuration for router {}", id);
                RouterBuilder builder = getBuilder(id);

                if(builder == null){
                    log.error("Null builder for router {}", id.toString());
                }

                RouterZkManager.RouterConfig config = null;
                try {
                    config = routerMgr.get(id, watchRouter(id));
                } catch (StateAccessException e) {
                    log.error("Cannot retrieve the configuration for bridge {}",
                              id, e);
                }
                ArpTable arpTable = null;
                if (config != null) {
                    if (!isUpdate) {
                        try {
                            arpTable = new ArpTable(
                                routerMgr.getArpTableDirectory(id));
                            arpTable.start();
                        } catch (StateAccessException e) {
                            log.error(
                                "Error retrieving ArpTable for bridge {}",
                                id, e);
                        }
                        try {
                            startRoutingTable(id, builder);
                        } catch (StateAccessException e) {
                            log.error("Couldn't retrieve the RoutingTableDirectory", e);
                        }
                    }
                }
                buildRouterFromConfig(id, config, builder, arpTable);
                log.info("Update configuration for router {}", id);
            }
        };
    }

    private void startRoutingTable(UUID routerId, RouterBuilder builder)
        throws StateAccessException {
        ReplicatedRouteSet routeSet = new ReplicatedRouteSet(
            routerMgr.getRoutingTableDirectory(routerId),
            CreateMode.EPHEMERAL, builder);
        mapRouterIdToRoutes.put(routerId, routeSet);
        routeSet.start();
        log.debug("Started Routing Table for router {}", routerId);
    }

    Runnable watchRouter(final UUID id) {
        return new Runnable() {
            @Override
            public void run() {
                // return fast and update later
                reactorLoop.submit(getRouterConf(id, true));
                log.info("Added watcher for router {}", id);
            }
        };
    }

    void buildRouterFromConfig(UUID id, RouterZkManager.RouterConfig config,
                               RouterBuilder builder, ArpTable arpTable) {

        builder.setInFilter(config.inboundFilter)
               .setOutFilter(config.outboundFilter);
        if(arpTable != null)
            builder.setArpCache(new ArpCacheImpl(arpTable));
        builder.build();
    }

    @Override
    public Runnable getConfig(UUID id) {
        return getRouterConf(id, false);
    }

    public void updateRoutesBecauseLocalPortChangedStatus(UUID routerId, UUID portId,
                                                          boolean active){
        if(!active){
            // do nothing the watcher should take care of removing the routes
            return;
        }
        try {

            if(mapPortIdToRoutes.containsKey(portId)){
                log.error("The cluster client has already requested the routes for this port");
            }
            Directory dir = routerMgr.getRoutingTableDirectory(routerId);
            getPortRoutes(routerId, portId, dir);
        } catch (Exception e) {
            log.error("Error when trying to get routes for port {} that became " +
                          "active locally", portId, e);
        }
    }


    class PortRoutesWatcher extends Directory.DefaultTypedWatcher {
        UUID routeId;
        UUID portId;
        Directory dir;

        PortRoutesWatcher(UUID routeId, UUID portId, Directory dir) {
            this.routeId = routeId;
            this.portId = portId;
            this.dir = dir;
        }

        @Override
        public void run() {
            try {
                getPortRoutes(routeId, portId, dir);
            } catch (Exception e) {
                log.error("Got exception when running watcher for the routes of " +
                              "port {}", portId.toString(), e);
            }
        }

    }

    private void getPortRoutes(UUID routerId, UUID portId, Directory dir)
        throws StateAccessException, KeeperException {

        Set<Route> oldRoutes = mapPortIdToRoutes.get(portId);

        if(oldRoutes == null){
            mapPortIdToRoutes.put(portId, new HashSet<Route>());
        }

        routeManager.listPortRoutesAsynch(portId,
                                          new PortRoutesCallback(routerId, portId, dir),
                                          new PortRoutesWatcher(routerId, portId, dir)) ;

    }

    private void updateRoutingTableAfterGettingRoutes(UUID routerId, UUID portId,
                                                      Directory dir,
                                                      Set<Route> newRoutes) {

        ReplicatedRouteSet routingTable = mapRouterIdToRoutes.get(routerId);
        Set<Route> oldRoutes = mapPortIdToRoutes.get(portId);
        RouteEncoder encoder = new RouteEncoder();

        if (newRoutes.equals(oldRoutes))
            return;
        Set<Route> removedRoutes = new HashSet<Route>(oldRoutes);
        removedRoutes.removeAll(newRoutes);
        Set<Route> addedRoutes = new HashSet<Route>(newRoutes);
        addedRoutes.removeAll(oldRoutes);

        for(Route routeToAdd: addedRoutes){
            String path = "/" + encoder.encode(routeToAdd);
            dir.asyncAdd(path, null, CreateMode.EPHEMERAL);
        }

        for(Route routeToRemove: removedRoutes){
            String path = "/" + encoder.encode(routeToRemove);
            dir.asyncDelete(path);
        }

        mapPortIdToRoutes.put(portId, newRoutes);
    }

    class PortRoutesCallback implements DirectoryCallback<Set<Route>>{

        UUID routerId;
        UUID portId;
        Directory dir;

        PortRoutesCallback(UUID routerId, UUID portId, Directory dir) {
            this.routerId = routerId;
            this.portId = portId;
            this.dir = dir;
        }

        @Override
        public void onSuccess(Result<Set<Route>> data) {
            log.debug("PortRoutesCallback success, received {} routes: {}",
                      data.getData().size(), data.getData());
            updateRoutingTableAfterGettingRoutes(routerId, portId, dir,
                                                 data.getData());
        }

        @Override
        public void onTimeout() {
            log.error("Callback timeout when trying to get routes for port {}",
                      portId);
        }

        @Override
        public void onError(KeeperException e) {
            if (e instanceof KeeperException.NoNodeException) {
                ReplicatedRouteSet routingTable = mapRouterIdToRoutes.get(routerId);
                if (routingTable == null) {
                    log.error("Null Routing Table for router {} when trying to delete" +
                                  "all routes for NoStatePathException", routerId, e);
                    return;
                }
                Set<Route> oldRoutes = mapPortIdToRoutes.get(portId);
                // If we get a NoStatePathException it means the someone removed
                // the port routes. Remove all routes
                for (Route route: oldRoutes) {
                     routingTable.remove(route);
                }
                mapPortIdToRoutes.remove(portId);
            }
            else {
                log.error("Callback error when trying to get routes for port {}",
                          portId, e);
            }
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
            addWatcher(builder);
        }

        public void addWatcher(RouterBuilder builder){
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
        boolean isUpdate = false;

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
            //if (added.size() > 0 || removed.size() > 0)
            //notifyWatchers(); //TODO(ross) shall we notify for flow removal?

            // If it's not the first time we execute this code, it means that
            // the ReplicatedSe fired an update, so we have to notify the builder
            if(isUpdate)
                builder.build();

            //TODO(ross) is there a better way?
            isUpdate = true;
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
            if (newV != null && oldV != null && newV.macAddr.equals(oldV.macAddr))
                return;

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
                                  new Object[]{ipAddr, entry});
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
                                  ipAddr);
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
