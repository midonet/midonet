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
package org.midonet.midolman.state.zkManagers;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.base.Preconditions;

public abstract class ConfigWithProperties extends BaseConfig {

    private enum Key {

        tenantId("tenant_id");

        private final String name;

        private Key(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    public Map<String, String> properties = new HashMap<>();

    @JsonIgnore
    public <K, V> void setProperty(K key, V val) {

        key = Preconditions.checkNotNull(key);
        val = Preconditions.checkNotNull(val);
        Preconditions.checkState(properties != null);

        properties.put(key.toString(), val.toString());
    }

    @JsonIgnore
    public <K> String getProperty(K key) {

        key = Preconditions.checkNotNull(key);
        Preconditions.checkState(properties != null);

        return properties.get(key.toString());
    }

    @JsonIgnore
    public <K> UUID getPropertyUuid(K key) {

        String val = getProperty(key);
        return val == null ? null : UUID.fromString(val);
    }

    @JsonIgnore
    public void setTenantId(String tenantId) {
        setProperty(Key.tenantId.toString(), tenantId);
    }

    @JsonIgnore
    public String getTenantId() {
        return getProperty(Key.tenantId.toString());
    }
}
