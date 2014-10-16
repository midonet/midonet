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
package org.midonet.cluster.data;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PortGroup extends Entity.Base<UUID, PortGroup.Data, PortGroup> {

    public enum Property {
        tenant_id
    }

    public PortGroup() {
        this(null, new Data());
    }

    public PortGroup(UUID id){
        this(id, new Data());
    }

    public PortGroup(Data data){
        this(null, data);
    }

    public PortGroup(UUID uuid, Data data) {
        super(uuid, data);
    }

    @Override
    protected PortGroup self() {
        return this;
    }

    public String getName() {
        return getData().name;
    }

    public PortGroup setName(String name) {
        getData().name = name;
        return this;
    }

    public boolean isStateful() {
        return getData().stateful;
    }

    public PortGroup setStateful(boolean stateful) {
        getData().stateful = stateful;
        return this;
    }

    public PortGroup setProperty(Property property, String value) {
        getData().properties.put(property.name(), value);
        return this;
    }

    public PortGroup setProperties(Map<String, String> properties) {
        getData().properties = properties;
        return this;
    }

    public String getProperty(Property property) {
        return getData().properties.get(property.name());
    }

    public Map<String, String> getProperties() {
        return getData().properties;
    }

    public static class Data {
        public String name;
        public Map<String, String> properties = new HashMap<String, String>();
        public boolean stateful = false;

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;

            Data that = (Data) o;

            if (name != null ? !name.equals(that.name) : that.name != null)
                return false;

            return stateful == that.stateful;
        }

        @Override
        public int hashCode() {
            int result = name.hashCode();
            return result;
        }

        @Override
        public String toString() {
            return "PortGroup " + "{name=" + name +  ", stateful=" + stateful + '}';
        }
    }
}
