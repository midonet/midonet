/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midonet.cluster;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
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
import com.midokura.midolman.state.NoStatePathException;
import com.midokura.midolman.state.ReplicatedSet;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.zkManagers.RouteZkManager;
import com.midokura.midolman.state.zkManagers.RouterZkManager;
import com.midokura.midolman.util.JSONSerializer;
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
                            ReplicatedRouteSet routeSet = new ReplicatedRouteSet(
                                routerMgr.getRoutingTableDirectory(id),
                                CreateMode.EPHEMERAL, builder);
                            routeSet.start();
                            mapRouterIdToRoutes.put(id, routeSet);
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

            mapPortIdToRoutes.put(portId, new HashSet<Route>());
            getPortRoutes(routerId, portId);
        } catch (Exception e) {
            log.error("Error when trying to get routes for port {} that became " +
                          "active locally", portId, e);
        }
    }


    class PortRoutesWatcher extends Directory.DefaultTypedWatcher {
        UUID routeId;
        UUID portId;

        PortRoutesWatcher(UUID routeId, UUID portId) {
            this.routeId = routeId;
            this.portId = portId;
        }

        @Override
        public void run() {
            try {
                getPortRoutes(routeId, portId);
            } catch (Exception e) {
                log.error("Got exception when running watcher for the routes of " +
                              "port {}", portId.toString(), e);
            }
        }

    }

    private void getPortRoutes(UUID routerId, UUID portId)
        throws StateAccessException, KeeperException {

        ReplicatedRouteSet routingTable = mapRouterIdToRoutes.get(routerId);
        Set<Route> oldRoutes = mapPortIdToRoutes.get(portId);

        if(oldRoutes == null){
            log.error("There no routes set for this port {}", portId);
            return;
        }

        if(routingTable == null){
            // we never request this router, let's schedule that
            reactorLoop.submit(getConfig(routerId));
            // let's put this call in the queue
            reactorLoop.submit(new PortRoutesWatcher(routerId, portId));
            return;
        }

        List<UUID> entries = Collections.emptyList();
        routeManager.listPortRoutesAsynch(portId,
                                          new PortRoutesCallback(routerId, portId),
                                          new PortRoutesWatcher(routerId, portId)) ;

    }

    private void updateRoutingTableAfterGettingRoutes(UUID routerId, UUID portId,
                                                      Set<Route> newRoutes) {

        ReplicatedRouteSet routingTable = mapRouterIdToRoutes.get(routerId);
        Set<Route> oldRoutes = mapPortIdToRoutes.get(portId);

        if (newRoutes.equals(oldRoutes))
            return;
        Set<Route> removedRoutes = new HashSet<Route>(oldRoutes);
        removedRoutes.removeAll(newRoutes);
        Set<Route> addedRoutes = new HashSet<Route>(newRoutes);
        addedRoutes.removeAll(oldRoutes);
        if( routingTable == null){
            log.error("Null routing table for router {}", routerId);
            return;
        }
        try {
            for(Route routeToAdd: addedRoutes){
                routingTable.add(routeToAdd);
            }

            for(Route routeToRemove: removedRoutes){
                routingTable.remove(routeToRemove);
            }
        } catch (KeeperException e) {
            log.error("Exception when adding new routes to the routing table of " +
                          "router {}", routerId, e);
        }
        mapPortIdToRoutes.put(portId, newRoutes);
    }

    class PortRoutesCallback implements DirectoryCallback<Set<Route>>{

        UUID routerId;
        UUID portId;

        PortRoutesCallback(UUID routerId, UUID portId) {
            this.routerId = routerId;
            this.portId = portId;
        }

        @Override
        public void onSuccess(Result<Set<Route>> data) {
            updateRoutingTableAfterGettingRoutes(routerId, portId, data.getData());
        }

        @Override
        public void onTimeout() {
            log.error("Callback timeout when trying to get routes for port {}",
                      portId);
        }

        @Override
        public void onError(KeeperException e) {
            // TODO(ross) check that
            if (e.getClass().equals(NoStatePathException.class)) {
                ReplicatedRouteSet routingTable = mapRouterIdToRoutes.get(routerId);
                Set<Route> oldRoutes = mapPortIdToRoutes.get(portId);
                // If we get a NoStatePathException it means the someone removed
                // the port routes. Remove all routes
                for(Route route: oldRoutes){
                    try {
                        routingTable.remove(route);
                    } catch (KeeperException e1) {
                        log.error("Error removing route {}", route.toString(), e);
                    }
                }
                mapPortIdToRoutes.remove(portId);
            }
            else {
                log.error("Callback error when trying to get routes for port {}",
                          portId, e);
            }
        }
    }

    class ReplicatedRouteSet extends ReplicatedSet<Route> {
        //TODO(ross) check if we have to inject this
        JSONSerializer serializer = new JSONSerializer();

        public ReplicatedRouteSet(Directory d, CreateMode mode,
                                  RouterBuilder builder) {
            super(d, mode);
            this.addWatcher(new RouteWatcher(builder));
        }

        protected String encode(Route rt) {
            //TODO(dmd): this is slightly ghetto
            try {
                return new String(serializer.objToBytes(rt));
            } catch (IOException e) {
                log.warn("ReplicatedRouteSet.encode", e);
                return null;
            }
        }

        protected Route decode(String str) {
            try {
                return serializer.bytesToObj(str.getBytes(), Route.class);
            } catch (Exception e) {
                log.warn("ReplicatedRouteSet.decode", e);
                return null;
            }
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
