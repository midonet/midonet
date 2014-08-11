/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.neutron.loadbalancer;

import org.codehaus.jackson.annotate.JsonCreator;
import org.codehaus.jackson.annotate.JsonValue;

public enum SessionPersistenceType {

    SOURCE_IP,
    HTTP_COOKIE,
    APP_COOKIE;

    @JsonValue
    public String value() {
        return this.name();
    }

    @JsonCreator
    public static SessionPersistenceType forValue(String v) {
        if (v == null) return null;

        for (SessionPersistenceType spType : SessionPersistenceType.values()) {
            if (v.equalsIgnoreCase(spType.value())) {
                return spType;
            }
        }

        return null;
    }

    @Override
    public String toString() {
        return value();
    }
}
