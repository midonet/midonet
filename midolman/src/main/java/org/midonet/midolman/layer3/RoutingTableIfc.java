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

import org.midonet.packets.IPAddr;
import org.slf4j.Logger;

import java.util.List;

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
    void addRoute(Route rt);

    /**
     * Returns a route.
     */
    List<Route> lookup(IP src, IP dst);

    /**
     * Returns a route.
     */
    List<Route> lookup(IP src, IP dst, Logger logger);

}
