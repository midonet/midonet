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
package org.midonet.cluster.data.neutron;

import com.google.common.base.Objects;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.IPv4Subnet;

import java.util.UUID;

public class ProviderRouter {

    public static final String NAME = "MidoNet Provider Router";
    public static final IPv4Subnet LL_CIDR = new IPv4Subnet(
            "169.254.255.0", 30);
    public static final IPv4Addr LL_GW_IP_1 = IPv4Addr.fromString(
            "169.254.255.1");
    public static final IPv4Addr LL_GW_IP_2 = IPv4Addr.fromString(
            "169.254.255.2");

    public ProviderRouter() {}

    public ProviderRouter(UUID id) {
        this.id = id;
    }

    public UUID id;

    @Override
    public boolean equals(Object obj) {

        if (obj == this) return true;

        if (!(obj instanceof ProviderRouter)) return false;
        final ProviderRouter other = (ProviderRouter) obj;

        return Objects.equal(id, other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);

    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("id", id).toString();
    }
}
