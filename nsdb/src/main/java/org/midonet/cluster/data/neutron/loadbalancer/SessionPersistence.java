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