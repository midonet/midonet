/*
 * Copyright 2015 Midokura SARL
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
package org.midonet.cluster.rest_api.neutron.models;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import org.midonet.cluster.data.ZoomEnum;
import org.midonet.cluster.data.ZoomEnumValue;
import org.midonet.cluster.models.Commons;

@ZoomEnum(clazz = Commons.RuleDirection.class)
public enum RuleDirection {

    @ZoomEnumValue("EGRESS")
    EGRESS("egress"),

    @ZoomEnumValue("INGRESS")
    INGRESS("ingress");

    private final String value;

    RuleDirection(final String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    @SuppressWarnings("unused")
    public static RuleDirection forValue(String v) {
        if (v == null) return null;
        try {
            return valueOf(v.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
