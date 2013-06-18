/*
 * Copyright 2013 Midokura Europe SARL
 */

package org.midonet.midolman.layer3;

import org.midonet.packets.IPv4Addr;

/**
 * TODO (galo) - transitional, wraps the legacy RoutesTrie implementation
 * into the new RoutingTableIfc interface. All classes depending on
 * RoutesTrie should be refactored to conform to the RoutesTrie interface.
 */
public class IPv4RoutingTable implements RoutingTableIfc<IPv4Addr> {

    private RoutingTable legacyTable = new RoutingTable();

    public void addRoute(Route rt) {
        legacyTable.addRoute(rt);
    }

    public void deleteRoute(Route rt) {
        legacyTable.deleteRoute(rt);
    }

    public Iterable<Route> lookup(IPv4Addr src, IPv4Addr dst) {
        return legacyTable.lookup(src.toInt(), dst.toInt());
    }

}
