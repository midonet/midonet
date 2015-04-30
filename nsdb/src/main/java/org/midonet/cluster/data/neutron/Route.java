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

public class Route {

    public Route() {}

    public Route(String destination, String nexthop) {
        this.destination = destination;
        this.nexthop = nexthop;
    }

    public String destination;
    public String nexthop;

    @Override
    public boolean equals(Object obj) {

        if (obj == this) return true;

        if (!(obj instanceof Route)) return false;

        final Route other = (Route) obj;

        return Objects.equal(destination, other.destination)
                && Objects.equal(nexthop, other.nexthop);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(destination, nexthop);
    }

    @Override
    public String toString() {

        return Objects.toStringHelper(this)
                .add("destination", destination)
                .add("nexthop", nexthop).toString();
    }

}
