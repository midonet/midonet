/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midonet.cluster;

import java.io.IOException;
import java.util.Collection;
import java.util.UUID;
import javax.inject.Inject;
import javax.inject.Named;

import org.apache.zookeeper.CreateMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.guice.zookeeper.ZKConnectionProvider;
import com.midokura.midolman.layer3.Route;
import com.midokura.midolman.state.ArpCacheEntry;
import com.midokura.midolman.state.ArpTable;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.ReplicatedSet;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.zkManagers.RouterZkManager;
import com.midokura.midolman.util.JSONSerializer;
import com.midokura.midonet.cluster.client.ArpCache;
import com.midokura.midonet.cluster.client.RouterBuilder;
import com.midokura.packets.IntIPv4;
import com.midokura.util.eventloop.Reactor;
import com.midokura.util.functors.Callback1;


public class ClusterRouterManager extends ClusterManager<RouterBuilder> {

    @Inject
    RouterZkManager routerMgr;

    @Inject
    @Named(ZKConnectionProvider.DIRECTORY_REACTOR_TAG)
    Reactor reactorLoop;

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
                    }
                    try {
                        ReplicatedRouteSet routeSet = new ReplicatedRouteSet(
                            routerMgr.getRoutingTableDirectory(id),
                            CreateMode.EPHEMERAL, builder);
                        // TODO(ross): since we don't pass a pointer to the
                        // builder, do something to prevent the
                        // ReplicatedRouteSet from being garbage-collected.
                        routeSet.start();
                    } catch (StateAccessException e) {
                        log.error("Couldn't retrieve the RoutingTableDirectory", e);
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
        ReplicatedRouteSet watchedSet;

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


    class ArpCacheImpl implements ArpCache {

        ArpTable arpTable;

        ArpCacheImpl(ArpTable arpTable) {
            this.arpTable = arpTable;
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

    }

}
