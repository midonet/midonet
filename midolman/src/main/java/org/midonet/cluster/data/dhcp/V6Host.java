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
package org.midonet.cluster.data.dhcp;

import org.midonet.cluster.data.Entity;
import org.midonet.packets.IPv6Addr;

import java.util.UUID;

/**
 * DHCPv6 host
 */
public class V6Host extends Entity.Base<String, V6Host.Data, V6Host> {

    public V6Host() {
        this(null, new Data());
    }

    public V6Host(String clientId, Data data) {
        super(clientId, data);
    }

    @Override
    protected V6Host self() {
        return this;
    }

    public String getClientId() {
        return getData().clientId;
    }

    public V6Host setClientId(String clientId) {
        getData().clientId = clientId;
        return self();
    }

    public IPv6Addr getFixedAddress() {
        return getData().fixedAddress;
    }

    public V6Host setFixedAddress(IPv6Addr fixedAddress) {
        getData().fixedAddress = fixedAddress;
        return self();
    }

    public String getName() {
        return getData().name;
    }

    public V6Host setName(String name) {
        getData().name = name;
        return self();
    }

    public static class Data {

        public String clientId;
        public IPv6Addr fixedAddress;
        public String name;

    }
}
