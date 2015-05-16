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

package org.midonet.cluster.rest_api.conversion;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

import org.midonet.cluster.rest_api.models.AdRoute;

public class AdRouteDataConverter {

    public static AdRoute fromData(org.midonet.cluster.data.AdRoute data,
                                   URI baseUri) {
        AdRoute adRoute = new AdRoute(data.getId(),
                                      data.getNwPrefix().getHostAddress(),
                                      data.getPrefixLength(),
                                      data.getBgpId());
        adRoute.setBaseUri(baseUri);
        return adRoute;
    }

    public static org.midonet.cluster.data.AdRoute toData (AdRoute adRoute) {
        try {
            return new org.midonet.cluster.data.AdRoute()
                .setId(adRoute.id)
                .setBgpId(adRoute.bgpId)
                .setNwPrefix(InetAddress.getByName(adRoute.nwPrefix))
                .setPrefixLength(adRoute.prefixLength);
        } catch (UnknownHostException e) {
            // TODO: taken from legacy code, but is a DTO the right place to do
            // this check?
            throw new RuntimeException("Invalid nwPrefix: " + adRoute.nwPrefix,e);
        }
    }

}
