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

import java.net.Inet4Address;

import org.midonet.migrator.models.AdRoute;
import org.midonet.packets.IPv4Subnet;
import org.midonet.packets.IPv6Subnet;

public class AdRouteDataConverter {

    public static AdRoute fromData(org.midonet.cluster.data.AdRoute adRouteData) {
        AdRoute a = new AdRoute();
        a.id = adRouteData.getId();
        a.bgpId = adRouteData.getBgpId();
        if (adRouteData.getNwPrefix() instanceof Inet4Address) {
            a.subnet = new IPv4Subnet(adRouteData.getNwPrefix().getAddress(),
                                      adRouteData.getPrefixLength());
        } else {
            a.subnet = new IPv6Subnet(adRouteData.getNwPrefix().getAddress(),
                                      adRouteData.getPrefixLength());
        }
        return a;
    }

}
