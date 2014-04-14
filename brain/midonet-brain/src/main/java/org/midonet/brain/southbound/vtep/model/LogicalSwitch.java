/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.brain.southbound.vtep.model;

/**
 * Represents a VTEP's logical switch.
 */
public final class LogicalSwitch {

    /** The description associated to this switch */
    public final String description;

    /** The name of the switch */
    public final String name;

    /** The VNI */
    public final Integer tunnelKey;

    public LogicalSwitch(String desc, String name, Integer tunnelKey) {
        this.description = desc;
        this.name = name;
        this.tunnelKey = tunnelKey;
    }

    @Override
    public String toString() {
       return "LogicalSwitch{ "+
              "description=" + description + "," +
              "name=" + name + "," +
              "tunnelKey = " + tunnelKey + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        return o != null && getClass() == o.getClass() &&
               ((LogicalSwitch) o).name.equals(name);
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

}
