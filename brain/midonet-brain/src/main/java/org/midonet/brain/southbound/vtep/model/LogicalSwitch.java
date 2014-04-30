/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
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
