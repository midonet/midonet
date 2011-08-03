package com.midokura.midolman.state;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.midokura.midolman.layer3.Route;
import com.midokura.midolman.rules.NatTarget;
import com.midokura.midolman.rules.Rule;

public class RouterDirectory {

    Directory dir;

    public RouterDirectory(Directory dir) {
        this.dir = dir;
    }

    public void addRouter(UUID routerId) {

    }

    public Collection<UUID> getRouters() {
        return null;
    }

    public void deleteRouter(UUID routerId) {

    }

    public void addRoute(UUID routerId, Route route) {

    }

    public Collection<Route> getRoutes(UUID routerId) {
        return null;
    }

    public void deleteRoute(UUID routerId, Route route) {

    }

    public void addRuleChain(UUID routerId, String name, List<Rule> rules) {

    }

    public Collection<String> getRuleChainNames(UUID routerId, Runnable watcher) {
        return null;
    }

    public List<Rule> getRuleChain(UUID routerId, String chainName,
            Runnable watcher) {
        return null;
    }

    public void deleteRuleChain(UUID routerId, String chainName) {

    }

    public Collection<NatTarget> getSnatBlocks(UUID routerId, Runnable watcher) {
        return null;
    }

    public void addSnatReservation(UUID routerId, NatTarget reservation) {

    }

    
}
