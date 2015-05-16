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

import java.net.URI;

import org.midonet.cluster.rest_api.models.Bgp;
import org.midonet.packets.IPv4Addr;

public class BgpDataConverter {
    public static org.midonet.cluster.data.BGP toData(Bgp bgp) {
        return new org.midonet.cluster.data.BGP()
            .setId(bgp.id)
            .setPortId(bgp.portId)
            .setLocalAS(bgp.localAS)
            .setStatus(bgp.status)
            .setPeerAddr(IPv4Addr.fromString(bgp.peerAddr))
            .setPeerAS(bgp.peerAS);
    }

    public static Bgp fromData(org.midonet.cluster.data.BGP data, URI baseUri) {
        Bgp bgp = new Bgp();
        bgp.id = data.getId();
        bgp.localAS = data.getLocalAS();
        bgp.peerAddr = data.getPeerAddr().toString();
        bgp.peerAS = data.getPeerAS();
        bgp.portId = data.getPortId();
        bgp.status = data.getStatus();
        bgp.setBaseUri(baseUri);
        return bgp;
    }

}
