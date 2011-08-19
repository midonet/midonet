package com.midokura.midolman.layer3;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.zookeeper.KeeperException;

import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.ReplicatedSet;

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
    private ReplicatedRouteSet routeSet;
    private Set<Runnable> watchers;

    public ReplicatedRoutingTable(Directory routeDir) {
        table = new RoutingTable();
        watchers = new HashSet<Runnable>();
        routeSet = new ReplicatedRouteSet(routeDir);
        routeSet.addWatcher(new MyWatcher());
        // TODO(pino): perhaps move this into a start method?
        routeSet.start();
    }

    public void addWatcher(Runnable watcher) {
        watchers.add(watcher);
    }

    public void removeWatcher(Runnable watcher) {
        watchers.remove(watcher);
    }

    private void notifyWatchers() {
        // TODO(pino): should these be called asynchronously?
        for (Runnable w : watchers) {
            w.run();
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
