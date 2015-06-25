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

import java.util.List;

import org.slf4j.Logger;

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

    public List<Route> lookup(IPv4Addr src, IPv4Addr dst) {
        return legacyTable.lookup(src.toInt(), dst.toInt());
    }

    public List<Route> lookup(IPv4Addr src, IPv4Addr dst, Logger logger) {
        return legacyTable.lookup(src.toInt(), dst.toInt(), logger);
    }

}
