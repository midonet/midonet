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
package org.midonet.midolman.topology;

import org.midonet.cluster.data.Route;
import org.midonet.packets.IPv4Addr;

import static org.midonet.midolman.layer3.Route.NO_GATEWAY;

public class Converter {
    public static org.midonet.midolman.layer3.Route toRouteConfig(
            Route route) {

        int gateway = route.getNextHopGateway() == null ? NO_GATEWAY :
                      IPv4Addr.stringToInt(route.getNextHopGateway());
        return new org.midonet.midolman.layer3.Route(
            IPv4Addr.stringToInt(route.getSrcNetworkAddr()),
            route.getSrcNetworkLength(),
            IPv4Addr.stringToInt(route.getDstNetworkAddr()),
            route.getDstNetworkLength(),
            route.getNextHop(),
            route.getNextHopPort(),
            gateway,
            route.getWeight(),
            route.getAttributes(),
            route.getRouterId(),
            route.isLearned()
        );
    }

    public static Route fromRouteConfig(
            org.midonet.midolman.layer3.Route route) {

        if (route == null)
            return null;

        return new Route()
                .setSrcNetworkAddr(route.getSrcNetworkAddr())
                .setSrcNetworkLength(route.srcNetworkLength)
                .setDstNetworkAddr(route.getDstNetworkAddr())
                .setDstNetworkLength(route.dstNetworkLength)
                .setNextHop(route.nextHop)
                .setNextHopPort(route.nextHopPort)
                .setNextHopGateway(route.getNextHopGateway())
                .setWeight(route.weight)
                .setAttributes(route.attributes)
                .setRouterId(route.routerId)
                .setLearned(route.isLearned())
        ;
    }
}
