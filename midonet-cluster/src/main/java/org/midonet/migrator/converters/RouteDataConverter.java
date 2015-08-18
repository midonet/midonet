/*
 * Copyright 2015 Midokura SARL
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

package org.midonet.migrator.converters;

import org.midonet.cluster.rest_api.models.Route;
import org.midonet.packets.IPv4Addr;

import static org.midonet.midolman.layer3.Route.NextHop;

public class RouteDataConverter {

    public static Route fromData(org.midonet.cluster.data.Route routeData) {
        Route r = new Route();
        r.id = routeData.getId();
        r.routerId = routeData.getRouterId();
        r.nextHopPort = routeData.getNextHopPort();
        r.attributes = routeData.getAttributes();
        r.dstNetworkAddr = routeData.getDstNetworkAddr();
        r.dstNetworkLength = routeData.getDstNetworkLength();
        r.srcNetworkAddr = routeData.getSrcNetworkAddr();
        r.srcNetworkLength = routeData.getSrcNetworkLength();
        if (IPv4Addr.stringToInt(routeData.getNextHopGateway()) !=
            org.midonet.midolman.layer3.Route.NO_GATEWAY) {
            r.nextHopGateway = routeData.getNextHopGateway();
        }
        r.learned = routeData.isLearned();
        if (routeData.getNextHop() == NextHop.BLACKHOLE) {
            r.type = Route.NextHop.BlackHole;
        } else if (routeData.getNextHop() == NextHop.REJECT) {
            r.type = Route.NextHop.Reject;
        } else {
            r.type = Route.NextHop.Normal;
        }
        r.weight = routeData.getWeight();
        return r;
    }

}
