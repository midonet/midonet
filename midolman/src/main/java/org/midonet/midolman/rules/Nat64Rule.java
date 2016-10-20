/*
 * Copyright 2016 Midokura SARL
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

package org.midonet.midolman.rules;

import java.util.Objects;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.data.ZoomOneOf;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.util.IPSubnetUtil;
import org.midonet.midolman.simulation.PacketContext;
import org.midonet.packets.IPSubnet;

@ZoomClass(clazz = Topology.Rule.class )
@ZoomOneOf(name = "nat64_rule_data")
public class Nat64Rule extends Rule {

    @ZoomField(name = "port_address", converter = IPSubnetUtil.Converter.class)
    public IPSubnet<?> portAddress;

    @ZoomField(name = "nat_pool")
    public NatTarget natPool;

    @Override
    protected boolean apply(PacketContext pktCtx) {
        throw new IllegalStateException("NAT64 rules cannot be simulated");
    }

    @Override
    public int hashCode() {
        return super.hashCode() * 23 + natPool.hashCode();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other)
            return true;
        if (!(other instanceof Nat64Rule)) return false;
        if (!super.equals(other))
            return false;
        Nat64Rule r = (Nat64Rule) other;
        return Objects.equals(natPool, r.natPool);
    }

    @Override
    public String toString() {
        return "Nat64Rule [" + super.toString() + ", natPool=" + natPool + "]";
    }
}
