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

package org.midonet.brain.southbound.vtep.model;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.opendaylight.ovsdb.lib.notation.UUID;

/**
 * A physical port in a physical switch. Collections exposed as public
 * parameters are expected not to be modified by any users.
 *
 * TODO (galo): we could add guava and make these immutable, but a bit of
 * overkill for now.
 *
 */
public final class PhysicalPort {

    /** The description of this port */
    public final String description;

    /** The name of this port */
    public final String name;

    /** Bindings created on this port */
    public final Map<Short, UUID> vlanBindings;

    /** Stats of this port - refer to OVSDB VTEP schema documentation */
    public final Map<Short, UUID> vlanStats;

    /** Fault status of this port - refer to OVSDB VTEP schema documenation */
    public final Set<String> portFaultStatus;

    public PhysicalPort(String description, String name) {
        this.description = description;
        this.name = name;
        this.vlanBindings = new HashMap<>();
        this.vlanStats = new HashMap<>();
        this.portFaultStatus = new HashSet<>();
    }

    public PhysicalPort(String description, String name,
                        Map<Short, UUID> vlanBindings,
                        Map<Short, UUID> vlanStats,
                        Set<String> portFaultStatus) {
        this.description = description;
        this.name = name;
        this.vlanBindings = vlanBindings;
        this.vlanStats = vlanStats;
        this.portFaultStatus = portFaultStatus;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        return Objects.equals(name, ((PhysicalPort) o).name);
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

    @Override
    public String toString() {
        return "PhysicalPort{" +
               "name=" + name +
               ", description='" + description + '\'' +
               '}';
    }
}
