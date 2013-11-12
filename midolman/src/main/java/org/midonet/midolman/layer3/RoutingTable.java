/*
 * Copyright 2011 Midokura KK
 */

package org.midonet.midolman.layer3;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Vector;

import org.midonet.packets.IPv4Addr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.packets.IPAddr;
import org.midonet.packets.IPv4Addr;

/**
 * TODO (galo, ipv6) - this class is IPv4 specific, should eventually be
 * be rewritten for compatibility with both versions and hooked in the
 * RoutingTableIfc hierarchy.
 */
class RoutingTable extends RoutesTrie {

    private final static Logger log = LoggerFactory.getLogger(RoutingTable.class);

    Iterable<Route> lookup(int src, int dst) {
        log.debug("lookup: src {} dst {} in table with {} routes",
                new Object[] {
                    IPv4Addr.intToString(src),
                    IPv4Addr.intToString(dst),
                    numRoutes} );

        List<Route> ret = new Vector<Route>();
        Iterator<Collection<Route>> rtIter = findBestMatch(dst);
        while (rtIter.hasNext()) {
            Collection<Route> routes = rtIter.next();
            int minWeight = Integer.MAX_VALUE;
            // Filter out the routes that don't match the source address and
            // return only those with the minimum weight.
            ret.clear();
            for (Route rt : routes) {
                if (addrsMatch(src, rt.srcNetworkAddr, rt.srcNetworkLength)) {
                    if (rt.weight < minWeight) {
                        ret.clear();
                        ret.add(rt);
                        minWeight = rt.weight;
                    } else if (rt.weight == minWeight)
                        ret.add(rt);
                }
            }
            if (ret.size() > 0)
                break;
        }

        log.debug("lookup: return {} for src {} dst {}",
                new Object[] {
                ret,
                IPv4Addr.intToString(src),
                IPv4Addr.intToString(dst)});

        return ret;
    }

    @Override
    protected Logger getLog() {
        return log;
    }

    @Override
    public String toString() {
        return "RoutingTable [dstPrefixTrie=" + dstPrefixTrie + "]";
    }
}
