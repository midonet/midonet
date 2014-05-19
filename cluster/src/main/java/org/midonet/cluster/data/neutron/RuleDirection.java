/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.neutron;

import org.codehaus.jackson.annotate.JsonCreator;
import org.codehaus.jackson.annotate.JsonValue;

public enum RuleDirection {

    EGRESS("egress"),
    INGRESS("ingress");

    private final String value;

    private RuleDirection(final String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static RuleDirection forValue(String v) {
        if (v == null) return null;
        for (RuleDirection direction : RuleDirection.values()) {
            if (v.equalsIgnoreCase(direction.value)) {
                return direction;
            }
        }

        return null;
    }
}
