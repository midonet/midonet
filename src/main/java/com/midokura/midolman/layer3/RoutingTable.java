package com.midokura.midolman.layer3;

import java.util.List;

import org.apache.zookeeper.KeeperException;

public interface RoutingTable {

    void addRoute(Route rt) throws KeeperException, InterruptedException;

    void deleteRoute(Route rt) throws KeeperException, InterruptedException;

    List<Route> lookup(int src, int dst);
}
