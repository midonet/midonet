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
import java.util.Objects;
import java.util.UUID;

public class Chain extends Entity.Base<UUID, Chain.Data, Chain> {

    public enum Property {
        tenant_id
    }

    public Chain() {
        this(null, new Data());
    }

    public Chain(UUID id){
        this(id, new Data());
    }

    public Chain(Data data){
        this(null, data);
    }

    public Chain(UUID uuid, Data data) {
        super(uuid, data);
    }

    @Override
    protected Chain self() {
        return this;
    }

    public String getName() {
        return getData().name;
    }

    public Chain setName(String name) {
        getData().name = name;
        return this;
    }

    public Chain setProperty(Property property, String value) {
        getData().properties.put(property.name(), value);
        return this;
    }

    public Chain setProperties(Map<String, String> properties) {
        getData().properties = properties;
        return this;
    }

    public String getProperty(Property property) {
        return getData().properties.get(property.name());
    }

    public Map<String, String> getProperties() {
        return getData().properties;
    }

    public boolean hasTenantId(String tenantId) {
        return Objects.equals(getProperty(Property.tenant_id), tenantId);
    }

    public static class Data {
        public String name;
        public Map<String, String> properties = new HashMap<String, String>();

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;

            Data that = (Data) o;
            if (name != null ? !name.equals(that.name) : that.name != null)
                return false;

            return true;
        }

        @Override
        public int hashCode() {
            return name != null ? name.hashCode() : 0;
        }

        @Override
        public String toString() {
            return "Chain.Data{" + "name=" + name + '}';
        }
    }
}
