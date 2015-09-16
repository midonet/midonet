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
package org.midonet.cluster.rest_api.models;

import java.net.URI;
import java.util.UUID;

import org.midonet.packets.IPv6Addr;

public class Ipv6AddrGroupAddr extends IpAddrGroupAddr {

    public Ipv6AddrGroupAddr(){
        super();
    }

    public Ipv6AddrGroupAddr(UUID groupId, String addr) {
        super(groupId, addr);
    }

    public Ipv6AddrGroupAddr(URI baseUri, UUID groupId, IPv6Addr addr) {
        super(groupId, addr.toString());
        this.setBaseUri(baseUri);
    }

    @Override
    public int getVersion() {
        return 6;
    }
}
