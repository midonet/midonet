/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.neutron.loadbalancer;

import com.google.common.base.Objects;

import org.codehaus.jackson.annotate.JsonProperty;

public class SessionPersistence {

    public SessionPersistence() {}

    public SessionPersistence(SessionPersistenceType type, String cookieName) {
        this.type = type;
        this.cookieName = cookieName;
    }

    public SessionPersistenceType type;

    @JsonProperty("cookie_name")
    public String cookieName;

    @Override
    public final boolean equals(Object obj) {

        if (obj == this)
            return true;

        if (!(obj instanceof SessionPersistence))
            return false;
        final SessionPersistence other = (SessionPersistence) obj;

        return Objects.equal(type, other.type)
               && Objects.equal(cookieName, other.cookieName);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(type, cookieName);
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
            .add("type", type)
            .add("cookieName", cookieName)
            .toString();
    }
}