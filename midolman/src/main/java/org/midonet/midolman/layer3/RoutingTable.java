/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.midolman.layer3;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Vector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.packets.IPAddr;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.IPv4Subnet;

/**
 * TODO (galo, ipv6) - this class is IPv4 specific, should eventually be
 * be rewritten for compatibility with both versions and hooked in the
 * RoutingTableIfc hierarchy.
 */
class RoutingTable extends RoutesTrie {

    private final static Logger log = LoggerFactory.getLogger(RoutingTable.class);

    Iterable<Route> lookup(int src, int dst) {
        if (log.isDebugEnabled()) {
            log.debug(String.format("lookup: src %s dst %s in table with %d routes",
                                    IPv4Addr.intToString(src),
                                    IPv4Addr.intToString(dst),
                                    numRoutes));
        }

        List<Route> ret = new Vector<>();
        Iterator<Collection<Route>> rtIter = findBestMatch(dst);
        while (rtIter.hasNext()) {
            Collection<Route> routes = rtIter.next();
            int minWeight = Integer.MAX_VALUE;
            // Filter out the routes that don't match the source address and
            // return only those with the minimum weight.
            ret.clear();
            for (Route rt : routes) {
                if (IPv4Subnet.addrMatch(src, rt.srcNetworkAddr, rt.srcNetworkLength)) {
                    if (rt.weight < minWeight) {
                        ret.clear();
                        ret.add(rt);
                        minWeight = rt.weight;
                    } else if (rt.weight == minWeight)
                        ret.add(rt);
                }
            }
            if (!ret.isEmpty())
                break;
        }

        if (log.isDebugEnabled()) {
            log.debug(String.format("lookup: return %s for src %s dst %s",
                      ret.toString(),
                      IPv4Addr.intToString(src),
                      IPv4Addr.intToString(dst)));
        }

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
