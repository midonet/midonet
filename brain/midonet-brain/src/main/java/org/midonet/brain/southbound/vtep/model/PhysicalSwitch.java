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

import org.opendaylight.ovsdb.lib.notation.UUID;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A VTEP.
 */
public final class PhysicalSwitch {

    /** The port's UUID in OVSDB. */
    public final UUID uuid;

    /** The description of this switch */
    public final String description;

    /** The name of the switch */
    public final String name;

    /** Physical ports available on this switch */
    public final List<String> ports;

    /** Management IP of the switch */
    public final Set<String> mgmtIps;

    /** Tunnel IP of the switch */
    public final Set<String> tunnelIps;

    public PhysicalSwitch(UUID uuid,
                          String description,
                          String name,
                          Collection<String> ports,
                          Set<String> mgmtIps,
                          Set<String> tunnelIps) {
        this.uuid = uuid;
        this.description = description;
        this.name = name;
        this.ports = new ArrayList<>(ports.size());
        this.ports.addAll(ports);
        this.mgmtIps = mgmtIps;
        this.tunnelIps = tunnelIps;
    }

    @Override
    public boolean equals(Object o) {

        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        PhysicalSwitch that = (PhysicalSwitch) o;
        return Objects.equals(uuid, that.uuid) &&
               Objects.equals(name, that.name) &&
               Objects.equals(mgmtIps, that.mgmtIps) &&
               Objects.equals(tunnelIps, that.tunnelIps);
    }

    @Override
    public int hashCode() {
        return uuid.hashCode();
    }

    @Override
    public String toString() {
        return "PhysicalSwitch{" +
               "uuid=" + uuid +
               ", description='" + description + '\'' +
               ", name='" + name + '\'' +
               ", ports='" + ports + '\'' +
               ", mgmtIps=" + mgmtIps +
               ", tunnelIps=" + tunnelIps +
               '}';
    }
}
