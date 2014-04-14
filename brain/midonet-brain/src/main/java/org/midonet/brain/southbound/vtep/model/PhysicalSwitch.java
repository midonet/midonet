/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.brain.southbound.vtep.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A VTEP.
 */
public final class PhysicalSwitch {

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

    public PhysicalSwitch(String description,
                          String name,
                          Collection<String> ports,
                          Set<String> mgmtIps,
                          Set<String> tunnelIps) {
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
        return Objects.equals(name, that.name) &&
               Objects.equals(mgmtIps, that.mgmtIps) &&
               Objects.equals(tunnelIps, that.tunnelIps);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, mgmtIps, tunnelIps);
    }

    @Override
    public String toString() {
        return "PhysicalSwitch{" +
               "description='" + description + '\'' +
               ", name='" + name + '\'' +
               ", ports='" + ports + '\'' +
               ", mgmtIps=" + mgmtIps +
               ", tunnelIps=" + tunnelIps +
               '}';
    }
}
