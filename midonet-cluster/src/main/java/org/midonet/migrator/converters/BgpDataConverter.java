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

import org.midonet.migrator.models.Bgp;

public class BgpDataConverter {

    public static Bgp fromData(org.midonet.cluster.data.BGP bgpData) {
        Bgp b = new Bgp();
        b.id = bgpData.getId();
        b.portId = bgpData.getPortId();
        b.localAs = bgpData.getLocalAS();
        b.peerAs = bgpData.getPeerAS();
        b.peerAddress = bgpData.getPeerAddr();
        return b;
    }

}
