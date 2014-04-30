/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
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
    public final Map<Integer, UUID> vlanBindings;

    /** Stats of this port - refer to OVSDB VTEP schema documentation */
    public final Map<Integer, UUID> vlanStats;

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
                        Map<Integer, UUID> vlanBindings,
                        Map<Integer, UUID> vlanStats,
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
}
