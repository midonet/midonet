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

import java.util.Objects;

/**
 * Represents a VTEP's logical switch.
 */
public final class LogicalSwitch {

    /** The UUID of this switch in OVSDB. */
    public final UUID uuid;

    /** The description associated to this switch */
    public final String description;

    /** The name of the switch */
    public final String name;

    /** The VNI */
    public final Integer tunnelKey;

    public LogicalSwitch(UUID uuid, String desc,
                         String name, Integer tunnelKey) {
        this.uuid = uuid;
        this.description = desc;
        this.name = name;
        this.tunnelKey = tunnelKey;
    }

    @Override
    public String toString() {
       return "LogicalSwitch{"+
              "uuid=" + uuid + ", " +
              "description=" + description + ", " +
              "name=" + name + ", " +
              "tunnelKey=" + tunnelKey + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        LogicalSwitch that = (LogicalSwitch)o;
        return Objects.equals(uuid, that.uuid) &&
                Objects.equals(name, that.name) &&
                Objects.equals(description, that.description) &&
                Objects.equals(tunnelKey, that.tunnelKey);
    }

    @Override
    public int hashCode() {
        return uuid.hashCode();
    }

}
