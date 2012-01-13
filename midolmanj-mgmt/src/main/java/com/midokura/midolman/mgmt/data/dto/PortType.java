/*
 * @(#)PortType        1.6 12/1/10
 *
 * Copyright 2012 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dto;

import javax.xml.bind.annotation.XmlEnum;
import javax.xml.bind.annotation.XmlEnumValue;
import javax.xml.bind.annotation.XmlType;

/**
 * Enum representing port types.
 *
 * @version 1.6 10 Jan 2012
 * @author Ryu Ishimoto
 */
@XmlEnum
@XmlType(name = "port_type")
public enum PortType {

    /**
     * Bridge port
     */
    @XmlEnumValue("Bridge")
    BRIDGE("Bridge"),
    /**
     * Logical router port.
     */
    @XmlEnumValue("LogicalRouter")
    LOGICAL_ROUTER("LogicalRouter"),
    /**
     * Materialized router port.
     */
    @XmlEnumValue("MaterializedRouter")
    MATERIALIZED_ROUTER("MaterializedRouter");

    private final String value;

    private PortType(String val) {
        this.value = val;
    }

    /**
     * @return The PortType value.
     */
    public String getType() {
        return this.value;
    }
}
