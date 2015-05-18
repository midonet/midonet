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
package org.midonet.cluster.rest_api.conversion;

import java.net.URI;
import java.util.UUID;

import org.midonet.cluster.rest_api.models.TunnelZone;
import org.midonet.cluster.rest_api.models.TunnelZone.TunnelZoneType;

public class TunnelZoneDataConverter {

    public static TunnelZone fromData(
        org.midonet.cluster.data.TunnelZone tunnelZoneData,
        URI baseUri) throws IllegalAccessException {

        org.midonet.cluster.rest_api.models.TunnelZone
            tz = new org.midonet.cluster.rest_api.models.TunnelZone();

        tz.setId(UUID.fromString(tunnelZoneData.getId().toString()));
        tz.setName(tunnelZoneData.getName());
        tz.setType(TunnelZoneType.valueOf(tunnelZoneData.getType().toString()));
        tz.setBaseUri(baseUri);
        return tz;
    }

    public static org.midonet.cluster.data.TunnelZone toData(TunnelZone tz) {
        org.midonet.cluster.data.TunnelZone.Type t = null;

        if (TunnelZoneType.vxlan.equals(tz.getType())) {
            t = org.midonet.cluster.data.TunnelZone.Type.vxlan;
        } else if (TunnelZoneType.vtep.equals(tz.getType())) {
            t = org.midonet.cluster.data.TunnelZone.Type.vtep;
        } else if (TunnelZoneType.gre.equals(tz.getType())) {
            t = org.midonet.cluster.data.TunnelZone.Type.gre;
        } else {
            throw new IllegalArgumentException(
                "Unknown tunnel zone type: " + tz.getType());
        }
        return new org.midonet.cluster.data.TunnelZone()
                                           .setId(tz.getId())
                                           .setName(tz.getName())
                                           .setType(t);
    }

}
