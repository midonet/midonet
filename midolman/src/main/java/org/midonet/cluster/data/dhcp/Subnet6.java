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
import org.midonet.packets.IPv6Subnet;

import java.util.List;

/**
 * DHCPv6 subnet6
 */
public class Subnet6 extends Entity.Base<String, Subnet6.Data, Subnet6> {

    public Subnet6() {
        this(null, new Data());
    }

    public Subnet6(String addr, Data data) {
        super(addr, data);
    }

    @Override
    protected Subnet6 self() {
        return this;
    }

    public IPv6Subnet getPrefix() {
        return getData().prefix;
    }

    public Subnet6 setPrefix(IPv6Subnet prefix) {
        getData().prefix = prefix;
        return self();
    }

    public static class Data {

        public IPv6Subnet prefix;

        @Override
        public String toString() {
            return "Subnet6{" +
                    "prefix=" + prefix.toString() +
                    '}';
        }
    }
}
