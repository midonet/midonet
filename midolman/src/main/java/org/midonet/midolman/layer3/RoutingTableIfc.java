/*
 * Copyright 2013 Midokura Europe SARL
 */
package org.midonet.midolman.layer3;

import org.midonet.packets.IPAddr;

/**
 * Defines the common interface for a Routes table generic for all versions of
 * IP addresses.
 *
 * @param <IP>
 */
public interface RoutingTableIfc<IP extends IPAddr> {

    /**
     * Adds a route to the Trie.
     * @param rt the new route
     */
    public void addRoute(Route rt);

    /**
     * Removes a route from the Trie.
     * @param rt the route to delete
     */
    public void deleteRoute(Route rt);

    /**
     * Returns a route.
     */
    public Iterable<Route> lookup(IP src, IP dst);

}
