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
