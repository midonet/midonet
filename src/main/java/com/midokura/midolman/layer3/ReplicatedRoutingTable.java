package com.midokura.midolman.layer3;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.zookeeper.KeeperException;

import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.ReplicatedSet;
import com.midokura.midolman.util.Callback;

public class ReplicatedRoutingTable {

    private class ReplicatedRouteSet extends ReplicatedSet<Route> {

        public ReplicatedRouteSet(Directory d) {
            super(d);
        }

        protected String encode(Route rt) {
            return rt.toString();
        }

        protected Route decode(String str) {
            return Route.fromString(str);
        }
    };

    private class MyWatcher implements ReplicatedSet.Watcher<Route> {

        @Override
        public void process(Collection<Route> added, Collection<Route> removed) {
            for (Route rt : removed) {
                table.deleteRoute(rt);
            }
            for (Route rt : added) {
                table.addRoute(rt);
            }
            if (added.size() > 0 || removed.size() > 0)
                notifyWatchers();
        }
    }

    private RoutingTable table;
    private UUID routerId;
    private ReplicatedRouteSet routeSet;
    private Set<Callback<UUID>> watchers;

    public ReplicatedRoutingTable(UUID routerId, Directory routeDir) {
        this.routerId = routerId;
        table = new RoutingTable();
        watchers = new HashSet<Callback<UUID>>();
        routeSet = new ReplicatedRouteSet(routeDir);
        routeSet.addWatcher(new MyWatcher());
        // TODO(pino): perhaps move this into a start method?
        routeSet.start();
    }

    public void addWatcher(Callback<UUID> watcher) {
        watchers.add(watcher);
    }

    public void removeWatcher(Callback<UUID> watcher) {
        watchers.remove(watcher);
    }

    private void notifyWatchers() {
        // TODO(pino): should these be called asynchronously?
        for (Callback<UUID> watcher : watchers) {
            watcher.call(routerId);
        }
    }

    public void addRoute(Route rt) throws KeeperException, InterruptedException {
        routeSet.add(rt);
    }

    public void deleteRoute(Route rt) throws KeeperException,
            InterruptedException {
        routeSet.remove(rt);
    }

    public List<Route> lookup(int src, int dst) {
        return table.lookup(src, dst);
    }
}
