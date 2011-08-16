package com.midokura.midolman.layer3;

import java.util.Collection;
import java.util.List;

import org.apache.zookeeper.KeeperException;

import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.ReplicatedSet;

public class ReplicatedRoutingTable implements RoutingTable {

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
        public void process(Collection<Route> added,
                Collection<Route> removed) {
            for(Route rt : removed) {
                trie.deleteRoute(rt);
            }
            for(Route rt : added) {
                trie.addRoute(rt);
            }
        }
    }

    private RoutingTrie trie;
    private ReplicatedRouteSet routeSet;

    public ReplicatedRoutingTable(Directory routeDir) {
        trie = new RoutingTrie();
        routeSet = new ReplicatedRouteSet(routeDir);
        routeSet.addWatcher(new MyWatcher());
        // TODO(pino): perhaps move this into a start method?
        routeSet.start();
    }

    @Override
    public void addRoute(Route rt) throws KeeperException, InterruptedException {
        routeSet.add(rt);
    }

    @Override
    public void deleteRoute(Route rt) throws KeeperException, InterruptedException {
        routeSet.remove(rt);
    }

    @Override
    public List<Route> lookup(int src, int dst) {
        return trie.lookup(src, dst);
    }
}
